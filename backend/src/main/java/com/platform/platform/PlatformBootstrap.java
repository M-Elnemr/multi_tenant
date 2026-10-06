package com.platform.platform;

import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
import com.platform.shared.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first PLATFORM_OWNER from PLATFORM_ADMIN_PHONE / PLATFORM_ADMIN_PASSWORD (env, never committed) when no
 * platform owner exists yet. Nothing happens if the variables are unset.
 */
@Component
public class PlatformBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PlatformBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcClient jdbc;
    private final String phone;
    private final String password;

    public PlatformBootstrap(UserRepository users, PasswordEncoder encoder, JdbcClient jdbc,
                             @Value("${app.platform.admin-phone:}") String phone, @Value("${app.platform.admin-password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.phone = phone;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (phone.isBlank() || password.isBlank()) return;
        if (password.length() < 12) throw new IllegalStateException("PLATFORM_ADMIN_PASSWORD must be at least 12 characters");
        Long owners = jdbc.sql("SELECT count(*) FROM core.user_platform_roles ur JOIN core.roles r ON r.id = ur.role_id WHERE r.code = 'PLATFORM_OWNER'").query(Long.class).single();
        if (owners > 0) return;
        String normalized = PhoneNormalizer.normalize(phone);
        User u = users.findByPhone(normalized).orElseGet(() -> {
            User n = new User();
            n.setPhone(normalized);
            n.setFirstName("Platform");
            n.setLastName("Owner");
            n.setPasswordHash(encoder.encode(password));
            n.setStatus(User.Status.ACTIVE);
            return users.saveAndFlush(n);
        });
        jdbc.sql("INSERT INTO core.user_platform_roles (user_id, role_id) SELECT :u, id FROM core.roles WHERE code = 'PLATFORM_OWNER' AND tenant_id IS NULL ON CONFLICT DO NOTHING").param("u", u.getId()).update();
        log.info("Bootstrapped platform owner account for {}", normalized);
    }
}
