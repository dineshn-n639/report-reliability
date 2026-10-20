# Report Delivery Reliability (Meridian Payments case study)

Reads the job-runner history, works out which scheduled report runs **never actually succeeded**, explains why,
and proposes (dry-run) retries only for runs where a retry is safe and within budget.

## Run
Prerequisite: Java 17+ (zero third-party dependencies).

```sh
./run.sh                      # compile, run tests, run demo on data/report_jobs.json
./run.sh data/other.json --max-attempts 4
```
Manual: `javac --release 17 -d out $(find src -name '*.java')`, then
`java -cp out com.meridian.reliability.SelfTest` and `java -cp out com.meridian.reliability.Main [jobs.json] [--max-attempts N]`.
A `pom.xml` is included (`mvn compile exec:java`), but see limitations.

## Dashboard, periodic checks and email alerts (same app)
```sh
java -cp out com.meridian.reliability.Main --serve data/report_jobs.json --port 8080 --interval 60
# open http://127.0.0.1:8080/   (JSON: /api/status, health: /healthz, POST /check = check now)
```
- Re-reads the job log every `--interval` seconds (min 5), so new attempts appended to the file show up on the next cycle.
- Page shows a red/amber/green banner, counts per state, every run (problems first, with attempt timeline and likely cause) and recent activity.
- **Email**: configured only via environment variables (secrets never on the command line):
  `SMTP_HOST`, `ALERT_TO` (comma-separated) are required; optional `SMTP_PORT` (587), `SMTP_SECURITY` (`starttls` default | `ssl` | `none`),
  `SMTP_USER`, `SMTP_PASS`, `ALERT_FROM`. Without them, alerts print to the console.
- One digest email per cycle covering **new** ESCALATE / NEEDS_REVIEW runs. De-duplicated per (run, state, attempts, last status) and persisted in
  `--state-file` (default `alerted.state`), so restarts don't re-send. A failed send is retried next cycle. If the log itself becomes unreadable, you get one email about that too.
- Credentials are refused over plaintext (`none` + user/pass is rejected).
- The dashboard has **no authentication**; it binds to 127.0.0.1 by default. Use `--host 0.0.0.0` only behind a reverse proxy/VPN with auth.

## Expected output on the seed data
12 runs: 9 succeeded, 1 recovered (R-003), 1 retry-eligible (R-010, status `error`), 1 escalated (R-005, Gamma Health).

## Layout
- `StatusClassifier` – maps raw status strings to SUCCESS / FAILURE / IN_FLIGHT / UNKNOWN (unknown is never success)
- `RunAnalyzer` / `RunAnalysis` – groups attempts per `run_id`, produces a verdict + rule-based root-cause hint
- `RetryPolicy`, `RetryExecutor` – policy knobs and the seam to the real runner (v1: dry-run only)
- `Monitor` – one check cycle: analyse, dry-run retries, de-duplicated alerts, activity log
- `Dashboard` / `DashboardApp` – HTTP dashboard (JDK `HttpServer`) and the `--serve` runner with scheduler
- `SmtpNotifier` / `Notifier` – dependency-free SMTP client (STARTTLS/SSL/AUTH LOGIN) and console fallback
- `Main` – CLI report; `SelfTest` (21 checks, incl. end-to-end on the dataset) and `DashboardTest` (27 checks: de-duplication, SMTP against a fake server, HTTP endpoints, XSS escaping)

## Known limitations
- SMTP was tested against a local fake server only, **not a real mail provider or TLS handshake**; try it with a test mailbox first. Providers needing OAuth2 (e.g. modern Microsoft 365/Gmail without app passwords) won't work with AUTH LOGIN.
- No "resolved" email when a failed run later recovers; no escalation reminders or on-call routing.
- The dashboard is read-only, unauthenticated, single-process; alert state lives in a local file.
- No real runner API was provided, so retries are **dry-run**; the idempotency key (`run_id:attempt-N`) is computed but not sent anywhere.
- Single-shot batch over a file; no scheduler, persistence, or alerting integration.
- Cannot detect runs that were **never attempted** (no schedule/expected-runs source); only runs present in the log.
- Status vocabulary is inferred from this dataset plus common synonyms; confirm with Meridian.
- Root-cause hints are heuristics on duration/status only (no log text available).
- Java was built with the JDK 21 compiler in `-source 17` mode; JUnit/Spring/Maven Central were not reachable in my build environment, so
  tests use a tiny built-in runner and **the pom.xml has not been exercised with Maven**.
- Spring Boot/DB/REST were deliberately not used: the task is a batch analysis over a file; a web stack would add weight with no requirement behind it.
