package com.meridian.reliability;

import java.util.List;

/** Verdict for one logical report run. */
public record RunAnalysis(String runId, String tenantId, String tenantName, String reportName, String scheduledAt,
                          State state, int attemptsUsed, String lastStatus, String rootCauseHint, String reason,
                          List<JobAttempt> attempts) {

    public enum State {
        SUCCEEDED,        // first attempt worked
        RECOVERED,        // failed at first, a later attempt succeeded (no customer impact)
        RETRY_ELIGIBLE,   // never succeeded, retry is safe and within budget
        ESCALATE,         // never succeeded, retrying is exhausted/futile -> human
        IN_FLIGHT,        // still running or queued; leave alone
        NEEDS_REVIEW      // unrecognised status; do not guess
    }

    public boolean neverSucceeded() {
        return state == State.RETRY_ELIGIBLE || state == State.ESCALATE || state == State.NEEDS_REVIEW;
    }
}
