package com.meridian.reliability;

import com.meridian.reliability.RunAnalysis.State;

import java.nio.file.Path;
import java.util.*;

public final class Main {
    public static void main(String[] args) throws Exception {
        if (Arrays.asList(args).contains("--serve")) { DashboardApp.run(args); return; }
        String path = "data/report_jobs.json";
        int max = RetryPolicy.defaults().maxAttempts();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--max-attempts")) max = Integer.parseInt(args[++i]);
            else if (args[i].equals("--help")) { System.out.println("Usage: Main [jobs.json] [--max-attempts N]  |  Main --serve [jobs.json] [--port 8080] [--host 127.0.0.1] [--interval 60] [--state-file alerted.state]"); return; }
            else path = args[i];
        }
        RetryPolicy def = RetryPolicy.defaults();
        RetryPolicy policy = new RetryPolicy(max, def.maxConsecutiveTimeouts(), def.fastFailSeconds());

        List<JobAttempt> jobs = JobAttempt.loadAll(Path.of(path));
        List<RunAnalysis> runs = new RunAnalyzer(policy).analyze(jobs);
        System.out.print(render(runs, jobs.size(), policy, new RetryExecutor.DryRun()));
    }

    static String render(List<RunAnalysis> runs, int attempts, RetryPolicy p, RetryExecutor exec) {
        StringBuilder sb = new StringBuilder();
        Map<State, Integer> counts = new EnumMap<>(State.class);
        for (RunAnalysis r : runs) counts.merge(r.state(), 1, Integer::sum);

        sb.append("REPORT DELIVERY RELIABILITY\n");
        sb.append(String.format("%d attempts -> %d logical runs | policy: max %d attempts, escalate after %d consecutive timeouts%n%n",
                attempts, runs.size(), p.maxAttempts(), p.maxConsecutiveTimeouts()));
        sb.append("Summary: ");
        for (State s : State.values()) if (counts.containsKey(s)) sb.append(s).append('=').append(counts.get(s)).append("  ");
        sb.append("\n\n");

        sb.append("RUNS THAT NEVER SUCCEEDED\n");
        boolean any = false;
        for (RunAnalysis r : runs) {
            if (!r.neverSucceeded()) continue;
            any = true;
            sb.append(String.format("  %s  %-15s %-17s sched %s  attempts=%d  last=%s%n", r.runId(), r.tenantName(), r.reportName(), r.scheduledAt(), r.attemptsUsed(), r.lastStatus()));
            sb.append("      state : ").append(r.state()).append(" - ").append(r.reason()).append('\n');
            if (!r.rootCauseHint().isEmpty()) sb.append("      why   : ").append(r.rootCauseHint()).append('\n');
            if (r.state() == State.RETRY_ELIGIBLE) sb.append("      action: ").append(exec.submit(r, RetryExecutor.keyFor(r))).append('\n');
            else if (r.state() == State.ESCALATE) sb.append("      action: ESCALATE to on-call (customer-impacting, auto-retry not appropriate)\n");
            else if (r.state() == State.NEEDS_REVIEW) sb.append("      action: human review of status vocabulary\n");
        }
        if (!any) sb.append("  (none)\n");

        sb.append("\nSELF-HEALED (failed earlier, later succeeded - no action)\n");
        boolean rec = false;
        for (RunAnalysis r : runs) if (r.state() == State.RECOVERED) {
            rec = true;
            sb.append(String.format("  %s  %-15s %s%n", r.runId(), r.tenantName(), r.reason()));
        }
        if (!rec) sb.append("  (none)\n");
        return sb.toString();
    }
}
