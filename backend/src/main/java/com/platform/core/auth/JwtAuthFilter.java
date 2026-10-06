package com.platform.core.auth;

import com.platform.core.rbac.RbacService;
import com.platform.core.user.Membership;
import com.platform.core.user.MembershipRepository;
import com.platform.shared.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a Bearer token and derives authorities from the user's membership in the tenant
 * resolved from the Host (never from the client): a user only has the permissions their roles in
 * THIS tenant grant, so a token from tenant A is powerless on tenant B's domain.
 */
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final MembershipRepository memberships;
    private final RbacService rbac;

    public JwtAuthFilter(JwtService jwt, MembershipRepository memberships, RbacService rbac) {
        this.jwt = jwt;
        this.memberships = memberships;
        this.rbac = rbac;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            jwt.parse(h.substring(7)).ifPresent(userId -> authenticate(userId));
        }
        chain.doFilter(req, res);
    }

    private void authenticate(UUID userId) {
        Set<GrantedAuthority> auths = new HashSet<>();
        for (String p : rbac.platformPermissionCodes(userId)) auths.add(new SimpleGrantedAuthority(p));
        TenantContext.Current t = TenantContext.get();
        if (t != null) {
            memberships.findByUserIdAndTenantId(userId, t.id())
                    .filter(m -> m.getStatus() == Membership.Status.ACTIVE)
                    .ifPresent(m -> {
                        for (String p : rbac.permissionCodes(m.getId())) auths.add(new SimpleGrantedAuthority(p));
                        for (String r : rbac.roleCodes(m.getId())) auths.add(new SimpleGrantedAuthority("ROLE_" + r));
                    });
        }
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.copyOf(auths)));
    }
}
