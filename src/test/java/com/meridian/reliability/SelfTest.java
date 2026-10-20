package com.meridian.reliability;

import com.meridian.reliability.RunAnalysis.State;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/** Dependency-free test runner (no JUnit available offline). Exit code 1 on any failure. */
public final class SelfTest {
    static int passed, failed;

    static void check(String name, boolean ok) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    static JobAttempt job(String run, int n, String status, Integer dur) {
        return new JobAttempt("J-" + run + n, run, "T", "Tenant", "Rpt", LocalDateTime.parse("2026-09-01T02:00:00"), status, n, dur, null);
    }

    static State stateOf(RetryPolicy p, JobAttempt... js) {
        return new RunAnalyzer(p).analyze(Arrays.asList(js)).get(0).state();
    }

    public static void main(String[] args) throws Exception {
        RetryPolicy p = RetryPolicy.defaults();

        check("completed -> SUCCEEDED", stateOf(p, job("A", 1, "completed", 40)) == State.SUCCEEDED);
        check("fail then ok -> RECOVERED", stateOf(p, job("A", 1, "failed", 5), job("A", 2, "completed", 40)) == State.RECOVERED);
        check("'error' is a failure (starter-kit gap)", stateOf(p, job("A", 1, "error", 5)) == State.RETRY_ELIGIBLE);
        check("status case/whitespace tolerant", stateOf(p, job("A", 1, " ERROR ", 5)) == State.RETRY_ELIGIBLE);
        check("budget exhausted -> ESCALATE", stateOf(p, job("A", 1, "failed", 5), job("A", 2, "failed", 5), job("A", 3, "failed", 5)) == State.ESCALATE);
        check("two timeouts -> ESCALATE before budget", stateOf(p, job("A", 1, "timed_out", 300), job("A", 2, "timed_out", 300)) == State.ESCALATE);
        check("one timeout -> RETRY_ELIGIBLE", stateOf(p, job("A", 1, "timed_out", 300)) == State.RETRY_ELIGIBLE);
        check("running -> IN_FLIGHT, not retried", stateOf(p, job("A", 1, "running", null)) == State.IN_FLIGHT);
        check("unknown status never success", stateOf(p, job("A", 1, "weird_new_status", 1)) == State.NEEDS_REVIEW);
        check("input order irrelevant", stateOf(p, job("A", 2, "completed", 40), job("A", 1, "failed", 5)) == State.RECOVERED);
        check("failure after success still counts as delivered", stateOf(p, job("A", 1, "completed", 40), job("A", 2, "failed", 5)) == State.RECOVERED);
        check("maxAttempts=1 escalates immediately", stateOf(new RetryPolicy(1, 2, 15), job("A", 1, "failed", 5)) == State.ESCALATE);
        check("hint: repeated timeouts", new RunAnalyzer(p).analyzeRun(List.of(job("A", 1, "timed_out", 300), job("A", 2, "timed_out", 300))).rootCauseHint().contains("performance"));
        check("idempotency key", RetryExecutor.keyFor(new RunAnalyzer(p).analyzeRun(List.of(job("R", 1, "failed", 5)))).equals("R:attempt-2"));

        boolean threw = false;
        try { Json.parse("{\"a\": }"); } catch (IllegalArgumentException e) { threw = true; }
        check("malformed JSON rejected", threw);

        // End-to-end against the provided dataset
        List<JobAttempt> jobs = JobAttempt.loadAll(Path.of("data/report_jobs.json"));
        Map<String, RunAnalysis> by = new HashMap<>();
        for (RunAnalysis r : new RunAnalyzer(p).analyze(jobs)) by.put(r.runId(), r);
        check("dataset: 15 attempts", jobs.size() == 15);
        check("dataset: 12 runs", by.size() == 12);
        check("dataset: R-003 RECOVERED", by.get("R-003").state() == State.RECOVERED);
        check("dataset: R-005 ESCALATE", by.get("R-005").state() == State.ESCALATE);
        check("dataset: R-010 RETRY_ELIGIBLE", by.get("R-010").state() == State.RETRY_ELIGIBLE);
        check("dataset: exactly 2 never-succeeded", by.values().stream().filter(RunAnalysis::neverSucceeded).count() == 2);

        System.out.println("Tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
