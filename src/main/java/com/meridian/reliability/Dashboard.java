package com.meridian.reliability;

import com.meridian.reliability.RunAnalysis.State;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/** Read-only status page + JSON API on the JDK's built-in HTTP server. No auth: bind to localhost or put behind a proxy. */
public final class Dashboard {
    private Dashboard() {}

    public static HttpServer start(Monitor m, String host, int port, int refreshSeconds) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(host, port), 0);
        s.createContext("/", ex -> {
            try { route(m, ex, refreshSeconds); }
            catch (Exception e) { respond(ex, 500, "text/plain", "Internal error"); }
            finally { ex.close(); }
        });
        s.setExecutor(Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "dashboard-http"); t.setDaemon(true); return t; }));
        s.start();
        return s;
    }

    private static void route(Monitor m, HttpExchange ex, int refresh) throws IOException {
        String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
        ex.getRequestBody().readAllBytes();
        boolean read = method.equals("GET") || method.equals("HEAD");
        switch (path) {
            case "/" -> { if (read) respond(ex, 200, "text/html", html(m.snapshot(), m.events(), refresh)); else notAllowed(ex, "GET"); }
            case "/api/status" -> { if (read) respond(ex, 200, "application/json", json(m.snapshot(), m.events())); else notAllowed(ex, "GET"); }
            case "/healthz" -> respond(ex, 200, "text/plain", "ok");
            case "/check" -> {
                if (!method.equals("POST")) { notAllowed(ex, "POST"); return; }
                m.check();
                ex.getResponseHeaders().set("Location", "/");
                respond(ex, 303, "text/plain", "");
            }
            default -> respond(ex, 404, "text/plain", "Not found");
        }
    }

    private static void notAllowed(HttpExchange ex, String allow) throws IOException {
        ex.getResponseHeaders().set("Allow", allow);
        respond(ex, 405, "text/plain", "Method not allowed");
    }

    private static void respond(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type + "; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'");
        if (ex.getRequestMethod().equals("HEAD") || code == 303) { ex.sendResponseHeaders(code, -1); return; }
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    static String json(Monitor.Snapshot s, List<Monitor.Event> events) {
        StringBuilder b = new StringBuilder("{");
        b.append("\"checkedAt\":").append(q(s.checkedAt() == null ? null : s.checkedAt().toString()));
        b.append(",\"error\":").append(q(s.error()));
        b.append(",\"needsAttention\":").append(s.needsHuman());
        b.append(",\"summary\":{");
        boolean first = true;
        for (State st : State.values()) { if (!first) b.append(','); first = false; b.append(q(st.name())).append(':').append(s.count(st)); }
        b.append("},\"runs\":[");
        for (int i = 0; i < s.runs().size(); i++) {
            RunAnalysis r = s.runs().get(i);
            if (i > 0) b.append(',');
            b.append("{\"runId\":").append(q(r.runId())).append(",\"tenant\":").append(q(r.tenantName()))
                    .append(",\"report\":").append(q(r.reportName())).append(",\"scheduledAt\":").append(q(r.scheduledAt()))
                    .append(",\"state\":").append(q(r.state().name())).append(",\"attempts\":").append(r.attemptsUsed())
                    .append(",\"lastStatus\":").append(q(r.lastStatus())).append(",\"reason\":").append(q(r.reason()))
                    .append(",\"rootCauseHint\":").append(q(r.rootCauseHint())).append('}');
        }
        b.append("],\"events\":[");
        for (int i = 0; i < events.size(); i++) {
            Monitor.Event e = events.get(i);
            if (i > 0) b.append(',');
            b.append("{\"at\":").append(q(e.at().toString())).append(",\"kind\":").append(q(e.kind())).append(",\"message\":").append(q(e.message())).append('}');
        }
        return b.append("]}").toString();
    }

    static String html(Monitor.Snapshot s, List<Monitor.Event> events, int refresh) {
        String level, banner;
        if (s.checkedAt() == null) { level = "amber"; banner = "Waiting for first check..."; }
        else if (s.error() != null) { level = "red"; banner = "Monitor cannot read the job log - failures are NOT being detected"; }
        else if (s.needsHuman()) { level = "red"; banner = (s.count(State.ESCALATE) + s.count(State.NEEDS_REVIEW)) + " run(s) need human attention"; }
        else if (s.count(State.RETRY_ELIGIBLE) > 0) { level = "amber"; banner = s.count(State.RETRY_ELIGIBLE) + " run(s) awaiting automatic retry"; }
        else { level = "green"; banner = "All report runs delivered"; }

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<meta http-equiv=\"refresh\" content=\"").append(refresh).append("\"><title>Report Delivery Monitor</title><style>")
                .append("body{font:14px system-ui,sans-serif;margin:0;background:#f5f6f8;color:#1c2330}main{max-width:1100px;margin:0 auto;padding:16px}")
                .append("h1{font-size:20px;margin:8px 0}h2{font-size:15px;margin:24px 0 8px}")
                .append(".banner{padding:14px 16px;border-radius:8px;color:#fff;font-weight:600}.red{background:#c62828}.amber{background:#b26a00}.green{background:#2e7d32}")
                .append(".cards{display:flex;gap:8px;flex-wrap:wrap;margin:12px 0}.card{background:#fff;border-radius:8px;padding:10px 14px;min-width:110px;box-shadow:0 1px 2px #0002}")
                .append(".card b{display:block;font-size:22px}table{width:100%;border-collapse:collapse;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 1px 2px #0002}")
                .append("th,td{text-align:left;padding:7px 10px;border-bottom:1px solid #e6e8ec;vertical-align:top}th{background:#eceff3;font-size:12px}")
                .append(".b{padding:2px 8px;border-radius:10px;color:#fff;font-size:12px;white-space:nowrap}.s-ESCALATE,.s-NEEDS_REVIEW{background:#c62828}.s-RETRY_ELIGIBLE,.s-IN_FLIGHT{background:#b26a00}.s-RECOVERED{background:#1565c0}.s-SUCCEEDED{background:#2e7d32}")
                .append(".muted{color:#667}.hint{color:#8a4b00}button{padding:6px 12px;border:0;border-radius:6px;background:#1c2330;color:#fff;cursor:pointer}")
                .append("</style></head><body><main><h1>Report Delivery Monitor</h1>");
        h.append("<div class=\"banner ").append(level).append("\">").append(esc(banner)).append("</div>");
        h.append("<p class=\"muted\">Last check: ").append(s.checkedAt() == null ? "never" : esc(s.checkedAt().toString()))
                .append(" &middot; ").append(s.attempts()).append(" attempts &middot; auto-refresh ").append(refresh).append("s ")
                .append("<form method=\"post\" action=\"/check\" style=\"display:inline\"><button>Check now</button></form></p>");
        if (s.error() != null) h.append("<p class=\"hint\">Error: ").append(esc(s.error())).append("</p>");

        h.append("<div class=\"cards\">");
        for (State st : State.values()) h.append("<div class=\"card\"><span class=\"muted\">").append(st).append("</span><b>").append(s.count(st)).append("</b></div>");
        h.append("</div>");

        List<RunAnalysis> sorted = new ArrayList<>(s.runs());
        sorted.sort(Comparator.comparing((RunAnalysis r) -> severity(r.state())).thenComparing(RunAnalysis::runId));
        h.append("<h2>Runs (problems first)</h2><table><tr><th>Run</th><th>Tenant</th><th>Report / scheduled</th><th>State</th><th>Attempts</th><th>Detail</th></tr>");
        for (RunAnalysis r : sorted) {
            h.append("<tr><td>").append(esc(r.runId())).append("</td><td>").append(esc(r.tenantName())).append("</td><td>")
                    .append(esc(r.reportName())).append("<br><span class=\"muted\">").append(esc(r.scheduledAt())).append("</span></td>")
                    .append("<td><span class=\"b s-").append(r.state()).append("\">").append(r.state()).append("</span></td><td>")
                    .append(esc(Monitor.timeline(r.attempts()))).append("</td><td>").append(esc(r.reason()));
            if (!r.rootCauseHint().isEmpty()) h.append("<br><span class=\"hint\">").append(esc(r.rootCauseHint())).append("</span>");
            h.append("</td></tr>");
        }
        if (sorted.isEmpty()) h.append("<tr><td colspan=\"6\" class=\"muted\">No runs loaded yet</td></tr>");
        h.append("</table><h2>Recent activity</h2><table><tr><th>Time (UTC)</th><th>Kind</th><th>Message</th></tr>");
        for (Monitor.Event e : events) h.append("<tr><td>").append(esc(e.at().toString())).append("</td><td>").append(esc(e.kind())).append("</td><td>").append(esc(e.message())).append("</td></tr>");
        return h.append("</table></main></body></html>").toString();
    }

    private static int severity(State s) {
        return switch (s) { case ESCALATE -> 0; case NEEDS_REVIEW -> 1; case RETRY_ELIGIBLE -> 2; case IN_FLIGHT -> 3; case RECOVERED -> 4; case SUCCEEDED -> 5; };
    }
}
