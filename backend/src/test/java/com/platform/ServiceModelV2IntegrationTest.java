package com.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.platform.booking.BookingService;
import com.platform.chat.AiProvider.Turn;
import java.util.Map;
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

/** Services v2 and availability v2 for a SERVICE business: categories, add-ons, special days, vacations, per-service capacity, the customer's answers, pending expiry, cancel deadline, concurrent requests. */
class ServiceModelV2IntegrationTest extends IntegrationTestBase {
    @Autowired JdbcClient jdbc;
    @Autowired BookingService bookings;

    @org.junit.jupiter.api.BeforeEach
    void clean() { FakeAiProvider.reset(); }

    Tenant svcTenant() throws Exception {
        String slug = "sv" + uniq();
        String res = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/onboarding/tenants").header("Host", "platform.test").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"type\":\"SERVICE\",\"name\":\"Salon %s\",\"slug\":\"%s\",\"ownerFirstName\":\"Owner\",\"phone\":\"%s\",\"password\":\"s3cretPass!\"}".formatted(slug, slug, nextPhone()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Tenant(slug, JsonPath.read(res, "$.host"), "", JsonPath.read(res, "$.tokens.accessToken"), JsonPath.read(res, "$.tokens.refreshToken"));
    }

    UUID tid(Tenant t) { return jdbc.sql("SELECT id FROM core.tenants WHERE slug = :s").param("s", t.slug()).query(UUID.class).single(); }
    String body(ResultActions r) throws Exception { return r.andReturn().getResponse().getContentAsString(); }
    String day(Tenant t, int plus) { return LocalDate.now(ZoneId.of(jdbc.sql("SELECT timezone FROM core.tenants WHERE id = :t").param("t", tid(t)).query(String.class).single())).plusDays(plus).toString(); }

    /** Open 10:00-18:00 every day, 3 chairs, confirmed by the shop's own bookings. */
    void open(Tenant t, int stations) throws Exception {
        StringBuilder ranges = new StringBuilder();
        for (int d = 1; d <= 7; d++) ranges.append(d > 1 ? "," : "").append("{\"weekday\":%d,\"opens\":\"10:00\",\"closes\":\"18:00\"}".formatted(d));
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/hours", "{\"ranges\":[%s]}".formatted(ranges)).andExpect(status().isOk());
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"stations\":%d,\"slotStepMinutes\":30,\"minNoticeMinutes\":0,\"maxDaysAhead\":60,\"autoConfirm\":true}".formatted(stations)).andExpect(status().isOk());
    }

    String service(Tenant t, String json) throws Exception { return JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services", json).andExpect(status().isCreated())), "$.id"); }

    List<String> slots(Tenant t, String svc, String date, String addons) throws Exception {
        return JsonPath.read(body(onHost(t.host(), t.access(), "GET", "/api/v1/store/booking/slots?serviceId=" + svc + "&date=" + date + (addons == null ? "" : "&addonIds=" + addons), null).andExpect(status().isOk())), "$");
    }

    ResultActions book(Tenant t, String svc, String date, String time, String extra) throws Exception {
        return onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"%s\",\"name\":\"Sara\",\"phone\":\"%s\"%s}".formatted(svc, date, time, "010" + (10000000 + (int) (Math.random() * 89999999)), extra == null ? "" : "," + extra));
    }

    @Test
    void aServiceHasACategoryAnImagePromotionPriceLocationQuestionsAndAddOns() throws Exception {
        Tenant t = svcTenant();
        String cat = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/service-categories", "{\"name\":\"Hair\"}").andExpect(status().isCreated())), "$.id");
        onHost(t.host(), t.access(), "POST", "/api/v1/store/service-categories", "{\"name\":\"Hair\"}").andExpect(status().isBadRequest());   // a name once
        UUID image = UUID.randomUUID();
        String svc = service(t, "{\"name\":\"Colour\",\"durationMinutes\":90,\"priceMinor\":50000,\"priceBeforeMinor\":60000,\"categoryId\":\"%s\",\"shortDescription\":\"Full colour\",\"locationMode\":\"BOTH\",\"maxConcurrent\":2,\"serviceArea\":\"Nasr City\",\"instructions\":\"Wash hair first\",\"cancellationPolicy\":\"Free until 24 h\",\"imageFileId\":\"%s\",\"gallery\":[\"%s\"],\"requiredFields\":[{\"key\":\"hairLength\",\"label\":\"Hair length\"}]}".formatted(cat, image, image));
        String a1 = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services/" + svc + "/addons", "{\"name\":\"Treatment\",\"priceMinor\":10000,\"durationMinutes\":30}").andExpect(status().isCreated())), "$.id");
        String json = body(onHost(t.host(), t.access(), "GET", "/api/v1/store/services", null).andExpect(status().isOk()));
        assertThat(JsonPath.<String>read(json, "$[0].categoryName")).isEqualTo("Hair");
        assertThat(JsonPath.<Integer>read(json, "$[0].priceBeforeMinor")).isEqualTo(60000);
        assertThat(JsonPath.<String>read(json, "$[0].locationMode")).isEqualTo("BOTH");
        assertThat(JsonPath.<String>read(json, "$[0].imageFileId")).isEqualTo(image.toString());
        assertThat(JsonPath.<List<String>>read(json, "$[0].gallery")).containsExactly(image.toString());
        assertThat(JsonPath.<String>read(json, "$[0].requiredFields[0].key")).isEqualTo("hairLength");
        assertThat(JsonPath.<String>read(json, "$[0].addons[0].id")).isEqualTo(a1);
        // refused: an unknown location, a category of nobody, a question without a key
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/services/" + svc, "{\"locationMode\":\"MOON\"}").andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/services/" + svc, "{\"categoryId\":\"%s\"}".formatted(UUID.randomUUID())).andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "PATCH", "/api/v1/store/services/" + svc, "{\"requiredFields\":[{\"key\":\"\",\"label\":\"x\"}]}").andExpect(status().isBadRequest());
        // another business sees none of it and cannot touch it
        Tenant other = svcTenant();
        onHost(other.host(), other.access(), "GET", "/api/v1/store/service-categories", null).andExpect(jsonPath("$.length()").value(0));
        onHost(other.host(), other.access(), "POST", "/api/v1/store/services/" + svc + "/addons", "{\"name\":\"Steal\"}").andExpect(status().isNotFound());
        onHost(other.host(), other.access(), "DELETE", "/api/v1/store/service-categories/" + cat, null).andExpect(status().isNoContent());
        onHost(t.host(), t.access(), "GET", "/api/v1/store/service-categories", null).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anAddOnMakesTheAppointmentLongerAndDearerAndOnlyTimesThatFitAreOffered() throws Exception {
        Tenant t = svcTenant();
        open(t, 1);
        String svc = service(t, "{\"name\":\"Cut\",\"durationMinutes\":60,\"priceMinor\":15000}");
        String add = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services/" + svc + "/addons", "{\"name\":\"Beard\",\"priceMinor\":5000,\"durationMinutes\":60}").andExpect(status().isCreated())), "$.id");
        String d = day(t, 2);
        assertThat(slots(t, svc, d, null)).contains("17:00");          // 17:00-18:00 fits the cut alone
        assertThat(slots(t, svc, d, add)).doesNotContain("17:00", "16:30").contains("16:00");   // with the beard (2 hours) the last start is 16:00
        String res = body(book(t, svc, d, "16:00", "\"addonIds\":[\"%s\"]".formatted(add)).andExpect(status().isCreated()));
        assertThat(JsonPath.<Integer>read(res, "$.priceMinor")).isEqualTo(20000);   // the service plus the extra
        assertThat(JsonPath.<String>read(res, "$.endTime")).isEqualTo("18:00");
        assertThat(JsonPath.<String>read(res, "$.addons[0].name")).isEqualTo("Beard");
        book(t, svc, d, "17:00", "\"addonIds\":[\"%s\"]".formatted(add)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));
        book(t, svc, d, "11:00", "\"addonIds\":[\"%s\"]".formatted(UUID.randomUUID())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADDON_UNAVAILABLE"));
        // an add-on of another service of the same business is refused too
        String other = service(t, "{\"name\":\"Shave\",\"durationMinutes\":30,\"priceMinor\":8000}");
        book(t, other, d, "11:00", "\"addonIds\":[\"%s\"]".formatted(add)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ADDON_UNAVAILABLE"));
    }

    @Test
    void aSpecialDayReplacesTheWeekdaysHoursAndAVacationClosesEveryDateInIt() throws Exception {
        Tenant t = svcTenant();
        open(t, 1);
        String svc = service(t, "{\"name\":\"Cut\",\"durationMinutes\":60,\"priceMinor\":15000}");
        String special = day(t, 3);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/special-days", "{\"date\":\"%s\",\"ranges\":[{\"opens\":\"12:00\",\"closes\":\"14:00\"},{\"opens\":\"16:00\",\"closes\":\"17:00\"}]}".formatted(special)).andExpect(status().isOk());
        assertThat(slots(t, svc, special, null)).containsExactly("12:00", "12:30", "13:00", "16:00");   // a break in the middle, as for any day
        assertThat(slots(t, svc, day(t, 4), null)).contains("10:00");   // the next day is the normal week
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/special-days", "{\"date\":\"%s\",\"ranges\":[{\"opens\":\"15:00\",\"closes\":\"14:00\"}]}".formatted(special)).andExpect(status().isBadRequest());
        onHost(t.host(), t.access(), "POST", "/api/v1/store/booking/closures", "{\"date\":\"%s\",\"to\":\"%s\",\"reason\":\"Vacation\"}".formatted(day(t, 10), day(t, 14))).andExpect(status().isOk());
        for (int i = 10; i <= 14; i++) assertThat(slots(t, svc, day(t, i), null)).as("day +" + i).isEmpty();
        assertThat(slots(t, svc, day(t, 15), null)).isNotEmpty();
        onHost(t.host(), t.access(), "POST", "/api/v1/store/booking/closures", "{\"date\":\"%s\",\"to\":\"%s\"}".formatted(day(t, 20), day(t, 300))).andExpect(status().isBadRequest());   // not a year
    }

    @Test
    void aServiceCanBeLimitedToFewerAtOnceThanTheBusinessHasChairs() throws Exception {
        Tenant t = svcTenant();
        open(t, 3);
        String limited = service(t, "{\"name\":\"Colour\",\"durationMinutes\":60,\"priceMinor\":50000,\"maxConcurrent\":1}");
        String free = service(t, "{\"name\":\"Cut\",\"durationMinutes\":60,\"priceMinor\":15000}");
        String d = day(t, 2);
        book(t, limited, d, "10:00", null).andExpect(status().isCreated());
        book(t, limited, d, "10:00", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SLOT_TAKEN"));   // the colour can be done once at a time
        book(t, free, d, "10:00", null).andExpect(status().isCreated());                                                           // the other service still has chairs
        book(t, free, d, "10:00", null).andExpect(status().isCreated());
        book(t, free, d, "10:00", null).andExpect(status().isBadRequest());                                                        // three chairs are now busy
        assertThat(slots(t, limited, d, null)).doesNotContain("10:00").contains("11:00");
    }

    @Test
    void theServicesQuestionsAreAnsweredAndAHomeVisitNeedsAnAddress() throws Exception {
        Tenant t = svcTenant();
        open(t, 2);
        String svc = service(t, "{\"name\":\"AC repair\",\"durationMinutes\":60,\"priceMinor\":30000,\"locationMode\":\"HOME\",\"requiredFields\":[{\"key\":\"brand\",\"label\":\"Brand\"},{\"key\":\"problem\",\"label\":\"Problem\"},{\"key\":\"notes\",\"label\":\"Notes\",\"required\":false}]}");
        String shop = service(t, "{\"name\":\"Cut\",\"durationMinutes\":30,\"priceMinor\":10000}");
        String d = day(t, 2);
        book(t, svc, d, "10:00", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.brand").exists());   // the question comes first
        book(t, svc, d, "10:00", "\"answers\":{\"brand\":\"LG\",\"problem\":\"not cooling\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.address").exists());
        book(t, svc, d, "10:00", "\"answers\":{\"brand\":\"LG\",\"problem\":\"not cooling\"},\"locationMode\":\"SHOP\"").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCATION_UNAVAILABLE"));
        String ok = body(book(t, svc, d, "10:00", "\"answers\":{\"brand\":\"LG\",\"problem\":\"not cooling\"},\"address\":\"12 Makram Ebeid St, Nasr City\"").andExpect(status().isCreated()));
        assertThat(JsonPath.<String>read(ok, "$.answers.brand")).isEqualTo("LG");
        assertThat(JsonPath.<String>read(ok, "$.locationMode")).isEqualTo("HOME");
        assertThat(JsonPath.<String>read(ok, "$.address")).contains("Makram");
        book(t, shop, d, "10:00", "\"locationMode\":\"HOME\",\"address\":\"somewhere far away\"").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LOCATION_UNAVAILABLE"));   // a shop-only service is not done at home
    }

    @Test
    void anUnansweredRequestStopsHoldingTheTimeAndACustomerCannotCancelInsideTheDeadline() throws Exception {
        Tenant t = svcTenant();
        open(t, 1);
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"autoConfirm\":false,\"pendingExpiresMinutes\":30,\"cancelDeadlineHours\":2}").andExpect(status().isOk());
        String svc = service(t, "{\"name\":\"Cut\",\"durationMinutes\":60,\"priceMinor\":15000}");
        String d = day(t, 2);
        // a customer's request (the shop's own booking is confirmed at once): through the public page
        String res = body(onHost(t.host(), null, "POST", "/api/v1/shop/booking", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"10:00\",\"name\":\"Mona\",\"phone\":\"01011112222\"}".formatted(svc, d)).andExpect(status().isCreated()));
        assertThat(JsonPath.<String>read(res, "$.status")).isEqualTo("PENDING");
        assertThat(slots(t, svc, d, null)).doesNotContain("10:00");      // held while it waits
        jdbc.sql("UPDATE commerce.bookings SET created_at = now() - interval '31 minutes' WHERE tenant_id = :t").param("t", tid(t)).update();
        assertThat(slots(t, svc, d, null)).contains("10:00");           // 30 minutes without an answer: the time is free again
        bookings.expirePending();
        assertThat(jdbc.sql("SELECT status FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query(String.class).single()).isEqualTo("DECLINED");
        // the deadline: an appointment in one hour cannot be cancelled by the customer, the shop can
        onHost(t.host(), t.access(), "PUT", "/api/v1/store/booking/settings", "{\"autoConfirm\":true}").andExpect(status().isOk());
        String today = day(t, 0);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO commerce.bookings (id, tenant_id, booking_number, service_id, service_name, customer_name, phone, starts_at, ends_at, blocks_until, price_minor, currency, status, source)
                SELECT :i, :t, 'B-SOON01', :s, 'Cut', 'Mona', '+201011112222', now() + interval '1 hour', now() + interval '2 hours', now() + interval '2 hours', 15000, 'EGP', 'CONFIRMED', 'WEBSITE'
                """).param("i", id).param("t", tid(t)).param("s", UUID.fromString(svc)).update();
        assertThat(today).isNotEmpty();
        onHost(t.host(), null, "POST", "/api/v1/shop/booking/cancel", "{\"bookingNumber\":\"B-SOON01\",\"phone\":\"01011112222\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_LATE_TO_CHANGE"));
        onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings/" + id + "/cancel", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void manyCustomersAskingForTheLastFreeTimeAtOnceGetExactlyOneBooking() throws Exception {
        Tenant t = svcTenant();
        open(t, 1);
        String svc = service(t, "{\"name\":\"Cut\",\"durationMinutes\":60,\"priceMinor\":15000}");
        String d = day(t, 2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            final int n = i;
            results.add(pool.submit(() -> onHost(t.host(), t.access(), "POST", "/api/v1/store/bookings", "{\"serviceId\":\"%s\",\"date\":\"%s\",\"time\":\"10:00\",\"name\":\"C%d\",\"phone\":\"0102000000%d\"}".formatted(svc, d, n, n)).andReturn().getResponse().getStatus()));
        }
        int created = 0;
        for (var f : results) if (f.get() == 201) created++;
        pool.shutdown();
        assertThat(created).as("one chair, one booking").isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.bookings WHERE tenant_id = :t AND status IN ('PENDING','CONFIRMED')").param("t", tid(t)).query(Long.class).single()).isEqualTo(1L);
    }

    String say(Tenant t, String conv, String text) throws Exception {
        return body(onHost(t.host(), null, "POST", "/api/v1/shop/chat", "{\"conversationId\":\"%s\",\"message\":\"%s\"}".formatted(conv, text)).andExpect(status().isOk()));
    }

    @Test
    void theAssistantSeesTheQuestionsAndExtrasBooksAHomeVisitAndCannotTouchSomeoneElsesBooking() throws Exception {
        Tenant t = svcTenant();
        open(t, 2);
        String svc = service(t, "{\"name\":\"Filter maintenance\",\"durationMinutes\":60,\"priceMinor\":40000,\"locationMode\":\"HOME\",\"serviceArea\":\"Cairo\",\"requiredFields\":[{\"key\":\"stages\",\"label\":\"Filter stages\"}]}");
        String add = JsonPath.read(body(onHost(t.host(), t.access(), "POST", "/api/v1/store/services/" + svc + "/addons", "{\"name\":\"New candle set\",\"priceMinor\":15000,\"durationMinutes\":15}").andExpect(status().isCreated())), "$.id");
        String d = day(t, 2);
        String conv = UUID.randomUUID().toString();
        // the model is told the service's questions and extras (from the shop's data, never from memory)
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("list_services", Map.of()));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        say(t, conv, "عايز صيانة فلتر");
        String system = FakeAiProvider.lastSystem;
        assertThat(system).contains("Filter stages [stages]").contains("New candle set [" + add + "]").contains("done at the customer's home");
        // a booking without the answer is refused by the backend and nothing is created; the model gets the reason
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", d, "time", "12:00", "name", "Ali", "phone", "01012345678", "address", "5 Tahrir St, Dokki")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        say(t, conv, "احجزلي بكرة 12");
        assertThat(jdbc.sql("SELECT count(*) FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query(Long.class).single()).isZero();
        // with the answer, the address and the extra: booked, priced with the extra, 75 minutes long
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("book_appointment", Map.of("serviceId", svc, "date", d, "time", "12:00", "name", "Ali", "phone", "01012345678", "address", "5 Tahrir St, Dokki", "answers", Map.of("stages", "7"), "addonIds", List.of(add))));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("Registered."));
        say(t, conv, "تمام 7 مراحل ومعاه شمع جديد");
        var row = jdbc.sql("SELECT price_minor, location_mode, address, answers::text AS a, source, (EXTRACT(EPOCH FROM ends_at - starts_at) / 60)::int AS minutes, booking_number FROM commerce.bookings WHERE tenant_id = :t").param("t", tid(t)).query().singleRow();
        assertThat(((Number) row.get("price_minor")).longValue()).isEqualTo(55000L);
        assertThat(row.get("location_mode")).isEqualTo("HOME");
        assertThat((String) row.get("a")).contains("\"stages\"");
        assertThat(row.get("source")).isEqualTo("AI_CHAT");
        assertThat(((Number) row.get("minutes")).intValue()).isEqualTo(75);
        // another customer (another phone) cannot cancel or move it through the assistant
        String number = (String) row.get("booking_number");
        String other = UUID.randomUUID().toString();
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("cancel_booking", Map.of("bookingNumber", number, "phone", "01099999999", "reason", "x")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        say(t, other, "الغي حجز " + number);
        assertThat(jdbc.sql("SELECT status FROM commerce.bookings WHERE booking_number = :n").param("n", number).query(String.class).single()).isEqualTo("CONFIRMED");
        FakeAiProvider.SCRIPT.clear();
        FakeAiProvider.SCRIPT.add(turns -> FakeAiProvider.call("reschedule_booking", Map.of("bookingNumber", number, "phone", "01099999999", "date", d, "time", "14:00")));
        FakeAiProvider.SCRIPT.add(turns -> Turn.model("ok"));
        say(t, other, "غير الميعاد " + number);
        assertThat(jdbc.sql("SELECT to_char(starts_at AT TIME ZONE 'Africa/Cairo', 'HH24:MI') FROM commerce.bookings WHERE booking_number = :n").param("n", number).query(String.class).single()).isEqualTo("12:00");
    }
}
