package com.meridian.reliability;

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.meridian.reliability.SelfTest.check;

/** Tests for monitor de-duplication, SMTP client (against a fake server), and the HTTP dashboard. */
public final class DashboardTest {
    static String row(String id, String run, String tenant, int attempt, String status, int dur) {
        return "{\"job_id\":\"" + id + "\",\"run_id\":\"" + run + "\",\"tenant_id\":\"T\",\"tenant_name\":\"" + tenant
                + "\",\"report_name\":\"Monthly Statement\",\"scheduled_at\":\"2026-09-01T02:00:00\",\"status\":\"" + status
                + "\",\"attempt_number\":" + attempt + ",\"duration_seconds\":" + dur + ",\"row_count\":null}";
    }
    static void write(Path p, String... rows) throws IOException { Files.writeString(p, "{\"jobs\":[" + String.join(",", rows) + "]}"); }

    static final class Recorder implements Notifier {
        final List<String> subjects = new ArrayList<>(), bodies = new ArrayList<>();
        boolean fail;
        public void send(String s, String b) throws IOException { if (fail) throw new IOException("smtp down"); subjects.add(s); bodies.add(b); }
    }

    static final class FakeSmtp implements Runnable {
        final ServerSocket ss;
        final List<String> cmds = Collections.synchronizedList(new ArrayList<>());
        final StringBuilder data = new StringBuilder();
        FakeSmtp() throws IOException { ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); }
        void say(Writer w, String l) throws IOException { w.write(l + "\r\n"); w.flush(); }
        public void run() {
            try (Socket s = ss.accept()) {
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                say(w, "220 fake ready");
                String l;
                while ((l = r.readLine()) != null) {
                    cmds.add(l);
                    String u = l.toUpperCase(Locale.ROOT);
                    if (u.startsWith("EHLO")) say(w, "250-fake\r\n250 8BITMIME");
                    else if (u.startsWith("MAIL") || u.startsWith("RCPT")) say(w, "250 ok");
                    else if (u.equals("DATA")) {
                        say(w, "354 go");
                        while (!(l = r.readLine()).equals(".")) data.append(l).append('\n');
                        say(w, "250 queued");
                    } else if (u.equals("QUIT")) { say(w, "221 bye"); break; }
                    else say(w, "500 what");
                }
            } catch (IOException ignored) { }
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("mon");
        Path log = dir.resolve("jobs.json"), st = dir.resolve("state");

        // --- Monitor: alerting + de-duplication ---
        write(log, row("1", "R-1", "Good Co", 1, "completed", 40), row("2", "R-2", "Bad Co", 1, "timed_out", 300), row("3", "R-2", "Bad Co", 2, "timed_out", 300),
                row("4", "R-3", "Retry Co", 1, "error", 5));
        Recorder rec = new Recorder();
        Monitor m = new Monitor(log, RetryPolicy.defaults(), rec, new RetryExecutor.DryRun(), st);
        Monitor.Snapshot s1 = m.check();
        check("first check sends one digest", rec.subjects.size() == 1);
        check("digest names escalated run", rec.bodies.get(0).contains("R-2") && rec.bodies.get(0).contains("Bad Co"));
        check("retry-eligible run does not email", !rec.bodies.get(0).contains("R-3"));
        check("retry-eligible run logged as dry-run retry", m.events().stream().anyMatch(e -> e.kind().equals("RETRY") && e.message().contains("R-3:attempt-2")));
        check("snapshot needs human", s1.needsHuman());
        m.check();
        check("second identical check does not re-email", rec.subjects.size() == 1);
        check("retry not re-submitted", m.events().stream().filter(e -> e.kind().equals("RETRY")).count() == 1);

        write(log, row("1", "R-1", "Good Co", 1, "completed", 40), row("2", "R-2", "Bad Co", 1, "timed_out", 300), row("3", "R-2", "Bad Co", 2, "timed_out", 300),
                row("4", "R-3", "Retry Co", 1, "error", 5), row("5", "R-3", "Retry Co", 2, "error", 5), row("6", "R-3", "Retry Co", 3, "error", 5));
        m.check();
        check("newly exhausted run triggers a new email", rec.subjects.size() == 2 && rec.bodies.get(1).contains("R-3") && !rec.bodies.get(1).contains("R-2 |"));

        Monitor m2 = new Monitor(log, RetryPolicy.defaults(), rec, new RetryExecutor.DryRun(), st);
        m2.check();
        check("state file prevents re-alert after restart", rec.subjects.size() == 2);

        Recorder bad = new Recorder(); bad.fail = true;
        Monitor m3 = new Monitor(log, RetryPolicy.defaults(), bad, new RetryExecutor.DryRun(), null);
        m3.check();
        check("failed send is reported", m3.events().stream().anyMatch(e -> e.kind().equals("ALERT_FAILED")));
        bad.fail = false; m3.check();
        check("failed alert is retried next cycle", bad.subjects.size() == 1);

        Recorder rec4 = new Recorder();
        Monitor m4 = new Monitor(dir.resolve("missing.json"), RetryPolicy.defaults(), rec4, new RetryExecutor.DryRun(), null);
        Monitor.Snapshot s4 = m4.check(); m4.check();
        check("unreadable source flagged and alerted once", s4.error() != null && rec4.subjects.size() == 1 && s4.needsHuman());

        // --- SMTP against fake server ---
        FakeSmtp fake = new FakeSmtp();
        Thread t = new Thread(fake); t.start();
        SmtpNotifier smtp = new SmtpNotifier(new SmtpNotifier.Config("127.0.0.1", fake.ss.getLocalPort(), "none", null, null, "mon@example.com", List.of("a@example.com", "b@example.com")));
        smtp.send("Alert\r\nBcc: evil@example.com", "line1\n.\nline3 \u00e9");
        t.join(5000);
        String data = fake.data.toString();
        check("smtp: envelope", fake.cmds.contains("MAIL FROM:<mon@example.com>") && fake.cmds.contains("RCPT TO:<a@example.com>") && fake.cmds.contains("RCPT TO:<b@example.com>"));
        check("smtp: CRLF in subject cannot inject headers", !data.contains("\nBcc:") && data.contains("Subject: Alert Bcc: evil@example.com"));
        String payload = data.substring(data.indexOf("\n\n") + 2).replace("\n", "");
        check("smtp: body round-trips incl. lone dot and unicode", new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8).equals("line1\n.\nline3 \u00e9"));
        boolean refused = false;
        try { new SmtpNotifier(new SmtpNotifier.Config("127.0.0.1", 1, "none", "u", "p", "mon@example.com", List.of("a@example.com"))).send("x", "y"); }
        catch (IOException e) { refused = e.getMessage().contains("without TLS") || e.getMessage().contains("onnect"); }
        check("smtp: no credentials over plaintext", refused);
        boolean badAddr = false;
        try { new SmtpNotifier(new SmtpNotifier.Config("h", 25, "none", null, null, "mon@example.com", List.of("x@y.z\r\nRCPT TO:<z@z.z"))); }
        catch (IllegalArgumentException e) { badAddr = true; }
        check("smtp: recipient injection rejected", badAddr);
        check("smtp: env config absent -> null", SmtpNotifier.fromEnv(Map.of()) == null);
        check("smtp: env config parsed", SmtpNotifier.fromEnv(Map.of("SMTP_HOST", "h", "ALERT_TO", "a@b.c, d@e.f")).to().size() == 2);

        // --- HTTP dashboard ---
        write(log, row("1", "R-1", "<script>alert(1)</script>", 1, "completed", 40), row("2", "R-2", "Bad Co", 1, "failed", 5), row("3", "R-2", "Bad Co", 2, "failed", 5), row("4", "R-2", "Bad Co", 3, "failed", 5));
        Monitor md = new Monitor(log, RetryPolicy.defaults(), new Recorder(), new RetryExecutor.DryRun(), null);
        md.check();
        HttpServer srv = Dashboard.start(md, "127.0.0.1", 0, 30);
        String base = "http://127.0.0.1:" + srv.getAddress().getPort();
        HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        HttpResponse<String> home = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
        check("http: home 200 with failing run", home.statusCode() == 200 && home.body().contains("R-2") && home.body().contains("need human attention"));
        check("http: tenant name HTML-escaped (no XSS)", !home.body().contains("<script>alert(1)") && home.body().contains("&lt;script&gt;"));
        check("http: security headers", home.headers().firstValue("Content-Security-Policy").isPresent());
        HttpResponse<String> api = c.send(HttpRequest.newBuilder(URI.create(base + "/api/status")).build(), HttpResponse.BodyHandlers.ofString());
        check("http: api reports ESCALATE", api.statusCode() == 200 && api.body().contains("\"state\":\"ESCALATE\"") && api.body().contains("\"needsAttention\":true"));
        check("http: healthz", c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).build(), HttpResponse.BodyHandlers.ofString()).body().equals("ok"));
        check("http: GET /check rejected", c.send(HttpRequest.newBuilder(URI.create(base + "/check")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 405);
        check("http: POST /check redirects", c.send(HttpRequest.newBuilder(URI.create(base + "/check")).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 303);
        check("http: unknown path 404", c.send(HttpRequest.newBuilder(URI.create(base + "/nope")).build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 404);
        srv.stop(0);

        System.out.println("Dashboard tests: " + SelfTest.passed + " passed, " + SelfTest.failed + " failed");
        if (SelfTest.failed > 0) System.exit(1);
    }
}
