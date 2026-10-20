package com.meridian.reliability;

/**
 * @param maxAttempts          total attempts (including the original) before a human is paged
 * @param maxConsecutiveTimeouts trailing timeouts after which blind retries are considered futile
 * @param fastFailSeconds      failures quicker than this suggest a deterministic/config/data error
 */
public record RetryPolicy(int maxAttempts, int maxConsecutiveTimeouts, int fastFailSeconds) {
    public static RetryPolicy defaults() { return new RetryPolicy(3, 2, 15); }
    public RetryPolicy {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
}
