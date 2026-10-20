package com.meridian.reliability;

import com.meridian.reliability.RunAnalysis.State;
import com.meridian.reliability.StatusClassifier.Outcome;

import java.util.*;
import java.util.stream.Collectors;

/** Groups attempts into logical runs and decides, per run, whether it ever really succeeded. */
public final class RunAnalyzer {
    private final RetryPolicy policy;

    public RunAnalyzer(RetryPolicy policy) { this.policy = policy; }

    public List<RunAnalysis> analyze(List<JobAttempt> jobs) {
        Map<String, List<JobAttempt>> byRun = new TreeMap<>();
        for (JobAttempt j : jobs) byRun.computeIfAbsent(j.runId(), k -> new ArrayList<>()).add(j);
        List<RunAnalysis> out = new ArrayList<>();
        for (List<JobAttempt> attempts : byRun.values()) {
            attempts.sort(Comparator.comparingInt(JobAttempt::attemptNumber));
            out.add(analyzeRun(attempts));
        }
        return out;
    }

    RunAnalysis analyzeRun(List<JobAttempt> a) {
        JobAttempt first = a.get(0), last = a.get(a.size() - 1);
        int used = last.attemptNumber();
        boolean everSucceeded = a.stream().anyMatch(j -> StatusClassifier.classify(j.status()) == Outcome.SUCCESS);
        String hint = rootCauseHint(a);

        State state; String reason;
        if (everSucceeded) {
            boolean clean = a.size() == 1 || a.stream().filter(j -> StatusClassifier.classify(j.status()) != Outcome.SUCCESS).count() == 0;
            state = clean ? State.SUCCEEDED : State.RECOVERED;
            reason = clean ? "Succeeded on first attempt" : "Succeeded on attempt " + successAttempt(a) + " after earlier failure(s)";
            hint = clean ? "" : hint;
        } else {
            Outcome lo = StatusClassifier.classify(last.status());
            if (lo == Outcome.IN_FLIGHT) {
                state = State.IN_FLIGHT; reason = "Latest attempt still '" + last.status() + "'";
            } else if (lo == Outcome.UNKNOWN) {
                state = State.NEEDS_REVIEW; reason = "Unrecognised status '" + last.status() + "' - not assumed success or failure";
            } else if (used >= policy.maxAttempts()) {
                state = State.ESCALATE; reason = "Retry budget exhausted (" + used + "/" + policy.maxAttempts() + " attempts)";
            } else if (trailingTimeouts(a) >= policy.maxConsecutiveTimeouts()) {
                state = State.ESCALATE; reason = trailingTimeouts(a) + " consecutive timeouts - blind retry unlikely to help";
            } else {
                state = State.RETRY_ELIGIBLE; reason = "Latest attempt '" + last.status() + "' with " + (policy.maxAttempts() - used) + " retr" + (policy.maxAttempts() - used == 1 ? "y" : "ies") + " left";
            }
        }
        return new RunAnalysis(first.runId(), first.tenantId(), first.tenantName(), first.reportName(),
                first.scheduledAt().toString(), state, used, last.status(), hint, reason, List.copyOf(a));
    }

    private static int successAttempt(List<JobAttempt> a) {
        return a.stream().filter(j -> StatusClassifier.classify(j.status()) == Outcome.SUCCESS)
                .mapToInt(JobAttempt::attemptNumber).min().orElse(-1);
    }

    private static int trailingTimeouts(List<JobAttempt> a) {
        int n = 0;
        for (int i = a.size() - 1; i >= 0 && StatusClassifier.isTimeout(a.get(i).status()); i--) n++;
        return n;
    }

    /** Deterministic rule-based triage. An LLM could replace/augment this (see design doc, bonus track). */
    String rootCauseHint(List<JobAttempt> a) {
        List<JobAttempt> fails = a.stream().filter(j -> StatusClassifier.classify(j.status()) == Outcome.FAILURE).collect(Collectors.toList());
        if (fails.isEmpty()) return "";
        long timeouts = fails.stream().filter(j -> StatusClassifier.isTimeout(j.status())).count();
        long fast = fails.stream().filter(j -> !StatusClassifier.isTimeout(j.status())
                && j.durationSeconds() != null && j.durationSeconds() < policy.fastFailSeconds()).count();
        if (timeouts == fails.size()) return timeouts > 1 ? "Repeated timeouts: likely performance/resource issue" : "Single timeout: possibly transient load";
        if (timeouts > 0 && fast > 0) return "Timeouts then fast hard-failure: resource pressure followed by deterministic error - investigate";
        if (fast == fails.size()) return fails.size() > 1 ? "Repeated fast failures: likely deterministic config/data error" : "Fast failure: early error (config/data/dependency); may be transient";
        return "Mixed failures: inspect job logs";
    }
}
