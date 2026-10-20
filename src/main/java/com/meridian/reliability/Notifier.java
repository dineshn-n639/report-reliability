package com.meridian.reliability;

import java.io.IOException;

/** Where alerts go. Implementations must throw on failure so the monitor retries next cycle instead of losing the alert. */
public interface Notifier {
    void send(String subject, String body) throws IOException;

    /** Default when SMTP is not configured: prints the alert to the console. */
    final class Log implements Notifier {
        @Override public void send(String subject, String body) {
            System.out.println("[ALERT-LOG-ONLY] " + subject + "\n" + body);
        }
    }
}
