# Report Delivery Reliability: Enhancements and Panel Q&A Prep

Prep notes for the live session on the Meridian Payments FDE case study.

## Enhancements, in priority order

### Likely to impress the panel (cheap to add)

1. **Missing-run detection.** This is the biggest gap. The tool only sees runs that appear in the log, but the real "report just doesn't show up" case may be a run that never started. Add an expected-schedule input (tenant, report, cron or cadence), compare it to the log, and flag missing runs after a grace period.
2. **Real retry executor.** Add an HTTP client for the runner's API behind `RetryExecutor`, with a feature flag (dry-run by default) and a per-sweep retry cap.
3. **Backoff and a circuit breaker.** Space retries out (for example 5, 15 and 45 minutes). If more than N tenants fail within a window, stop retrying and raise one incident alert, because a mass failure points to a shared dependency, not individual reports.
4. **Per-report SLA or deadline.** Alert when a report hasn't succeeded by a delivery deadline (for example 06:00 local), not only after retries run out.
5. **"Resolved" email and reminders.** Send a resolved notice when an escalated run recovers, and re-notify if a run is still unresolved after X hours.

### Medium effort

- **Success sanity checks.** Flag a `completed` run with zero or null rows, or a row count far from the tenant's prior runs (for example, below 50% of the last run). Right now `completed` is trusted blindly.
- **Pluggable alert channels.** Slack or Teams webhooks and PagerDuty alongside email.
- **Persistence.** Move alert and retry state from a local file to a database table with a unique key on the idempotency key.
- **Dashboard auth** (or deployment behind SSO), plus metrics such as time-to-detect and auto-recovery rate.

### Bonus track: LLM root-cause

Keep the rule-based triage as the fallback and add an optional LLM step that gets only a redacted summary of the attempts and logs and returns a category plus a suggested action. Two cautions:

- The LLM must advise only and never decide on retries.
- It should be cached per run so it isn't called every cycle.

This only adds value once you have real log text, which the seed data lacks.

## Questions to expect, with answer outlines

### Ambiguity and assumptions

- **"How did you decide what counts as failed?"** The starter treated only `failed` and `timed_out` as failures and missed `error`. I classify known success, failure and in-flight statuses explicitly, and treat unknown statuses as needing review, never as success. I would confirm the full status list with Sam.
- **"Why 3 attempts? Why escalate after 2 timeouts?"** It's a judgement call: 3 covers transient blips without delaying a human, and repeated timeouts usually mean load or data volume, so retrying adds load. Be ready to say you'd tune it from real data (retry success rate by failure type).
- **"Is retrying safe?"** This is the big one. Be honest that it's unverified. A financial statement could be sent twice. I built idempotency keys, but the runner must honour them. A good answer also mentions that retries here regenerate the report, and delivery to the tenant needs separate de-duplication.

### Design judgement

- **"Why no Spring Boot or database?"** The need was a batch analysis over a file, and extra frameworks add weight with no requirement behind them. Name when you'd add them: when it becomes a service with multiple instances and durable state.
- **"Why 'any success' instead of the latest attempt?"** It gives fewer false alarms. The cost is that a failure after a success is ignored. Mention that you'd revisit this if reruns can overwrite output.
- **"Why is RETRY\_ELIGIBLE not emailed?"** The system handles it itself, so alerting would be noise. You only alert when a human is needed.

### Scale ("what if...")

- **"What if 10,000 tenants and 50 reports each?"** Query the job table instead of reading a file, shard the stateless analysis by tenant, persist alert and retry state with a unique key, and add per-tenant rate limits and a global retry cap.
- **"Two monitor instances run at once?"** Today that would double-send. The fix is a lease or lock, or a unique constraint on the idempotency key and alert key in a shared store.
- **"What if the runner is down?"** Retries fail and should back off, and the monitor should alert on its own blindness. The unreadable-log alert is a start, and a dead-man's switch (an external ping that expects a heartbeat) would be the next step.

### Failure modes and security

- **"What if the email fails?"** The alert isn't marked as sent, so it retries next cycle. Admit the gap: if email is down for hours, there is no second channel yet.
- **"How are secrets handled?"** SMTP credentials come from environment variables, never CLI arguments, and are refused over plaintext. The dashboard is localhost-only with no auth, so say that plainly and name the proper fix.
- **"What did you not test?"** Real SMTP and TLS, and the Maven build. Saying this unprompted builds credibility.

### Challenge-the-weakness questions

- **"Your root-cause hints are just heuristics."** Agree. They use only status and duration, and I labelled them as hints. With real logs I would use an LLM for classification, with the safeguards above.
- **"The seed data is tiny. How do you know the thresholds are right?"** You don't. Describe how you'd measure: retry success rate, time-to-recover, and false-escalation rate over a few weeks, then tune.

## Tips for the live session

- Lead with the `error` status finding, since it shows you found the trap in the starter code.
- Practise a 2-minute demo: run the dashboard, show R-005 escalating with an email, R-010 as a dry-run retry, and R-003 self-healing.
- For "what if" questions, restate the new constraint, say what breaks first, then give the smallest change that fixes it. That's what they're scoring.
- Where you don't know something (for example, runner idempotency), say what you'd ask Sam instead of bluffing.

## Possible next steps

- Draft a one-page cheat sheet of these answers.
- Add missing-run detection to the app before the session (the enhancement to pick first).
