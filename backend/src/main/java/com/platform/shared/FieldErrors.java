package com.platform.shared;

import java.util.LinkedHashMap;
import java.util.Map;

/** Collects every invalid field of a form so one answer lists them all (each shown under its own field), instead of stopping at the first. */
public final class FieldErrors {
    private final Map<String, String> errors = new LinkedHashMap<>();
    private String firstCode;

    public FieldErrors add(String field, String message) { return add(field, "VALIDATION_ERROR", message); }

    public FieldErrors add(String field, String code, String message) {
        errors.putIfAbsent(field, message);
        if (firstCode == null) firstCode = code;
        return this;
    }

    public FieldErrors when(boolean bad, String field, String message) { return bad ? add(field, message) : this; }

    public boolean isEmpty() { return errors.isEmpty(); }

    /** 400 VALIDATION_ERROR (or the first error's own code when there is only one) with a `fields` map. */
    public void throwIfAny() {
        if (errors.isEmpty()) return;
        String code = errors.size() == 1 && firstCode != null ? firstCode : "VALIDATION_ERROR";
        String message = errors.size() == 1 ? errors.values().iterator().next() : "One or more fields are invalid";
        throw BusinessException.badRequest(code, message).fields(errors);
    }
}
