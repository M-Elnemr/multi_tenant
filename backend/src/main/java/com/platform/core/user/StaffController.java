package com.platform.core.user;

import com.platform.shared.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenant/members")
@PreAuthorize("hasAuthority('staff.manage')")
public class StaffController {
    private final StaffService staff;

    public StaffController(StaffService staff) { this.staff = staff; }

    public record InviteRequest(@NotBlank String firstName, String lastName, @NotBlank String phone, String email, String role, Map<String, String> access) {}

    public record AccessRequest(Map<String, String> access) {}

    @org.springframework.web.bind.annotation.GetMapping
    public java.util.List<Map<String, Object>> list() { return staff.list(TenantContext.require().id()); }

    @org.springframework.web.bind.annotation.DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID userId, Authentication a) { staff.remove((UUID) a.getPrincipal(), userId); }

    /** Creates the member and returns the one-time activation PIN to hand to them (shown only once). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffService.Invited invite(@Valid @RequestBody InviteRequest r, Authentication a) {
        return staff.invite((UUID) a.getPrincipal(), r.firstName(), r.lastName(), r.phone(), r.email(), r.role(), r.access());
    }

    @org.springframework.web.bind.annotation.PutMapping("/{userId}/access")
    public Map<String, String> setAccess(@PathVariable UUID userId, @RequestBody AccessRequest r, Authentication a) {
        return staff.setAccess((UUID) a.getPrincipal(), userId, r.access());
    }

    /** The screens an owner can allow for this business, and whether each can be written (reports are read only). */
    @org.springframework.web.bind.annotation.GetMapping("/screens")
    public java.util.List<Map<String, Object>> screens() {
        return com.platform.core.rbac.ScreenCatalog.screens(TenantContext.require().type()).stream()
                .map(s -> Map.<String, Object>of("key", s.key(), "writable", !s.write().isEmpty())).toList();
    }

    @PostMapping("/{userId}/activation-pin")
    public Map<String, String> reissue(@PathVariable UUID userId, Authentication a) {
        TenantContext.require();
        return Map.of("activationPin", staff.reissuePin((UUID) a.getPrincipal(), userId));
    }
}
