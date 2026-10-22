package com.platform.integrations.carriers;

import java.util.Map;

/** Egypt's governorates by the code the shop stores, with the English names carriers expect as the city. */
public final class Governorates {
    private Governorates() {}

    private static final Map<String, String> EN = Map.ofEntries(Map.entry("CAI", "Cairo"), Map.entry("GIZ", "Giza"), Map.entry("ALX", "Alexandria"), Map.entry("QLY", "Qalyubia"), Map.entry("SHR", "Sharqia"), Map.entry("DKH", "Dakahlia"),
            Map.entry("GHR", "Gharbia"), Map.entry("MNF", "Monufia"), Map.entry("BHR", "Beheira"), Map.entry("KFS", "Kafr El Sheikh"), Map.entry("DMT", "Damietta"), Map.entry("PSD", "Port Said"), Map.entry("ISM", "Ismailia"),
            Map.entry("SUZ", "Suez"), Map.entry("FYM", "Faiyum"), Map.entry("BNS", "Beni Suef"), Map.entry("MNY", "Minya"), Map.entry("AST", "Asyut"), Map.entry("SHG", "Sohag"), Map.entry("QNA", "Qena"), Map.entry("LXR", "Luxor"),
            Map.entry("ASN", "Aswan"), Map.entry("RSS", "Red Sea"), Map.entry("WAD", "New Valley"), Map.entry("MTR", "Matrouh"), Map.entry("NSN", "North Sinai"), Map.entry("SSN", "South Sinai"));

    public static String english(String code, String fallback) { return code == null ? fallback : EN.getOrDefault(code.toUpperCase(), fallback); }
}
