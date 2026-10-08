package com.platform.core.onboarding;

import com.platform.shared.BusinessException;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * What the owner told us about the business at sign-up: one or more categories (a clinic's specialties, a shop's product categories) picked from the
 * platform list, or "other" with a name typed by the owner.
 */
public record ProvisionOptions(List<String> categories, String otherCategory) {
    public static final String OTHER = "other";

    public static ProvisionOptions none() { return new ProvisionOptions(List.of(), null); }

    /** Distinct codes, at least one, at most five; "other" needs the typed name. Throws a 400 otherwise. */
    public ProvisionOptions validated(String what) {
        List<String> codes = categories == null ? List.of() : List.copyOf(new LinkedHashSet<>(categories.stream().filter(c -> c != null && !c.isBlank()).map(c -> c.trim().toLowerCase()).toList()));
        if (codes.isEmpty()) throw BusinessException.badRequest("CATEGORY_REQUIRED", "Please choose " + what);
        if (codes.size() > 5) throw BusinessException.badRequest("TOO_MANY_CATEGORIES", "Choose up to 5");
        String other = otherCategory == null ? null : otherCategory.trim();
        if (codes.contains(OTHER)) {
            if (other == null || other.length() < 2) throw BusinessException.badRequest("OTHER_CATEGORY_REQUIRED", "Please type the name for \"Other\"");
            if (other.length() > 100) throw BusinessException.badRequest("VALIDATION_ERROR", "The name for \"Other\" is too long");
        } else {
            other = null;
        }
        return new ProvisionOptions(codes, other);
    }
}
