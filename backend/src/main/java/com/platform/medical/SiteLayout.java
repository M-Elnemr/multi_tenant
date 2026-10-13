package com.platform.medical;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** The owner's choice of which sections of the public site show, and in what order. Only known section keys are ever stored. */
public final class SiteLayout {
    public static final Set<String> CLINIC = Set.of("hero", "about", "services", "doctors", "how", "visit", "gallery", "insurance", "faq", "contact");
    public static final Set<String> STORE = Set.of("banners", "trust", "categories", "featured", "offers", "bestSellers", "newest", "whatsapp", "branches");

    private SiteLayout() {}

    public static Map<String, Object> clean(Map<?, ?> in, Set<String> allowed) {
        return Map.of("order", keys(in.get("order"), allowed), "hidden", keys(in.get("hidden"), allowed));
    }

    private static List<String> keys(Object o, Set<String> allowed) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().map(String::valueOf).filter(allowed::contains).distinct().toList();
    }
}
