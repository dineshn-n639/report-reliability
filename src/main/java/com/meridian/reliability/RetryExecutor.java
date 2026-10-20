package com.meridian.reliability;

/** Seam to the real job runner. v1 ships only a dry-run implementation (no runner API was provided). */
public interface RetryExecutor {
    /** @param idempotencyKey stable per (run, next attempt) so a re-executed sweep can never double-submit. */
    String submit(RunAnalysis run, String idempotencyKey);

    static String keyFor(RunAnalysis run) { return run.runId() + ":attempt-" + (run.attemptsUsed() + 1); }

    /** Records what would be submitted; touches nothing. */
    final class DryRun implements RetryExecutor {
        @Override public String submit(RunAnalysis run, String key) { return "DRY-RUN would submit retry [" + key + "]"; }
    }
}
