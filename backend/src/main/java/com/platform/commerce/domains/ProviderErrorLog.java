package com.platform.commerce.domains;

import com.platform.shared.Rows;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Recent {@link ProviderException} occurrences, for the admin troubleshooting screen ({@code GET /provider-errors}). */
@Service
public class ProviderErrorLog {
    private final JdbcClient jdbc;

    public ProviderErrorLog(JdbcClient jdbc) { this.jdbc = jdbc; }

    public void record(UUID orderId, String domain, String operation, ProviderException e) {
        jdbc.sql("INSERT INTO commerce.domain_provider_errors (order_id, domain, operation, code, message, retryable) VALUES (:o, :d, :op, :c, :m, :r)")
                .param("o", orderId).param("d", domain).param("op", operation).param("c", e.code().name()).param("m", clip(e.getMessage())).param("r", e.retryable()).update();
    }

    public List<Map<String, Object>> recent(int limit) {
        return Rows.camel(jdbc.sql("SELECT id, order_id, domain, operation, code, message, retryable, created_at FROM commerce.domain_provider_errors ORDER BY id DESC LIMIT :l")
                .param("l", Math.min(Math.max(limit, 1), 200)).query().listOfRows());
    }

    private static String clip(String s) { return s == null ? null : s.length() > 480 ? s.substring(0, 480) : s; }
}
