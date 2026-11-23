package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.chat.AiProvider.Turn;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

/** A shop that sells services by appointment: services, hours, free times that respect the stations and the buffer, requests the shop confirms, and the assistant taking bookings and saving leads. */
class ServiceBookingsIntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void clean() { FakeAiProvider.reset(); }

    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }

    UUID tid(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }

    String tomorrow(Tenant t) {
        String zone = jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tid(t)).query(String.class).single();
        return LocalDate.now(ZoneId.of(zone)).plusDays(1).toString();
    }

    /** A shop open 10:00-18:00 every day, one chair, with a 60 minute service (and a break after it when given). */
    String shop(Tenant t, int stations, int buffer, boolean autoConfirm) throws Exception {
        StringBuilder ranges = new StringBuilder();
        for (int d = 1; d <= 7; d++) ranges.append(d > 1 ? "," : "").append("{\"weekday\":%d,\"opens\":\"10:00\",\"closes\":\"18:00\"}".formatted(d));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/hours", "{\"ranges\":[%s]}".formatted(ranges)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"stations\":%d,\"slotStepMinutes\":30,\"minNoticeMinutes\":0,\"maxDaysAhead\":30,\"autoConfirm\":%s}".formatted(stations, autoConfirm)).andExpect(status().isOk());
        return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services",
                "{\"name\":\"Haircut\",\"description\":\"Cut and style\",\"durationMinutes\":60,\"bufferMinutes\":%d,\"priceMinor\":15000}".formatted(buffer)).andExpect(status().isCreated())), "$.id");
    }

    List<String> slots(Tenant t, String service, String date) throws Exception {
        return JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/booking/slots?serviceId=" + service + "&date=" + date, null).andExpect(status().isOk())), "$");
    }

    ResultActions book(Tenant t, String service, String date, String time, String phone) throws Exception {
        return onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"%s\",\"name\":\"Sara\",\"phone\":\"%s\"}".formatted(service, date, time, phone));
    }


    String say(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    @Test
    void freeTimesFollowTheHoursTheStationsTheBreakAfterAServiceAndTheDaysOff() throws Exception {
        Tenant t = onboard("STORE");
        String svc = shop(t, 1, 0, false);
        String day = tomorrow(t);
        assertThat(slots(t, svc, day)).hasSize(15).startsWith("10:00").endsWith("17:00");   // 60 minutes in an 8 hour day, every 30 minutes
        // one chair: a booked time and every start that would overlap it are gone
        book(t, svc, day, "10:00", "01012345678").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CONFIRMED"));   // the shop's own booking needs no confirmation
        assertThat(slots(t, svc, day)).doesNotContain("10:00", "10:30").contains("11:00");
        book(t, svc, day, "10:30", "01099999999").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        // two chairs: a second customer fits at the same time, a third does not
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"stations\":2}").andExpect(status().isOk());
        book(t, svc, day, "10:00", "01022222222").andExpect(status().isCreated());
        book(t, svc, day, "10:00", "01033333333").andExpect(status().isBadRequest());
        // a day off: nothing to book
        onHost(t.host(), t.access(), "POST", "/api/v1/store/booking/closures", "{\"date\":\"%s\",\"reason\":\"Holiday\"}".formatted(day)).andExpect(status().isOk());
        assertThat(slots(t, svc, day)).isEmpty();
        book(t, svc, day, "14:00", "01044444444").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "DELETE", "/api/v1/store/booking/closures/" + day, null).andExpect(status().isOk());
        assertThat(slots(t, svc, day)).contains("14:00");

        // a service with a 30 minute break after it keeps the chair busy that much longer
        Tenant u = onboard("STORE");
        String withBreak = shop(u, 1, 30, false);
        book(u, withBreak, day, "10:00", "01012345678").andExpect(status().isCreated());
        assertThat(slots(u, withBreak, day)).doesNotContain("10:00", "10:30", "11:00").contains("11:30");
    }

    @Test
    void theShopWebsiteBooksWithTheSameRulesAndALimitStopsAFloodFromOneNumber() throws Exception {
        Tenant t = onboard("STORE");
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.profile.bookingEnabled").value(false));   // no services yet: no booking page link
        String svc = shop(t, 1, 0, false);
        onHost(t.host(), null, "GET", "/api/v1/shop/profile", null).andExpect(jsonPath("$.profile.bookingEnabled").value(true));
        onHost(t.host(), null, "GET", "/api/v1/shop/booking/config", null).andExpect(status().isOk()).andExpect(jsonPath("$.services[0].name").value("Haircut")).andExpect(jsonPath("$.autoConfirm").value(false));
        String day = tomorrow(t);
        List<String> free = JsonPath.read(body(onHost(t.host(), null, "GET", "/api/v1/shop/booking/slots?serviceId=" + svc + "&date=" + day, null).andExpect(status().isOk())), "$");
        assertThat(free).contains("12:00");
        String req = "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"12:00\",\"name\":\"Sara\",\"phone\":\"01012345678\"}".formatted(svc, day);
        String made = body(onHost(t.host(), null, "POST", "/api/v1/shop/booking", req).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
        String number = JsonPath.read(made, "$.bookingNumber");
        assertThat(jdbc.sql("SELECT source FROM commerce.bookings WHERE booking_number = :n").param("n", number).query(String.class).single()).isEqualTo("WEBSITE");
        // the same time again is taken; a wrong phone finds nothing; the right one finds it and can cancel it
        onHost(t.host(), null, "POST", "/api/v1/shop/booking", req.replace("01012345678", "01155555555")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        onHost(t.host(), null, "GET", "/api/v1/shop/booking/lookup?number=" + number + "&phone=01099999999", null).andExpect(status().isNotFound());
        onHost(t.host(), null, "GET", "/api/v1/shop/booking/lookup?number=" + number + "&phone=01012345678", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        onHost(t.host(), null, "POST", "/api/v1/shop/booking/cancel", "{\"bookingNumber\":\"%s\",\"phone\":\"01012345678\"}".formatted(number)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        // one number cannot fill the shop's day: the sixth waiting request in a day is refused
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"stations\":10}").andExpect(status().isOk());
        for (int i = 0; i < 5; i++) onHost(t.host(), null, "POST", "/api/v1/shop/booking", req.replace("12:00", (10 + i) + ":00").replace("Sara", "Prank")).andExpect(status().isCreated());
        onHost(t.host(), null, "POST", "/api/v1/shop/booking", req.replace("12:00", "16:00").replace("Sara", "Prank")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_BOOKINGS"));
        // another shop's host knows nothing of these services
        Tenant other = onboard("STORE");
        onHost(other.host(), null, "GET", "/api/v1/shop/booking/slots?serviceId=" + svc + "&date=" + day, null).andExpect(status().isNotFound());
    }

    @Test
    void anotherShopCannotSeeOrChangeThisShopsAppointmentsOrServices() throws Exception {
        Tenant a = onboard("STORE");
        Tenant b = onboard("STORE");
        String svc = shop(a, 1, 0, false);
        String day = tomorrow(a);
        book(a, svc, day, "11:00", "01012345678").andExpect(status().isCreated());
        String id = jdbc.sql("SELECT id::text FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(a)).query(String.class).single();
        onHost(b.host(), b.access(), "POST", "/api/v1/store/bookings/" + id + "/cancel", "{}").andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "PATCH", "/api/v1/store/services/" + svc, "{\"name\":\"Hacked\"}").andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "GET", "/api/v1/store/booking/slots?serviceId=" + svc + "&date=" + day, null).andExpect(status().isNotFound());
        onHost(b.host(), b.access(), "GET", "/api/v1/store/bookings", null).andExpect(jsonPath("$.meta.total").value(0));
        // a service that was booked is switched off, not deleted (its history stays)
        onHost(a.host(), a.access(), "DELETE", "/api/v1/store/services/" + svc, null).andExpect(status().isNoContent());
        assertThat(jdbc.sql("SELECT is_active FROM commerce.services WHERE id = CAST(:i AS uuid)").param("i", svc).query(Boolean.class).single()).isFalse();
    }
}
