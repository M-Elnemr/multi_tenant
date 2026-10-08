package com.platform.commerce;

import com.platform.core.auth.AuthService;
import com.platform.core.auth.TokenResponse;
import com.platform.core.user.User;
import com.platform.core.user.UserRepository;
import com.platform.shared.BusinessException;
import com.platform.shared.PhoneNormalizer;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Shopper accounts: open registration on a store, and "same account, many stores" via auto-join on first login. */
@Service
public class StoreCustomerService {
    private final UserRepository users;
        private final StoreMembershipService membership;
    private final PasswordEncoder encoder;
    private final AuthService auth;
    
    public StoreCustomerService(UserRepository users, StoreMembershipService membership, PasswordEncoder encoder, AuthService auth) {
        this.users = users;
        this.membership = membership;
        this.encoder = encoder;
        this.auth = auth;
    }

    public UUID ensureCustomer(UUID tenantId, UUID userId) { return membership.ensureCustomer(tenantId, userId); }

    @Transactional
    public TokenResponse register(UUID tenantId, String firstName, String lastName, String phoneRaw, String email, String password, String ip, String ua) {
        auth.validatePassword(password);
        String phone = PhoneNormalizer.normalize(phoneRaw);
        if (users.findByPhone(phone).isPresent())
            throw BusinessException.conflict("ACCOUNT_EXISTS", "This phone number already has an account - log in with its password");
        String mail = com.platform.shared.PhoneNormalizer.cleanEmail(email);
        if (mail != null && users.findByEmailIgnoreCase(mail).isPresent())
            throw BusinessException.conflict("ACCOUNT_EXISTS", "This email already has an account - log in with its password");
        User u = new User();
        u.setPhone(phone);
        u.setEmail(mail);
        u.setFirstName(firstName);
        u.setLastName(lastName == null ? "" : lastName);
        u.setPasswordHash(encoder.encode(password));
        u.setStatus(User.Status.ACTIVE);
        u = users.saveAndFlush(u);
        membership.join(u.getId(), tenantId);
        return auth.issueFor(u, ip, ua);
    }
}
