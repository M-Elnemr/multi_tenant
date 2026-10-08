package com.platform.medical;

import com.platform.shared.BusinessException;
import java.sql.Time;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Computes bookable start times from weekly schedules, exceptions, existing appointments and booking rules, in the clinic's timezone. */
@Service
public class SlotService {
    static final String LIVE = "('REQUESTED','PENDING_CONFIRMATION','CONFIRMED','CHECKED_IN','IN_PROGRESS')";

    private final JdbcClient jdbc;

    public SlotService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public ZoneId zone(UUID tenantId) {
        return ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tenantId).query(String.class).single());
    }

    /** enforceRules=true applies minimum notice / maximum days ahead (patient bookings); staff may book closer in. */
    /** ISO weekdays (1 = Monday ... 7 = Sunday) on which this doctor has active working hours at this branch, so a booking page can grey out days off. */
    public List<Integer> workingWeekdays(UUID tenantId, UUID doctorId, UUID branchId) {
        return jdbc.sql("""
                SELECT DISTINCT weekday FROM medical.doctor_schedules
                WHERE tenant_id = :t AND doctor_id = :d AND branch_id = :b AND is_active
                  AND (effective_to IS NULL OR effective_to >= current_date) ORDER BY weekday
                """).param("t", tenantId).param("d", doctorId).param("b", branchId).query(Integer.class).list();
    }

    public List<Instant> slots(UUID tenantId, UUID doctorId, UUID branchId, UUID serviceId, LocalDate date, boolean enforceRules) {
        int duration = jdbc.sql("SELECT duration_minutes FROM medical.appointment_services WHERE id = :s AND tenant_id = :t AND is_active").param("s", serviceId).param("t", tenantId).query(Integer.class).optional()
                .orElseThrow(() -> BusinessException.notFound("RESOURCE_NOT_FOUND", "Service not found"));
        if (jdbc.sql("SELECT count(*) FROM medical.doctors WHERE id = :d AND tenant_id = :t AND is_active").param("d", doctorId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Doctor not found");
        if (jdbc.sql("SELECT count(*) FROM medical.clinic_branches WHERE id = :b AND tenant_id = :t AND is_active").param("b", branchId).param("t", tenantId).query(Long.class).single() == 0)
            throw BusinessException.notFound("RESOURCE_NOT_FOUND", "Branch not found");

        ZoneId tz = zone(tenantId);
        var profile = jdbc.sql("SELECT minimum_booking_notice_minutes, maximum_days_ahead FROM medical.clinic_profiles WHERE tenant_id = :t").param("t", tenantId).query().singleRow();
        int weekday = date.getDayOfWeek().getValue();
        var schedules = jdbc.sql("""
                SELECT start_local_time, end_local_time, slot_duration_minutes, buffer_minutes FROM medical.doctor_schedules
                WHERE tenant_id = :t AND doctor_id = :d AND branch_id = :b AND weekday = :w AND is_active
                  AND (effective_from IS NULL OR effective_from <= :dt) AND (effective_to IS NULL OR effective_to >= :dt)
                """).param("t", tenantId).param("d", doctorId).param("b", branchId).param("w", weekday).param("dt", java.sql.Date.valueOf(date)).query().listOfRows();
        if (schedules.isEmpty()) return List.of();

        var exceptions = jdbc.sql("SELECT type, start_local_time, end_local_time FROM medical.schedule_exceptions WHERE tenant_id = :t AND doctor_id = :d AND exception_date = :dt AND (branch_id IS NULL OR branch_id = :b)")
                .param("t", tenantId).param("d", doctorId).param("dt", java.sql.Date.valueOf(date)).param("b", branchId).query().listOfRows();
        List<LocalTime[]> windows = new ArrayList<>();   // [start, end, step minutes]
        boolean custom = false;
        for (var e : exceptions) {
            String type = (String) e.get("type");
            if (!"CUSTOM_HOURS".equals(type)) return List.of();   // day off / holiday / fully booked
            custom = true;
        }
        int slotMin = ((Number) schedules.get(0).get("slot_duration_minutes")).intValue();
        int bufMin = ((Number) schedules.get(0).get("buffer_minutes")).intValue();
        if (custom) {
            for (var e : exceptions) windows.add(new LocalTime[] {((Time) e.get("start_local_time")).toLocalTime(), ((Time) e.get("end_local_time")).toLocalTime()});
        } else {
            for (var s : schedules) windows.add(new LocalTime[] {((Time) s.get("start_local_time")).toLocalTime(), ((Time) s.get("end_local_time")).toLocalTime()});
        }

        Instant dayStart = date.atStartOfDay(tz).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(tz).toInstant();
        var busy = jdbc.sql("SELECT start_at, end_at FROM medical.appointments WHERE tenant_id = :t AND doctor_id = :d AND status IN " + LIVE + " AND start_at < :de AND end_at > :ds")
                .param("t", tenantId).param("d", doctorId).param("de", java.sql.Timestamp.from(dayEnd)).param("ds", java.sql.Timestamp.from(dayStart)).query().listOfRows();

        Instant now = Instant.now();
        Instant earliest = enforceRules ? now.plus(Duration.ofMinutes(((Number) profile.get("minimum_booking_notice_minutes")).longValue())) : now;
        Instant latest = enforceRules ? now.plus(Duration.ofDays(((Number) profile.get("maximum_days_ahead")).longValue())) : Instant.MAX;

        TreeSet<Instant> out = new TreeSet<>();
        int step = slotMin + bufMin;
        for (LocalTime[] w : windows) {
            for (LocalTime t = w[0]; !t.plusMinutes(duration).isAfter(w[1]) && !t.plusMinutes(duration).isBefore(t); t = t.plusMinutes(step)) {
                Instant start = date.atTime(t).atZone(tz).toInstant();
                Instant end = start.plus(Duration.ofMinutes(duration));
                if (start.isBefore(earliest) || start.isAfter(latest)) continue;
                boolean clash = false;
                for (Map<String, Object> b : busy) {
                    Instant bs = ((java.sql.Timestamp) b.get("start_at")).toInstant();
                    Instant be = ((java.sql.Timestamp) b.get("end_at")).toInstant();
                    if (start.isBefore(be) && end.isAfter(bs)) { clash = true; break; }
                }
                if (!clash) out.add(start);
            }
        }
        return new ArrayList<>(out);
    }
}
