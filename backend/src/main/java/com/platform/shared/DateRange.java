package com.platform.shared;

import java.time.LocalDate;

/**
 * The "from / to" date filter every list screen offers. Days are the shop's own days (its time zone, Cairo by default),
 * both ends included; a missing end is open. The SQL uses the parameters :from and :to and the tenant parameter you name.
 */
public final class DateRange {
    private DateRange() {}

    /** SQL condition: {@code column}'s local day is inside [:from, :to]. */
    public static String sql(String column, String tenantParam) {
        String day = "((" + column + ") AT TIME ZONE coalesce((SELECT tz_t.timezone FROM core.tenants tz_t WHERE tz_t.id = :" + tenantParam + "), 'Africa/Cairo'))::date";
        return "(CAST(:from AS date) IS NULL OR " + day + " >= CAST(:from AS date)) AND (CAST(:to AS date) IS NULL OR " + day + " <= CAST(:to AS date))";
    }

    /** SQL condition for a plain date column (no time zone). */
    public static String sqlDate(String column) {
        return "(CAST(:from AS date) IS NULL OR " + column + " >= CAST(:from AS date)) AND (CAST(:to AS date) IS NULL OR " + column + " <= CAST(:to AS date))";
    }

    /** 400 on the "to" field when the range is upside down. */
    public static void check(LocalDate from, LocalDate to) {
        if (from != null && to != null && to.isBefore(from))
            throw BusinessException.badRequest("INVALID_DATE_RANGE", "The end date is before the start date").field("to");
    }
}
