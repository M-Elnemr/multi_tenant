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

    void goals(Tenant t, String json) throws Exception { onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/goals", "{\"goals\":" + json + "}").andExpect(status().isOk()); }

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
    void aCustomerRequestWaitsForTheShopAndIsThenConfirmedDeclinedMovedOrCancelled() throws Exception {
        Tenant t = onboard("STORE");
        String svc = shop(t, 1, 0, false);
        goals(t, "[\"BOOKINGS\"]");
        String day = tomorrow(t);
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("available_slots", Map.of("serviceId", svc, "date", day)));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", day, "time", "12:00", "name", "Sara", "phone", "01012345678", "note", "short cut")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Your request is registered, the shop will confirm it shortly."));
        say(t, conv, "I want a haircut tomorrow at noon");
        var row = jdbc.sql("SELECT id, status, source, phone, conversation_id FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("source")).isEqualTo("AI_CHAT");
        assertThat(row.get("phone")).isEqualTo("+201012345678");
        assertThat(row.get("conversation_id")).isNotNull();
        // the customer is a customer of the shop now, and the chat knows who it is
        assertThat(jdbc.sql("SELECT source FROM commerce.customers WHERE tenant_id = :t AND phone = '+201012345678'").param("t", tid(t)).query(String.class).single()).isEqualTo("BOOKING");
        assertThat(jdbc.sql("SELECT display_name FROM chat.conversations WHERE id = :c").param("c", row.get("conversation_id")).query(String.class).single()).isEqualTo("Sara");
        // the time a waiting request holds is not offered to anyone else
        assertThat(slots(t, svc, day)).doesNotContain("12:00");
        onHost(t.host(), t.access(), "GET", "/api/v1/store/bookings/summary", null).andExpect(jsonPath("$.pending").value(1));

        String id = row.get("id").toString();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id + "/confirm", null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        // the customer hears it in the same chat
        assertThat(jdbc.sql("SELECT content FROM chat.messages WHERE conversation_id = :c AND role = 'assistant' ORDER BY id DESC LIMIT 1").param("c", row.get("conversation_id")).query(String.class).single()).contains("12:00").contains("B-");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id + "/confirm", null).andExpect(status().isBadRequest());   // already confirmed
        // the shop moves it to a free time (the customer is told), then the customer cancels through the assistant
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id + "/reschedule", "{\"date\":\"%s\",\"time\":\"13:00\"}".formatted(day)).andExpect(status().isOk()).andExpect(jsonPath("$.time").value("13:00")).andExpect(jsonPath("$.status").value("CONFIRMED"));
        String number = jdbc.sql("SELECT booking_number FROM commerce.bookings WHERE id = CAST(:i AS uuid)").param("i", id).query(String.class).single();
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("cancel_booking", Map.of("bookingNumber", number, "phone", "01012345678", "reason", "changed my mind")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Your appointment is cancelled."));
        say(t, conv, "please cancel my appointment, I changed my mind");
        assertThat(jdbc.sql("SELECT status FROM commerce.bookings WHERE id = CAST(:i AS uuid)").param("i", id).query(String.class).single()).isEqualTo("CANCELLED");
        assertThat(slots(t, svc, day)).contains("13:00");   // the time is free again
        assertThat(jdbc.sql("SELECT count(*) FROM notifications.notifications WHERE tenant_id = :t AND notification_type IN ('BOOKING_REQUESTED', 'BOOKING_CANCELLED')").param("t", tid(t)).query(Long.class).single()).isGreaterThanOrEqualTo(2L);

        // a request the shop declines: the customer gets the reason and the time is free again
        FakeAiProvider.SCRIPT.clear();
        String conv2 = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", day, "time", "15:00", "name", "Mona", "phone", "01155555555")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Registered."));
        say(t, conv2, "book me at 3 pm");
        String id2 = jdbc.sql("SELECT id::text FROM commerce.bookings WHERE tenant_id = :t AND phone = '+201155555555'").param("t", tid(t)).query(String.class).single();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id2 + "/decline", "{\"reason\":\"closed early that day\"}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(jdbc.sql("SELECT content FROM chat.messages WHERE conversation_id = (SELECT id FROM chat.conversations WHERE external_id = :e) AND role = 'assistant' ORDER BY id DESC LIMIT 1").param("e", conv2).query(String.class).single()).contains("closed early");
        assertThat(slots(t, svc, day)).contains("15:00");
    }

    @Test
    void aCustomerOnlyGetsTimesInsideTheNoticeAndThatCannotBeBookedTwice() throws Exception {
        Tenant t = onboard("STORE");
        String svc = shop(t, 1, 0, true);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"minNoticeMinutes\":10080}").andExpect(status().isOk());   // a week's notice
        goals(t, "[\"BOOKINGS\"]");
        String day = tomorrow(t);
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("available_slots", Map.of("serviceId", svc, "date", day)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry, nothing that day."));
        say(t, UUID.randomUUID().toString(), "anything tomorrow?");
        assertThat(FakeAiProvider.RESULTS.stream().map(Object::toString).toList()).anyMatch(r -> r.contains("freeTimes") && r.contains("[]"));   // the customer is not offered a time the shop would not accept
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", day, "time", "12:00", "name", "Sara", "phone", "01012345678")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry."));
        say(t, UUID.randomUUID().toString(), "book tomorrow at noon");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query(Long.class).single()).isZero();   // refused: nothing was created
        // the shop's own screen still books it (it may book closer in), and with automatic confirmation the customer's own is CONFIRMED at once
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"minNoticeMinutes\":0}").andExpect(status().isOk());
        FakeAiProvider.RESULTS.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", day, "time", "12:00", "name", "Sara", "phone", "01012345678")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Booked."));
        say(t, UUID.randomUUID().toString(), "book tomorrow at noon please");
        assertThat(jdbc.sql("SELECT status FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query(String.class).single()).isEqualTo("CONFIRMED");
        // the same time asked for again is taken
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", day, "time", "12:00", "name", "Mona", "phone", "01155555555")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Sorry, taken."));
        say(t, UUID.randomUUID().toString(), "book tomorrow at noon too");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void whatTheOwnerSwitchedOnIsWhatTheAssistantIsToldAndGivenAndAShopThatChoseNothingIsUnchanged() throws Exception {
        Tenant plain = onboard("STORE");
        onHost(plain.host(), plain.access(), "GET", "/api/v1/store/ai/goals", null).andExpect(jsonPath("$.goals[0]").value("ORDERS")).andExpect(jsonPath("$.goals.length()").value(1));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello"));
        say(plain, UUID.randomUUID().toString(), "what do you sell here please");
        assertThat(FakeAiProvider.lastSystem).doesNotContain("WHAT THIS SHOP WANTS YOU TO DO");
        assertThat(FakeAiProvider.lastTools().stream().map(d -> d.name()).toList()).contains("prepare_order").doesNotContain("save_lead", "book_appointment", "list_services");

        Tenant t = onboard("STORE");
        shop(t, 1, 0, false);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/ai/goals", "{\"goals\":[\"NOPE\"]}").andExpect(status().isBadRequest());
        goals(t, "[\"LEADS\",\"BOOKINGS\"]");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello"));
        say(t, UUID.randomUUID().toString(), "what do you offer here please");
        assertThat(FakeAiProvider.lastSystem).contains("WHAT THIS SHOP WANTS YOU TO DO").contains("NAME FIRST").contains("PHONE AFTER THEY ARE SATISFIED").contains("APPOINTMENTS").contains("Haircut").contains("OPENING HOURS").contains("PRODUCT ORDERS ARE OFF");
        assertThat(FakeAiProvider.lastTools().stream().map(d -> d.name()).toList()).contains("save_lead", "book_appointment", "available_slots", "list_services").doesNotContain("prepare_order", "place_order", "cancel_order");
        // orders stay on next to the others: the order tools remain
        goals(t, "[\"ORDERS\",\"LEADS\"]");
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Hello"));
        say(t, UUID.randomUUID().toString(), "tell me more about your shop please");
        assertThat(FakeAiProvider.lastTools().stream().map(d -> d.name()).toList()).contains("prepare_order", "save_lead").doesNotContain("book_appointment");
        assertThat(FakeAiProvider.lastSystem).doesNotContain("PRODUCT ORDERS ARE OFF").doesNotContain("APPOINTMENTS (strict)");
        // another shop's choice is its own
        onHost(plain.host(), plain.access(), "GET", "/api/v1/store/ai/goals", null).andExpect(jsonPath("$.goals.length()").value(1));
    }

    @Test
    void aLeadIsSavedAsACustomerWhenTheyGiveTheirNumberAndOnlyWantedOffersAreMarked() throws Exception {
        Tenant t = onboard("STORE");
        goals(t, "[\"ORDERS\",\"LEADS\"]");
        String conv = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("save_lead", Map.of("name", "Sara")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Nice to meet you Sara!"));
        say(t, conv, "hi, what do you sell?");
        // a name alone is not a contact: no customer yet, but the chat knows the name
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.customers WHERE tenant_id = :t").param("t", tid(t)).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT display_name FROM chat.conversations WHERE external_id = :e").param("e", conv).query(String.class).single()).isEqualTo("Sara");
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("save_lead", Map.of("name", "Sara", "phone", "٠١٠١٢٣٤٥٦٧٨", "interest", "summer dresses", "wantsOffers", true)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Thank you!"));
        say(t, conv, "thanks, my number is 01012345678");
        var c = jdbc.sql("SELECT name, phone, source, marketing_opt_in, notes FROM commerce.customers WHERE tenant_id = :t").param("t", tid(t)).query().singleRow();
        assertThat(c.get("name")).isEqualTo("Sara");
        assertThat(c.get("phone")).isEqualTo("+201012345678");   // Arabic digits are read, the number is stored in one form
        assertThat(c.get("source")).isEqualTo("LEAD");
        assertThat(c.get("marketing_opt_in")).isEqualTo(true);
        assertThat((String) c.get("notes")).contains("summer dresses");
        // a customer who declined offers is saved without the offers mark, and a bad number is refused
        String conv2 = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("save_lead", Map.of("name", "Mona", "phone", "123", "wantsOffers", false)));
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("save_lead", Map.of("name", "Mona", "phone", "01155555555", "wantsOffers", false)));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Thanks Mona."));
        say(t, conv2, "ok thanks, 01155555555");
        assertThat(jdbc.sql("SELECT marketing_opt_in FROM commerce.customers WHERE tenant_id = :t AND phone = '+201155555555'").param("t", tid(t)).query(Boolean.class).single()).isFalse();
        // the leads show in the customers screen with where they came from
        assertThat(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/customers", null).andExpect(status().isOk()))).contains("LEAD");
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
