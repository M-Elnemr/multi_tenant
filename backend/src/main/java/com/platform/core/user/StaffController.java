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

    public record InviteRequest(@NotBlank String firstName, String lastName, @NotBlank String phone, String email, @NotBlank String role) {}

    /** Creates the member and returns the one-time activation PIN to hand to them (shown only once). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffService.Invited invite(@Valid @RequestBody InviteRequest r, Authentication a) {
        return staff.invite((UUID) a.getPrincipal(), r.firstName(), r.lastName(), r.phone(), r.email(), r.role());
    }

    @PostMapping("/{userId}/activation-pin")
    public Map<String, String> reissue(@PathVariable UUID userId, Authentication a) {
        TenantContext.require();
        return Map.of("activationPin", staff.reissuePin((UUID) a.getPrincipal(), userId));
    }
}
