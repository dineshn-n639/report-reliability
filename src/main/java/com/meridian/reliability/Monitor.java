package com.meridian.reliability;

import com.meridian.reliability.RunAnalysis.State;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * One check cycle = reload the log, re-analyse, (dry-run) retry what is safe, and email what needs a human.
 * Alerts are de-duplicated per (run, state, attempts, last status) so a persistent failure does not email every cycle,
 * but a changed situation (e.g. another failed attempt) does.
 */
public final class Monitor {
    public record Event(Instant at, String kind, String message) {}
    public record Snapshot(Instant checkedAt, int attempts, List<RunAnalysis> runs, String error) {
        public long count(State s) { return runs.stream().filter(r -> r.state() == s).count(); }
        public boolean needsHuman() { return error != null || count(State.ESCALATE) + count(State.NEEDS_REVIEW) > 0; }
    }

    private static final int MAX_EVENTS = 50;
    private final Path file, stateFile;
    private final RunAnalyzer analyzer;
    private final Notifier notifier;
    private final RetryExecutor executor;
    private final Set<String> done = ConcurrentHashMap.newKeySet();
    private final Deque<Event> events = new ArrayDeque<>();
    private volatile Snapshot snapshot = new Snapshot(null, 0, List.of(), null);

    public Monitor(Path file, RetryPolicy policy, Notifier notifier, RetryExecutor executor, Path stateFile) {
        this.file = file; this.analyzer = new RunAnalyzer(policy); this.notifier = notifier;
        this.executor = executor; this.stateFile = stateFile;
        if (stateFile != null && Files.exists(stateFile)) {
            try { done.addAll(Files.readAllLines(stateFile)); } catch (IOException e) { event("WARN", "Could not read state file: " + e.getMessage()); }
        }
    }

    public Snapshot snapshot() { return snapshot; }
    public synchronized List<Event> events() { return new ArrayList<>(events); }

    public synchronized Snapshot check() {
        Instant now = Instant.now();
        List<JobAttempt> jobs;
        try {
            jobs = JobAttempt.loadAll(file);
        } catch (Exception e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            event("ERROR", "Cannot read job log " + file + " - " + msg);
            notifyOnce("monitor-error", "[Report Reliability] monitor cannot read the job log",
                    "The monitor failed to read/parse " + file + ":\n" + msg + "\n\nUntil fixed, failures will NOT be detected.");
            snapshot = new Snapshot(now, snapshot.attempts(), snapshot.runs(), msg);
            return snapshot;
        }
        done.remove("monitor-error");

        List<RunAnalysis> runs = analyzer.analyze(jobs);
        snapshot = new Snapshot(now, jobs.size(), runs, null);

        for (RunAnalysis r : runs) {
            if (r.state() != State.RETRY_ELIGIBLE) continue;
            String key = "retry:" + RetryExecutor.keyFor(r);
            if (done.add(key)) { event("RETRY", r.runId() + " (" + r.tenantName() + "): " + executor.submit(r, RetryExecutor.keyFor(r))); persist(); }
        }

        List<RunAnalysis> fresh = new ArrayList<>();
        for (RunAnalysis r : runs) {
            if ((r.state() == State.ESCALATE || r.state() == State.NEEDS_REVIEW) && !done.contains(alertKey(r))) fresh.add(r);
        }
        if (!fresh.isEmpty()) {
            String subject = "[Report Reliability] " + fresh.size() + " report run(s) need attention";
            StringBuilder b = new StringBuilder("These report runs never succeeded and automatic retry is not appropriate:\n\n");
            for (RunAnalysis r : fresh) {
                b.append("- ").append(r.runId()).append(" | ").append(r.tenantName()).append(" | ").append(r.reportName())
                        .append(" | scheduled ").append(r.scheduledAt()).append(" | ").append(r.state()).append('\n')
                        .append("  ").append(r.reason()).append('\n');
                if (!r.rootCauseHint().isEmpty()) b.append("  Likely cause: ").append(r.rootCauseHint()).append('\n');
                b.append("  Attempts: ").append(timeline(r.attempts())).append("\n\n");
            }
            try {
                notifier.send(subject, b.toString());
                for (RunAnalysis r : fresh) done.add(alertKey(r));
                persist();
                event("ALERT", subject + ": " + fresh.stream().map(RunAnalysis::runId).collect(Collectors.joining(", ")));
            } catch (IOException | RuntimeException e) {
                event("ALERT_FAILED", "Could not send alert (will retry next cycle): " + e.getMessage());
            }
        }
        event("CHECK", jobs.size() + " attempts, " + runs.size() + " runs, " + fresh.size() + " new alert(s)");
        return snapshot;
    }

    static String alertKey(RunAnalysis r) { return "alert:" + r.runId() + "|" + r.state() + "|" + r.attemptsUsed() + "|" + r.lastStatus(); }

    static String timeline(List<JobAttempt> a) {
        return a.stream().map(j -> j.attemptNumber() + ":" + j.status() + (j.durationSeconds() == null ? "" : "(" + j.durationSeconds() + "s)"))
                .collect(Collectors.joining(" -> "));
    }

    private void notifyOnce(String key, String subject, String body) {
        if (done.contains(key)) return;
        try { notifier.send(subject, body); done.add(key); event("ALERT", subject); }
        catch (IOException | RuntimeException e) { event("ALERT_FAILED", "Could not send alert: " + e.getMessage()); }
    }

    private void persist() {
        if (stateFile == null) return;
        try {
            List<String> lines = done.stream().filter(k -> !k.equals("monitor-error")).sorted().collect(Collectors.toList());
            Files.write(stateFile, lines);
        } catch (IOException e) { event("WARN", "Could not write state file: " + e.getMessage()); }
    }

    private synchronized void event(String kind, String msg) {
        events.addFirst(new Event(Instant.now(), kind, msg));
        while (events.size() > MAX_EVENTS) events.removeLast();
    }
}
