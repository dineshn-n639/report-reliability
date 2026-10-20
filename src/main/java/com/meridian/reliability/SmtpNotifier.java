package com.meridian.reliability;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** Minimal dependency-free SMTP client: plain, STARTTLS or implicit TLS, optional AUTH LOGIN. */
public final class SmtpNotifier implements Notifier {
    public record Config(String host, int port, String security, String user, String pass, String from, List<String> to) {}

    private static final Pattern ADDR = Pattern.compile("[^\\s<>\\r\\n,;]+@[^\\s<>\\r\\n,;]+");
    private final Config c;

    public SmtpNotifier(Config c) {
        if (!ADDR.matcher(c.from()).matches()) throw new IllegalArgumentException("Invalid ALERT_FROM");
        if (c.to().isEmpty()) throw new IllegalArgumentException("No ALERT_TO recipients");
        for (String t : c.to()) if (!ADDR.matcher(t).matches()) throw new IllegalArgumentException("Invalid recipient: " + t);
        this.c = c;
    }

    /** Returns null when SMTP_HOST or ALERT_TO is missing. Secrets come from the environment, never from CLI args. */
    public static Config fromEnv(Map<String, String> env) {
        String host = env.get("SMTP_HOST"), to = env.get("ALERT_TO");
        if (host == null || host.isBlank() || to == null || to.isBlank()) return null;
        List<String> rcpts = new ArrayList<>();
        for (String t : to.split(",")) if (!t.isBlank()) rcpts.add(t.trim());
        String user = env.get("SMTP_USER");
        String from = env.getOrDefault("ALERT_FROM", user != null ? user : "report-monitor@localhost");
        return new Config(host.trim(), Integer.parseInt(env.getOrDefault("SMTP_PORT", "587").trim()),
                env.getOrDefault("SMTP_SECURITY", "starttls").trim().toLowerCase(Locale.ROOT),
                user, env.get("SMTP_PASS"), from.trim(), rcpts);
    }

    private static final class Conn {
        Socket s; BufferedReader in; BufferedWriter out;
        Conn(Socket s) throws IOException { set(s); }
        void set(Socket s) throws IOException {
            this.s = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
        void line(String l) throws IOException { out.write(l + "\r\n"); out.flush(); }
        int reply() throws IOException {
            String l;
            while (true) {
                l = in.readLine();
                if (l == null) throw new IOException("SMTP server closed connection");
                if (l.length() < 4 || l.charAt(3) != '-') break;
            }
            try { return Integer.parseInt(l.substring(0, 3)); } catch (RuntimeException e) { throw new IOException("Bad SMTP reply: " + l); }
        }
        void expect(int code, String what) throws IOException {
            int r = reply();
            if (r != code) throw new IOException("SMTP " + what + " failed with code " + r);
        }
    }

    @Override public void send(String subject, String body) throws IOException {
        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(c.host(), c.port()), 10_000);
        plain.setSoTimeout(15_000);
        Socket sock = plain;
        if (c.security().equals("ssl")) sock = tls(plain);
        try (Socket toClose = sock) {
            Conn k = new Conn(sock);
            k.expect(220, "greeting");
            k.line("EHLO localhost"); k.expect(250, "EHLO");
            if (c.security().equals("starttls")) {
                k.line("STARTTLS"); k.expect(220, "STARTTLS");
                k.set(tls(k.s));
                k.line("EHLO localhost"); k.expect(250, "EHLO(TLS)");
            }
            if (c.user() != null && !c.user().isEmpty()) {
                if (!c.security().equals("starttls") && !c.security().equals("ssl"))
                    throw new IOException("Refusing to send credentials without TLS (set SMTP_SECURITY=starttls or ssl)");
                Base64.Encoder b = Base64.getEncoder();
                k.line("AUTH LOGIN"); k.expect(334, "AUTH");
                k.line(b.encodeToString(c.user().getBytes(StandardCharsets.UTF_8))); k.expect(334, "AUTH user");
                k.line(b.encodeToString((c.pass() == null ? "" : c.pass()).getBytes(StandardCharsets.UTF_8))); k.expect(235, "AUTH pass");
            }
            k.line("MAIL FROM:<" + c.from() + ">"); k.expect(250, "MAIL FROM");
            for (String t : c.to()) {
                k.line("RCPT TO:<" + t + ">");
                int r = k.reply();
                if (r != 250 && r != 251) throw new IOException("SMTP RCPT " + t + " rejected with code " + r);
            }
            k.line("DATA"); k.expect(354, "DATA");
            k.out.write(message(subject, body)); k.out.write("\r\n.\r\n"); k.out.flush();
            k.expect(250, "message body");
            k.line("QUIT");
        }
    }

    private SSLSocket tls(Socket s) throws IOException {
        SSLSocket t = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, c.host(), c.port(), true);
        t.startHandshake();
        return t;
    }

    /** Body is base64 so it needs no dot-stuffing or line-length handling; header values are stripped of CR/LF (no header injection). */
    String message(String subject, String body) {
        String subj = subject.replaceAll("[\\r\\n]+", " ").trim();
        boolean ascii = subj.chars().allMatch(ch -> ch < 128);
        if (!ascii) subj = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subj.getBytes(StandardCharsets.UTF_8)) + "?=";
        StringBuilder m = new StringBuilder();
        m.append("From: <").append(c.from()).append(">\r\n");
        m.append("To: ").append(String.join(", ", c.to())).append("\r\n");
        m.append("Subject: ").append(subj).append("\r\n");
        m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        m.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(body.getBytes(StandardCharsets.UTF_8)));
        return m.toString();
    }
}
