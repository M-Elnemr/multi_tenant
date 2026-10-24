package com.platform.commerce;

/** The one definition of the customer segments (shared by the segments screen and the Copilot). */
public final class SegmentSql {
    private SegmentSql() {}

    public static final String BASE = """
            WITH c AS (
              SELECT customer_phone_snapshot AS phone, max(customer_name_snapshot) AS name,
                     count(*) FILTER (WHERE status NOT IN ('CANCELLED','PENDING')) AS orders,
                     count(*) FILTER (WHERE status = 'ARRIVED') AS delivered,
                     coalesce(sum(total_minor) FILTER (WHERE status NOT IN ('CANCELLED','RETURNED','REFUNDED','PENDING')), 0) AS spent,
                     max(created_at) FILTER (WHERE status NOT IN ('CANCELLED','PENDING')) AS last_order,
                     max(whatsapp_number) FILTER (WHERE whatsapp_opt_in_at IS NOT NULL) AS wa,
                     (array_agg(user_id) FILTER (WHERE user_id IS NOT NULL))[1] AS uid,
                     (SELECT cu.gender FROM commerce.customers cu WHERE cu.tenant_id = :t AND cu.phone = customer_phone_snapshot LIMIT 1) AS gender,
                     (SELECT cu.broadcast_opt_out_at IS NOT NULL FROM commerce.customers cu WHERE cu.tenant_id = :t AND cu.phone = customer_phone_snapshot LIMIT 1) AS opted_out
              FROM commerce.orders WHERE tenant_id = :t GROUP BY customer_phone_snapshot
            ), s AS (
              SELECT c.*, CASE
                WHEN last_order IS NULL THEN 'LAPSED'
                WHEN last_order < now() - interval '60 days' THEN 'LAPSED'
                WHEN delivered >= 3 THEN 'VIP'
                WHEN last_order < now() - interval '30 days' THEN 'AT_RISK'
                WHEN orders >= 2 THEN 'REPEAT'
                ELSE 'NEW' END AS segment
              FROM c WHERE orders > 0)
            """;
}
