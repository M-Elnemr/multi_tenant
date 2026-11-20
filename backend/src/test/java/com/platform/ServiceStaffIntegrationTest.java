package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** Optional staff: a person has skills, hours of their own, days off and a capacity; a service with staff is bookable only when one of them is free; a customer may ask for one. */
class ServiceStaffIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    Tenant svcTenant() throws Exception {
        String slug = "st" + uniq();
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SERVICE\",\"name\":\"Salon %s\",\"slug\":\"%s\",\"ownerFirstName\":\"Owner\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, slug, nextPhone()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), "", JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    UUID tid(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    String day(Tenant t, int plus) { return LocalDate.now(ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tid(t)).query(String.class).single())).plusDays(plus).toString(); }

    void open(Tenant t, int stations) throws Exception {
        StringBuilder ranges = new StringBuilder();
        for (int d = 1; d <= 7; d++) ranges.append(d > 1 ? "," : "").append("{\"weekday\":%d,\"opens\":\"10:00\",\"closes\":\"18:00\"}".formatted(d));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/hours", "{\"ranges\":[%s]}".formatted(ranges)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"stations\":%d,\"slotStepMinutes\":30,\"minNoticeMinutes\":0,\"maxDaysAhead\":60,\"autoConfirm\":true}".formatted(stations)).andExpect(status().isOk());
    }

    String service(Tenant t, String name) throws Exception { return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services", "{\"name\":\"%s\",\"durationMinutes\":60,\"priceMinor\":15000}".formatted(name)).andExpect(status().isCreated())), "$.id"); }
    String staff(Tenant t, String json) throws Exception { return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/staff", json).andExpect(status().isCreated())), "$.id"); }
    List<String> slots(Tenant t, String svc, String date, String staff) throws Exception {
        return JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/booking/slots?serviceId=" + svc + "&date=" + date + (staff == null ? "" : "&staffId=" + staff), null).andExpect(status().isOk())), "$");
    }
    ResultActions book(Tenant t, String svc, String date, String time, String staff) throws Exception {
        return onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"%s\",\"name\":\"Sara\",\"phone\":\"010%d\"%s}".formatted(svc, date, time, 10000000 + (int) (Math.random() * 89999999), staff == null ? "" : ",\"staffId\":\"" + staff + "\""));
    }

    @Test
    void aServiceWithStaffIsBookableOnlyWhenSomeoneWhoDoesItIsFreeAndTheBookingIsAssigned() throws Exception {
        Tenant t = svcTenant();
        open(t, 5);
        String cut = service(t, "Cut"), colour = service(t, "Colour");
        String ali = staff(t, "{\"name\":\"Ali\",\"serviceIds\":[\"%s\",\"%s\"]}".formatted(cut, colour));
        String omar = staff(t, "{\"name\":\"Omar\",\"serviceIds\":[\"%s\"]}".formatted(cut));   // Omar does not colour
        String d = day(t, 2);
        // two people do the cut: two at the same time, a third is refused; the colour has one person
        String first = body(book(t, cut, d, "10:00", null).andExpect(status().isCreated()));
        String second = body(book(t, cut, d, "10:00", null).andExpect(status().isCreated()));
        assertThat(List.of(JsonPath.<String>read(first, "$.staffId"), JsonPath.<String>read(second, "$.staffId"))).containsExactlyInAnyOrder(ali, omar);   // each got a different person
        assertThat(JsonPath.<String>read(first, "$.staffName")).isNotBlank();
        book(t, cut, d, "10:00", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        assertThat(slots(t, cut, d, null)).doesNotContain("10:00").contains("11:00");
        assertThat(slots(t, colour, d, null)).doesNotContain("10:00");   // Ali is busy and he is the only one who colours
        // a customer who asks for Omar at 11:00 gets Omar; asking for Omar for a colour is refused (he does not do it)
        assertThat(JsonPath.<String>read(body(book(t, cut, d, "11:00", omar).andExpect(status().isCreated())), "$.staffId")).isEqualTo(omar);
        book(t, colour, d, "12:00", omar).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("STAFF_UNAVAILABLE"));
        book(t, cut, d, "11:00", omar).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));   // Omar is busy then, Ali is free but was not asked for
        assertThat(slots(t, cut, d, omar)).doesNotContain("10:00", "11:00").contains("12:00");
    }

    @Test
    void ownHoursDaysOffAndCapacityAreHonouredAndCleaningTeamsCanTakeMoreThanOne() throws Exception {
        Tenant t = svcTenant();
        open(t, 5);
        String clean = service(t, "Cleaning");
        String lina = staff(t, "{\"name\":\"Lina\",\"serviceIds\":[\"%s\"],\"hours\":[{\"weekday\":1,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":2,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":3,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":4,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":5,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":6,\"opens\":\"12:00\",\"closes\":\"15:00\"},{\"weekday\":7,\"opens\":\"12:00\",\"closes\":\"15:00\"}]}".formatted(clean));
        String d = day(t, 2);
        assertThat(slots(t, clean, d, null)).containsExactly("12:00", "12:30", "13:00", "13:30", "14:00");   // only inside her own hours, not the business 10-18
        onHost(t.host(), t.access(), "POST", "/api/v1/store/staff/" + lina + "/days-off", "{\"date\":\"%s\",\"reason\":\"Sick\"}".formatted(d)).andExpect(status().isOk());
        assertThat(slots(t, clean, d, null)).isEmpty();                                                   // her day off: nobody else does cleaning
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/staff/" + lina + "/days-off/" + d, null).andExpect(status().isOk());
        assertThat(slots(t, clean, d, null)).isNotEmpty();
        // a team of capacity 2 serves two appointments at once
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/staff/" + lina, "{\"capacity\":2}").andExpect(status().isOk());
        book(t, clean, d, "12:00", null).andExpect(status().isCreated());
        book(t, clean, d, "12:00", null).andExpect(status().isCreated());
        book(t, clean, d, "12:00", null).andExpect(status().isBadRequest());
        // a service nobody does is not bookable once staff exist for others; deleting the only person who does it frees nothing: the business capacity rules again
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/staff/" + lina, null).andExpect(status().isNoContent());   // she has bookings: switched off, history kept
        assertThat(jdbc.sql("SELECT is_active FROM commerce.service_staff WHERE id = CAST(:i AS uuid)").param("i", lina).query(Boolean.class).single()).isFalse();
        assertThat(slots(t, clean, d, null)).isNotEmpty();   // no active staff for it: business hours and capacity apply
    }

    @Test
    void manyAtOnceForTheLastPersonGetExactlyOneBookingAndRescheduleKeepsThePerson() throws Exception {
        Tenant t = svcTenant();
        open(t, 5);
        String svc = service(t, "Cut");
        String ali = staff(t, "{\"name\":\"Ali\",\"serviceIds\":[\"%s\"]}".formatted(svc));
        String d = day(t, 2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> res = new ArrayList<>();
        for (int i = 0; i < 8; i++) { final int n = i; res.add(pool.submit(() -> onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"10:00\",\"name\":\"C%d\",\"phone\":\"0102100000%d\"}".formatted(svc, d, n, n)).andReturn().getResponse().getStatus())); }
        int created = 0;
        for (var f : res) if (f.get() == 201) created++;
        pool.shutdown();
        assertThat(created).as("one person, one booking").isEqualTo(1);
        String id = jdbc.sql("SELECT id FROM commerce.bookings WHERE tenant_id = :t AND status IN ('PENDING','CONFIRMED')").param("t", tid(t)).query(String.class).single();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id + "/reschedule", "{\"date\":\"%s\",\"time\":\"13:00\"}".formatted(d)).andExpect(status().isOk()).andExpect(jsonPath("$.staffId").value(ali)).andExpect(jsonPath("$.time").value("13:00"));
    }

    @Test
    void staffBelongToOneBusinessAndInvalidInputIsRefused() throws Exception {
        Tenant a = svcTenant(), b = svcTenant();
        String svcA = service(a, "Cut");
        String ali = staff(a, "{\"name\":\"Ali\",\"serviceIds\":[\"%s\"]}".formatted(svcA));
        onHost(b.host(), b.access(), "GET", "/api/v1/store/staff", null).andExpect(jsonPath("$.length()").value(0));
        onHost(b.host(), b.access(), "PATCH", "/api/v1/store/staff/" + ali, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "POST", "/api/v1/store/staff/" + ali + "/days-off", "{\"date\":\"2030-01-01\"}").andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "POST", "/api/v1/store/staff", "{\"name\":\"X\",\"serviceIds\":[\"%s\"]}".formatted(svcA)).andExpect(status().isBadRequest());   // another business's service
        onHost(a.host(), a.access(), "POST", "/api/v1/store/staff", "{\"name\":\"\"}").andExpect(status().isBadRequest());
        onHost(a.host(), a.access(), "POST", "/api/v1/store/staff", "{\"name\":\"Z\",\"capacity\":99}").andExpect(status().isBadRequest());
        onHost(a.host(), a.access(), "POST", "/api/v1/store/staff", "{\"name\":\"Z\",\"hours\":[{\"weekday\":1,\"opens\":\"15:00\",\"closes\":\"12:00\"}]}").andExpect(status().isBadRequest());
    }
}
