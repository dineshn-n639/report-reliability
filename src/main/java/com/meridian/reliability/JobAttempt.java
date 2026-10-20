package com.meridian.reliability;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** One row of the job-runner log: a single attempt of a logical report run. */
public record JobAttempt(String jobId, String runId, String tenantId, String tenantName, String reportName,
                         LocalDateTime scheduledAt, String status, int attemptNumber,
                         Integer durationSeconds, Integer rowCount) {

    @SuppressWarnings("unchecked")
    public static List<JobAttempt> loadAll(Path path) throws java.io.IOException {
        Object root = Json.parse(Files.readString(path));
        Object jobs = ((Map<String, Object>) root).get("jobs");
        if (!(jobs instanceof List)) throw new IllegalArgumentException("Expected top-level \"jobs\" array");
        List<JobAttempt> out = new ArrayList<>();
        for (Object o : (List<Object>) jobs) out.add(fromMap((Map<String, Object>) o));
        return out;
    }

    static JobAttempt fromMap(Map<String, Object> m) {
        return new JobAttempt(req(m, "job_id"), req(m, "run_id"), req(m, "tenant_id"),
                (String) m.get("tenant_name"), (String) m.get("report_name"),
                LocalDateTime.parse(req(m, "scheduled_at")), req(m, "status"),
                ((Number) m.get("attempt_number")).intValue(),
                num(m.get("duration_seconds")), num(m.get("row_count")));
    }

    private static String req(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) throw new IllegalArgumentException("Missing required field: " + k + " in " + m);
        return v.toString();
    }
    private static Integer num(Object o) { return o == null ? null : ((Number) o).intValue(); }
}
