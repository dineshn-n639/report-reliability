package com.meridian.reliability;

import java.util.Locale;
import java.util.Set;

/**
 * Normalises the inconsistent status vocabulary in the job logs.
 * Design rule: an UNRECOGNISED status is never treated as success (fail-safe) and is surfaced for review.
 */
public final class StatusClassifier {
    public enum Outcome { SUCCESS, FAILURE, IN_FLIGHT, UNKNOWN }

    static final Set<String> SUCCESS = Set.of("completed", "success", "succeeded");
    static final Set<String> FAILURE = Set.of("failed", "timed_out", "error", "errored", "cancelled", "canceled", "aborted");
    static final Set<String> IN_FLIGHT = Set.of("pending", "queued", "running", "in_progress", "scheduled");

    private StatusClassifier() {}

    public static Outcome classify(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (SUCCESS.contains(s)) return Outcome.SUCCESS;
        if (FAILURE.contains(s)) return Outcome.FAILURE;
        if (IN_FLIGHT.contains(s)) return Outcome.IN_FLIGHT;
        return Outcome.UNKNOWN;
    }

    public static boolean isTimeout(String status) {
        return status != null && status.trim().equalsIgnoreCase("timed_out");
    }
}
