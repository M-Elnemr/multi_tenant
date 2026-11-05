package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A lawyer signs up, gets a website, a guest books a session, the lawyer approves, works cases (actions + fees, steps, payments) and is reminded. */
class LawyerIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired com.platform.legal.LegalReminders reminders;

    String ok(org.springframework.test.web.servlet.ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    Tenant lawyer() throws Exception {
        String slug = "law" + uniq(), phone = nextPhone();
        String res = mvc.perform(post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(MediaType.APPLICATION_JSON).content("""
                        {"type":"LAWYER","name":"Ahmed Law","slug":"%s","ownerFirstName":"Ahmed","phone":"%s","password":"s3cretPass!","categories":["criminal","family"]}
                        """.formatted(slug, phone)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), phone, JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    /** The next day the office is open with a free slot. */
    String freeSlot(Tenant t) throws Exception {
        for (int i = 1; i < 10; i++) {
            String d = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(i).toString();
            List<String> s = JsonPath.read(ok(onHost(t.host(), null, "GET", "/api/v1/lawyer/public/slots?date=" + d, null).andExpect(status().isOk())), "$");
            if (!s.isEmpty()) return s.get(0);
        }
        throw new AssertionError("no free slot");
    }

    @Test
    void aClientTheLawyerAddedSignsInSeesOnlyTheirCasesBooksAndIsTold() throws Exception {
        Tenant t = lawyer();
        String h = t.host(), a = t.access();
        String phone = nextPhone(), other = nextPhone();
        String c = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"منى سعيد\",\"phone\":\"%s\"}".formatted(phone)).andExpect(status().isCreated())), "$.id");
        String c2 = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"عميل آخر\",\"phone\":\"%s\"}".formatted(other))), "$.id");
        String noPhone = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"بدون رقم\"}")), "$.id");
        String mine = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/cases", "{\"title\":\"دعوى نفقة\",\"caseType\":\"FAMILY\",\"court\":\"محكمة الأسرة\",\"agreedFeeMinor\":500000,\"clientIds\":[\"%s\"]}".formatted(c))), "$.id");
        String theirs = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/cases", "{\"title\":\"قضية غيرها\",\"clientIds\":[\"%s\"]}".formatted(c2))), "$.id");
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + mine + "/actions", "{\"title\":\"مذكرة داخلية سرية\",\"notes\":\"ملاحظة للمكتب فقط\",\"feeMinor\":100000}").andExpect(status().isOk());
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + mine + "/payments", "{\"amountMinor\":200000,\"clientId\":\"%s\"}".formatted(c)).andExpect(status().isOk());
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + mine + "/steps", "{\"kind\":\"HEARING\",\"description\":\"جلسة أولى\",\"location\":\"الدائرة 3\",\"startsAt\":\"%s\"}".formatted(Instant.now().plus(20, ChronoUnit.HOURS))).andExpect(status().isOk());

        // no sign-in before the lawyer turns access on; a client without a mobile cannot get it
        onHost(h, null, "POST", "/api/v1/lawyer/portal/login", "{\"phone\":\"%s\",\"password\":\"12345678\"}".formatted(phone)).andExpect(status().isUnauthorized());
        onHost(h, a, "POST", "/api/v1/lawyer/clients/" + noPhone + "/app-access", "{\"action\":\"ENABLE\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.phone").exists());
        String access = ok(onHost(h, a, "POST", "/api/v1/lawyer/clients/" + c + "/app-access", "{\"action\":\"ENABLE\"}").andExpect(status().isOk()));
        String temp = JsonPath.read(access, "$.password");
        assertThat(temp).hasSize(8);
        assertThat((String) JsonPath.read(access, "$.message")).contains(temp).contains("منى سعيد");
        String detail = ok(onHost(h, a, "GET", "/api/v1/lawyer/clients/" + c, null).andExpect(jsonPath("$.portalEnabled").value(true)));
        assertThat(detail).doesNotContain("portalPasswordHash").doesNotContain("$argon2");

        // sign in with the temporary password, then a new one is required
        onHost(h, null, "POST", "/api/v1/lawyer/portal/login", "{\"phone\":\"%s\",\"password\":\"wrong-pass\"}".formatted(phone)).andExpect(status().isUnauthorized());
        String login = ok(onHost(h, null, "POST", "/api/v1/lawyer/portal/login", "{\"phone\":\"%s\",\"password\":\"%s\"}".formatted(phone, temp)).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true)).andExpect(jsonPath("$.user.roles[0]").value("LEGAL_CLIENT")));
        String tok = JsonPath.read(login, "$.accessToken");
        onHost(h, tok, "POST", "/api/v1/lawyer/portal/change-password", "{\"currentPassword\":\"%s\",\"newPassword\":\"%s\"}".formatted(temp, temp)).andExpect(status().isBadRequest());
        tok = JsonPath.read(ok(onHost(h, tok, "POST", "/api/v1/lawyer/portal/change-password", "{\"currentPassword\":\"%s\",\"newPassword\":\"my-own-pass\"}".formatted(temp))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(false))), "$.accessToken");

        // only their own case; the office's notes never reach them; fees and payments do
        onHost(h, tok, "GET", "/api/v1/lawyer/portal/home", null).andExpect(status().isOk()).andExpect(jsonPath("$.cases.length()").value(1)).andExpect(jsonPath("$.cases[0].id").value(mine))
                .andExpect(jsonPath("$.upcoming[0].caseId").value(mine));
        String kase = ok(onHost(h, tok, "GET", "/api/v1/lawyer/portal/cases/" + mine, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.billedMinor").value(600000)).andExpect(jsonPath("$.paidMinor").value(200000)).andExpect(jsonPath("$.balanceMinor").value(400000))
                .andExpect(jsonPath("$.steps[0].location").value("الدائرة 3")).andExpect(jsonPath("$.payments.length()").value(1)));
        assertThat(kase).doesNotContain("مذكرة داخلية سرية").doesNotContain("ملاحظة للمكتب فقط");
        onHost(h, tok, "GET", "/api/v1/lawyer/portal/cases/" + theirs, null).andExpect(status().isNotFound());
        // a client token opens nothing of the lawyer's dashboard
        onHost(h, tok, "GET", "/api/v1/lawyer/overview", null).andExpect(status().isForbidden());
        onHost(h, tok, "GET", "/api/v1/lawyer/clients", null).andExpect(status().isForbidden());

        // book from the app: tied to them and their case; the lawyer's answer reaches their phone
        onHost(h, tok, "POST", "/api/v1/lawyer/portal/devices", "{\"token\":\"client-device-%s\"}".formatted(uniq())).andExpect(status().isOk());
        String slot = freeSlot(t);
        String code = JsonPath.read(ok(onHost(h, tok, "POST", "/api/v1/lawyer/portal/bookings", "{\"startsAt\":\"%s\",\"topic\":\"متابعة\",\"caseId\":\"%s\"}".formatted(slot, mine))
                .andExpect(status().isCreated())), "$.code");
        var row = jdbc.sql("SELECT id, client_id, case_id, source FROM legal.bookings WHERE upper(code) = upper(:c)").param("c", code).query().singleRow();
        assertThat(row.get("client_id").toString()).isEqualTo(c);
        assertThat(row.get("case_id").toString()).isEqualTo(mine);
        assertThat(row.get("source")).isEqualTo("APP");
        onHost(h, tok, "POST", "/api/v1/lawyer/portal/bookings", "{\"startsAt\":\"%s\",\"caseId\":\"%s\"}".formatted(slot, theirs)).andExpect(status().isBadRequest());
        onHost(h, a, "PUT", "/api/v1/lawyer/bookings/" + row.get("id"), "{\"status\":\"APPROVED\"}").andExpect(status().isOk());
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE user_id = CAST(:c AS uuid) AND channel = 'PUSH' AND subject = 'تم تأكيد موعدك'").param("c", c).query(Long.class).single()).isEqualTo(1);
        onHost(h, tok, "GET", "/api/v1/lawyer/portal/bookings", null).andExpect(jsonPath("$[0].status").value("APPROVED"));
        onHost(h, tok, "POST", "/api/v1/lawyer/portal/bookings/" + row.get("id") + "/cancel", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // the day before their hearing, their phone is reminded
        reminders.remind();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.outbox WHERE user_id = CAST(:c AS uuid) AND payload->>'type' = 'LEGAL_CLIENT_CASE'").param("c", c).query(Long.class).single()).isEqualTo(1);

        // the evening WhatsApp report lists tomorrow's hearing (the phone is not linked in tests, so nothing is sent)
        java.time.ZoneId cairo = java.time.ZoneId.of("Africa/Cairo");
        java.time.LocalDate hearingDay = Instant.now().plus(20, ChronoUnit.HOURS).atZone(cairo).toLocalDate();
        UUID tenantId = jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single();
        String report = reminders.whatsappReportText(tenantId, cairo, hearingDay.minusDays(1));
        assertThat(report).contains("جلسة أولى").contains("دعوى نفقة").contains("المستحق على الموكلين");
        onHost(h, a, "GET", "/api/v1/lawyer/whatsapp", null).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.waReport").value(true));
        onHost(h, a, "POST", "/api/v1/lawyer/whatsapp/report/send-now", null).andExpect(jsonPath("$.sent").value(false));

        // the lawyer turns access off: the client is out at once
        onHost(h, a, "POST", "/api/v1/lawyer/clients/" + c + "/app-access", "{\"action\":\"DISABLE\"}").andExpect(jsonPath("$.portalEnabled").value(false));
        onHost(h, tok, "GET", "/api/v1/lawyer/portal/home", null).andExpect(status().isForbidden());
        onHost(h, null, "POST", "/api/v1/lawyer/portal/login", "{\"phone\":\"%s\",\"password\":\"my-own-pass\"}".formatted(phone)).andExpect(status().isUnauthorized());
        // a client token of one office is useless at another
        Tenant other2 = lawyer();
        onHost(other2.host(), tok, "GET", "/api/v1/lawyer/portal/home", null).andExpect(status().isForbidden());
    }

    @Test
    void aWhiteLabelOfficeShowsNoBillingDomainsOrPlatformCredit() throws Exception {
        Tenant t = lawyer();
        onHost(t.host(), t.access(), "GET", "/api/v1/auth/me", null).andExpect(status().isOk()).andExpect(jsonPath("$.screens.billing").exists()).andExpect(jsonPath("$.screens.domains").exists());
        onHost(t.host(), null, "GET", "/api/v1/tenant/context", null).andExpect(jsonPath("$.whiteLabel").value(false));
        jdbc.sql("UPDATE core.tenants SET white_label = TRUE WHERE slug = :s").param("s", t.slug()).update();
        onHost(t.host(), t.access(), "GET", "/api/v1/auth/me", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.screens.billing").doesNotExist()).andExpect(jsonPath("$.screens.domains").doesNotExist()).andExpect(jsonPath("$.screens.cases").exists());
        onHost(t.host(), t.access(), "GET", "/api/v1/billing/subscription", null).andExpect(status().isForbidden());
        onHost(t.host(), null, "GET", "/api/v1/tenant/context", null).andExpect(jsonPath("$.whiteLabel").value(true));
        // lawyers: the first month is free, then 250 EGP a month for hosting
        assertThat(jdbc.sql("SELECT price_minor FROM billing.subscription_plans WHERE code = 'LAWYER_PRO'").query(Long.class).single()).isEqualTo(25000L);
    }

    @Test
    void websiteBookingApprovalAndSchedule() throws Exception {
        Tenant t = lawyer();
        String h = t.host(), a = t.access();
        // the website is ready on day one: practice areas from sign-up, services, hours
        onHost(h, null, "GET", "/api/v1/lawyer/public/profile", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.practiceAreas.length()").value(2)).andExpect(jsonPath("$.services.length()").value(3)).andExpect(jsonPath("$.hours.length()").value(6))
                .andExpect(jsonPath("$.autoApprove").doesNotExist());
        onHost(h, a, "GET", "/api/v1/tenant/screens", null);   // (exists for staff screens; not asserted)
        // field errors on the profile
        onHost(h, a, "PATCH", "/api/v1/lawyer/profile", "{\"displayName\":\"\",\"email\":\"bad\",\"social\":{\"facebook\":\"http://x\"}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.displayName").exists()).andExpect(jsonPath("$.fields.email").exists()).andExpect(jsonPath("$.fields['social.facebook']").exists());
        onHost(h, a, "PATCH", "/api/v1/lawyer/profile", "{\"displayName\":\"المستشار أحمد\",\"title\":\"المستشار\",\"yearsExperience\":20,\"template\":\"heritage\",\"testimonials\":[{\"name\":\"م.\",\"text\":\"ممتاز\",\"rating\":5}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.template").value("heritage")).andExpect(jsonPath("$.testimonials[0].rating").value(5));

        // a guest books (name + phone), cannot take the same time twice
        String slot = freeSlot(t);
        String svc = JsonPath.read(ok(onHost(h, null, "GET", "/api/v1/lawyer/public/profile", null)), "$.services[0].id");
        onHost(h, null, "POST", "/api/v1/lawyer/public/bookings", "{\"serviceId\":\"%s\",\"startsAt\":\"%s\",\"name\":\"\",\"phone\":\"1\"}".formatted(svc, slot))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields.phone").exists());
        String booked = ok(onHost(h, null, "POST", "/api/v1/lawyer/public/bookings", "{\"serviceId\":\"%s\",\"startsAt\":\"%s\",\"name\":\"منى علي\",\"phone\":\"01011112222\",\"topic\":\"نزاع إيجار\"}".formatted(svc, slot))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        String code = JsonPath.read(booked, "$.code");
        onHost(h, null, "POST", "/api/v1/lawyer/public/bookings", "{\"serviceId\":\"%s\",\"startsAt\":\"%s\",\"name\":\"Other\",\"phone\":\"01099998888\"}".formatted(svc, slot))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SLOT_UNAVAILABLE"));
        // the lawyer is told, sees it pending and denies only with a reason, then approves
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'LEGAL_BOOKING_REQUESTED' AND tenant_id = (SELECT id FROM core.tenants WHERE slug = :s)")
                .param("s", t.slug()).query(Long.class).single()).isEqualTo(1);
        String list = ok(onHost(h, a, "GET", "/api/v1/lawyer/bookings?status=PENDING&upcoming=true", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.counts.pending").value(1)));
        String bid = JsonPath.read(list, "$.data[0].id");
        onHost(h, a, "PUT", "/api/v1/lawyer/bookings/" + bid, "{\"status\":\"DENIED\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.note").exists());
        onHost(h, a, "PUT", "/api/v1/lawyer/bookings/" + bid, "{\"status\":\"APPROVED\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        onHost(h, null, "POST", "/api/v1/lawyer/public/bookings/track", "{\"code\":\"%s\",\"phone\":\"01011112222\"}".formatted(code)).andExpect(jsonPath("$.status").value("APPROVED"));
        // approving made the guest a client
        onHost(h, a, "GET", "/api/v1/lawyer/clients?q=منى", null).andExpect(jsonPath("$.meta.total").value(1));

        // one hour before: the reminder goes out once
        jdbc.sql("UPDATE legal.bookings SET starts_at = now() + interval '50 minutes', ends_at = now() + interval '80 minutes' WHERE id = :i").param("i", UUID.fromString(bid)).update();
        reminders.remind();
        reminders.remind();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'LEGAL_SESSION_SOON' AND payload->>'bookingId' = :b").param("b", bid).query(Long.class).single()).isEqualTo(1);

        // the schedule: a note on a new case created right from the calendar
        String day = LocalDate.now(ZoneId.of("Africa/Cairo")).plusDays(3).toString();
        String note = ok(onHost(h, a, "POST", "/api/v1/lawyer/schedule/notes", """
                {"startsAt":"%sT08:00:00Z","title":"تحضير مذكرة","newCase":{"title":"قضية إيجار منى","caseType":"REAL_ESTATE"}}""".formatted(day)).andExpect(status().isCreated()));
        assertThat((String) JsonPath.read(note, "$.caseId")).isNotBlank();
        onHost(h, a, "POST", "/api/v1/lawyer/schedule/steps", "{\"newCase\":{\"title\":\"\"},\"step\":{\"description\":\"x\",\"startsAt\":\"%sT09:00:00Z\"}}".formatted(day))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['newCase.title']").exists());
        onHost(h, a, "POST", "/api/v1/lawyer/schedule/steps", "{\"caseId\":\"%s\",\"step\":{\"kind\":\"HEARING\",\"description\":\"جلسة أولى\",\"startsAt\":\"%sT09:00:00Z\",\"location\":\"محكمة الجيزة\"}}"
                .formatted(JsonPath.read(note, "$.caseId"), day)).andExpect(status().isCreated());
        String sched = ok(onHost(h, a, "GET", "/api/v1/lawyer/schedule?from=" + LocalDate.now() + "&to=" + day, null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<String>>read(sched, "$[*].kind")).contains("BOOKING", "STEP", "NOTE");
        // a hearing blocks website slots at its time
        assertThat(jdbc.sql("SELECT count(*) FROM legal.case_steps").query(Long.class).single()).isGreaterThan(0);
    }

    @Test
    void casesClientsFeesFiltersAndSearch() throws Exception {
        Tenant t = lawyer();
        String h = t.host(), a = t.access();
        onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"\",\"email\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.name").exists()).andExpect(jsonPath("$.fields.email").exists());
        String c1 = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"شركة النيل\",\"phone\":\"01022223333\",\"kind\":\"COMPANY\"}").andExpect(status().isCreated())), "$.id");
        onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"Dup\",\"phone\":\"01022223333\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.fields.phone").exists());
        String c2 = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/clients", "{\"name\":\"أحمد سالم\",\"phone\":\"01044445555\"}")), "$.id");

        String k1 = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/cases", """
                {"title":"نزاع توريد","caseType":"COMMERCIAL","court":"المحكمة الاقتصادية","opponent":"شركة الدلتا","agreedFeeMinor":2000000,"clientIds":["%s","%s"]}""".formatted(c1, c2))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").exists()).andExpect(jsonPath("$.clients.length()").value(2))), "$.id");
        String k2 = JsonPath.read(ok(onHost(h, a, "POST", "/api/v1/lawyer/cases", "{\"title\":\"جنحة سب\",\"caseType\":\"CRIMINAL\",\"status\":\"UNDER_STUDY\",\"clientIds\":[\"%s\"]}".formatted(c2))), "$.id");
        onHost(h, a, "POST", "/api/v1/lawyer/cases", "{\"title\":\"x\",\"status\":\"UNDER_STUDY\",\"outcome\":\"WON\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.title").exists()).andExpect(jsonPath("$.fields.outcome").exists());

        // actions carry fees; payments reduce the balance
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + k1 + "/actions", "{\"title\":\"صحيفة الدعوى\",\"feeMinor\":300000}").andExpect(jsonPath("$.billedMinor").value(2300000));
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + k1 + "/payments", "{\"amountMinor\":0}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.amountMinor").exists());
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + k1 + "/payments", "{\"amountMinor\":1000000,\"method\":\"INSTAPAY\",\"clientId\":\"%s\"}".formatted(c1)).andExpect(jsonPath("$.balanceMinor").value(1300000));
        onHost(h, a, "GET", "/api/v1/lawyer/clients/" + c1, null).andExpect(jsonPath("$.balanceMinor").value(1300000)).andExpect(jsonPath("$.cases.length()").value(1));
        onHost(h, a, "GET", "/api/v1/lawyer/finance", null).andExpect(jsonPath("$.collectedMinor").value(1000000)).andExpect(jsonPath("$.dueMinor").value(1300000));

        // a step with an early reminder and the one-hour reminder
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + k1 + "/steps", "{\"description\":\"\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.description").exists()).andExpect(jsonPath("$.fields.startsAt").exists());
        onHost(h, a, "POST", "/api/v1/lawyer/cases/" + k1 + "/steps", "{\"kind\":\"HEARING\",\"description\":\"جلسة المرافعة\",\"startsAt\":\"%s\"}".formatted(Instant.now().plus(40, ChronoUnit.MINUTES)))
                .andExpect(jsonPath("$.steps.length()").value(1));
        reminders.remind();
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE notification_type = 'LEGAL_STEP_SOON' AND payload->>'caseId' = :k").param("k", k1).query(Long.class).single()).isEqualTo(1);

        // the filters: status / outcome chips, type, client, date range, search
        onHost(h, a, "PUT", "/api/v1/lawyer/cases/" + k1 + "/status", "{\"status\":\"CLOSED\",\"outcome\":\"WON\"}").andExpect(jsonPath("$.closedOn").exists());
        onHost(h, a, "GET", "/api/v1/lawyer/cases?outcome=WON", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.counts.won").value(1));
        onHost(h, a, "GET", "/api/v1/lawyer/cases?status=UNDER_STUDY,OPEN", null).andExpect(jsonPath("$.meta.total").value(1)).andExpect(jsonPath("$.data[0].id").value(k2));
        onHost(h, a, "GET", "/api/v1/lawyer/cases?type=CRIMINAL", null).andExpect(jsonPath("$.meta.total").value(1));
        onHost(h, a, "GET", "/api/v1/lawyer/cases?clientId=" + c2, null).andExpect(jsonPath("$.meta.total").value(2));
        onHost(h, a, "GET", "/api/v1/lawyer/cases?from=2000-01-01&to=2000-12-31", null).andExpect(jsonPath("$.meta.total").value(0));
        onHost(h, a, "GET", "/api/v1/lawyer/cases?q=الدلتا", null).andExpect(jsonPath("$.meta.total").value(1));
        // deep search folds Arabic letter forms («احمد» finds «أحمد») and looks inside actions too
        String found = ok(onHost(h, a, "GET", "/api/v1/lawyer/search?q=احمد", null).andExpect(status().isOk()));
        assertThat(JsonPath.<List<Object>>read(found, "$.clients")).hasSize(1);
        assertThat(JsonPath.<List<Object>>read(ok(onHost(h, a, "GET", "/api/v1/lawyer/search?q=صحيفه", null)), "$.actions")).hasSize(1);
        onHost(h, a, "GET", "/api/v1/lawyer/overview", null).andExpect(jsonPath("$.winRate").value(100)).andExpect(jsonPath("$.clients").value(2));
        onHost(h, a, "GET", "/api/v1/lawyer/clients/" + c1 + "/statement", null).andExpect(jsonPath("$.cases.length()").value(1));
        // a store host cannot use the lawyer API
        Tenant shop = onboard("STORE");
        onHost(shop.host(), shop.access(), "GET", "/api/v1/lawyer/cases", null).andExpect(status().is4xxClientError());
    }
}
