# Solution Design: Report Delivery Reliability

## Problem, in my words
Scheduled statements occasionally fail to reach tenants and Meridian learns of it from the customer. The job runner already logs every
attempt, so the need is a proactive watcher that (1) decides per *logical run* whether the report was ever delivered, (2) retries what is
safe to retry, and (3) pages a human for the rest, ideally before the tenant notices.

## Key finding in the data and starter kit
The starter `FAILURE_STATUSES = {failed, timed_out}` omits **`error`**. Run R-010 (Epsilon Media, Sep 8) ends in `error` and would be silently
treated as healthy. The starter also lists exhausted runs (R-005) alongside retryable ones with no distinction, and its docstring
("exhausted or still within budget") hides that. So: a whitelist of failure statuses is the wrong default; I classify known success, failure,
and in-flight statuses explicitly and treat anything **unrecognised as needs-review, never as success**.

## Assumptions (please challenge)
1. A run = one `run_id`; attempts share it and are ordered by `attempt_number`.
2. A run is delivered if **any** attempt reached `completed` (robust to out-of-order or late-arriving rows).
3. Failure statuses: `failed`, `timed_out`, `error` (+ obvious synonyms: errored, cancelled, aborted). `pending/queued/running` = in flight, left alone.
4. Retrying a statement generation is idempotent or de-duplicated downstream. **Unverified**; the idempotency key (`run_id:attempt-N`) is designed for it but the runner must honour it.
5. `completed` with a plausible row count is real success. I did not flag zero/null rows (none occur in the seed data) but would in production.
6. Every expected run appears in the log (no source of expected schedules was given).

## Approach
`log -> StatusClassifier -> group by run -> RunAnalyzer verdict -> report + (dry-run) retry / escalation`.
Verdicts: SUCCEEDED, RECOVERED (self-healed, no action), RETRY_ELIGIBLE, ESCALATE, IN_FLIGHT, NEEDS_REVIEW.

**Retry policy (defaults, all configurable):**
- Max **3 total attempts**: covers transient blips (a deploy, a brief DB stall) without hammering a broken dependency or delaying a human for long.
- **2 consecutive timeouts => escalate early.** Timeouts usually mean load or data volume; retrying the same job tends to repeat the failure and add load.
- Retry only when the latest attempt is a known failure, the run never succeeded, and budget remains.

**Seed data result:** R-003 recovered on its own; R-010 is retry-eligible (fast `error`, 6s, likely transient); R-005 (Gamma Health: two timeouts then a 14s failure)
is escalated with the hint "resource pressure followed by deterministic error", the one customer-impacting case that needs a person.

## Monitoring layer (v1.1)
A scheduler calls `Monitor.check()` every N seconds; the same analyser feeds a dashboard (`/`, `/api/status`) and email. Alert on states that need a human
(ESCALATE, NEEDS_REVIEW) and on the monitor's own blindness (unreadable log), **not** on RETRY_ELIGIBLE, which the system handles. Alerts are keyed by
(run, state, attempts, last status) so a persistent failure alerts once but a worsening one alerts again; a failed send is not recorded as sent. Dashboard output is
HTML-escaped with a restrictive CSP; SMTP rejects header/recipient injection and refuses credentials over plaintext.

## Trade-offs considered
- **Rules vs. LLM for root cause:** rules are deterministic, free, testable, and enough for duration/status signals. An LLM adds value only with real log text/stack traces.
- **Zero dependencies vs. Spring Boot:** a batch analyser needs no web server or DB; fewer moving parts, trivially runnable by a reviewer. Spring would be justified once this is a service.
- **Dry-run default:** no runner API exists, and an automatic retry of a financial statement is a side-effecting action; safe-by-default beats a fabricated integration.
- **"Any success" vs. "latest attempt":** fewer false alarms, at the cost of ignoring a later failure after success (acceptable for delivery questions; revisit if reruns can overwrite output).

## Scaling to many tenants and reports
- Move from file to a query on the job table/stream; the analyser is already per-run and stateless, so it shards by tenant trivially.
- Run as a scheduled sweep (every 5-10 min) or event-driven on failure records; persist retry decisions to dedupe sweeps (idempotency key as unique constraint).
- Add per-tenant fairness/rate limits and a global retry circuit breaker (if many tenants fail at once, it is an incident, not a retry storm).
- Per-report SLAs (deadline-based alerting) rather than a single policy; backoff between retries.

## With more time / data
1. Expected-schedule source to detect runs that were never started (the real "silent miss").
2. Confirm retry idempotency and status vocabulary with Sam's team; wire a real `RetryExecutor` and alert sink (Slack/PagerDuty).
3. Log text and error codes to separate transient vs. permanent failures; then the optional LLM triage becomes worthwhile.
4. Sanity checks on "successful" runs (row count anomalies vs. prior period).
5. Metrics: time-to-detect, auto-recovery rate, escalation volume.
