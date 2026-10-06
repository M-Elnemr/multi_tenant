package com.platform.medical;

import com.platform.core.onboarding.TenantProvisioner;
import com.platform.core.tenant.Tenant;
import com.platform.core.tenant.TenantType;
import com.platform.core.user.StaffInvitedEvent;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** A new clinic is bookable immediately: profile, main branch, owner's doctor profile, services and a default weekly schedule. */
@Component
public class ClinicProvisioner implements TenantProvisioner {
    private final JdbcClient jdbc;

    public ClinicProvisioner(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public TenantType supports() { return TenantType.CLINIC; }

    @Override
    public void provision(Tenant t, UUID ownerUserId) {
        jdbc.sql("INSERT INTO medical.clinic_profiles (tenant_id, clinic_name) VALUES (:t, :n)").param("t", t.getId()).param("n", t.getName()).update();
        UUID branch = jdbc.sql("INSERT INTO medical.clinic_branches (tenant_id, name, code) VALUES (:t, 'Main', 'MAIN') RETURNING id").param("t", t.getId()).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO medical.appointment_services (tenant_id, name, duration_minutes) VALUES (:t, 'Consultation', 30), (:t, 'Follow-up', 15)
                """).param("t", t.getId()).update();
        UUID doctor = createDoctor(t.getId(), ownerUserId);
        // Sunday-Thursday 10:00-16:00 (ISO weekdays 7,1,2,3,4); the doctor edits this in the dashboard.
        for (int weekday : new int[] {7, 1, 2, 3, 4})
            jdbc.sql("INSERT INTO medical.doctor_schedules (tenant_id, doctor_id, branch_id, weekday, start_local_time, end_local_time, slot_duration_minutes) VALUES (:t, :d, :b, :w, '10:00', '16:00', 30)")
                    .param("t", t.getId()).param("d", doctor).param("b", branch).param("w", weekday).update();
    }

    /** Doctors added later through the staff flow get a profile too (no schedule until they set one). */
    @EventListener
    public void onStaffInvited(StaffInvitedEvent e) {
        if ("DOCTOR".equals(e.role()) && isClinic(e.tenantId())) createDoctor(e.tenantId(), e.userId());
    }

    private boolean isClinic(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM core.tenants WHERE id = :t AND tenant_type = 'CLINIC'").param("t", tenantId).query(Long.class).single() > 0;
    }

    UUID createDoctor(UUID tenantId, UUID userId) {
        return jdbc.sql("""
                INSERT INTO medical.doctors (tenant_id, user_id, display_name)
                SELECT :t, u.id, trim(both ' ' from 'Dr. ' || u.first_name || ' ' || u.last_name) FROM core.users u WHERE u.id = :u
                ON CONFLICT (tenant_id, user_id) DO UPDATE SET is_active = TRUE RETURNING id
                """).param("t", tenantId).param("u", userId).query(UUID.class).single();
    }
}
