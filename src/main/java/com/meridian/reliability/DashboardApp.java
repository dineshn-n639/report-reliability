package com.meridian.reliability;

import com.sun.net.httpserver.HttpServer;

import java.nio.file.Path;
import java.util.concurrent.*;

/** Long-running mode: periodic checks + dashboard + email alerts. Start with: Main --serve [options]. */
public final class DashboardApp {
    private DashboardApp() {}

    public static void run(String[] args) throws Exception {
        String file = "data/report_jobs.json", host = "127.0.0.1", state = "alerted.state";
        int port = 8080, interval = 60, max = RetryPolicy.defaults().maxAttempts();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--serve" -> {}
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--host" -> host = args[++i];
                case "--interval" -> interval = Integer.parseInt(args[++i]);
                case "--max-attempts" -> max = Integer.parseInt(args[++i]);
                case "--state-file" -> state = args[++i];
                default -> file = args[i];
            }
        }
        if (interval < 5) throw new IllegalArgumentException("--interval must be >= 5 seconds");
        RetryPolicy d = RetryPolicy.defaults();
        RetryPolicy policy = new RetryPolicy(max, d.maxConsecutiveTimeouts(), d.fastFailSeconds());

        SmtpNotifier.Config smtp = SmtpNotifier.fromEnv(System.getenv());
        Notifier notifier = smtp == null ? new Notifier.Log() : new SmtpNotifier(smtp);
        System.out.println(smtp == null ? "Email: SMTP not configured (set SMTP_HOST and ALERT_TO) - alerts are logged to console only"
                : "Email: alerts to " + smtp.to() + " via " + smtp.host() + ":" + smtp.port() + " (" + smtp.security() + ")");

        Monitor monitor = new Monitor(Path.of(file), policy, notifier, new RetryExecutor.DryRun(), Path.of(state));
        monitor.check();

        ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "monitor-check"); t.setDaemon(true); return t; });
        sched.scheduleWithFixedDelay(() -> { try { monitor.check(); } catch (Throwable t) { System.err.println("Check failed: " + t); } }, interval, interval, TimeUnit.SECONDS);

        HttpServer server = Dashboard.start(monitor, host, port, Math.max(10, interval / 2));
        System.out.println("Dashboard: http://" + host + ":" + server.getAddress().getPort() + "/  (checking " + file + " every " + interval + "s)");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { sched.shutdownNow(); server.stop(0); }));
        Thread.currentThread().join();
    }
}
