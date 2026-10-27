package edu.learnsphere.core.util;

import java.util.UUID;
import java.util.regex.Pattern;

/** Identifiers are random UUID strings in canonical lowercase form. */
public final class Ids {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private Ids() {
    }

    /** A new random identifier. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** True when {@code id} is a canonical lowercase UUID string. */
    public static boolean isValid(String id) {
        return id != null && UUID_PATTERN.matcher(id).matches();
    }

    /**
     * Returns {@code id} when it is valid.
     *
     * @throws IllegalArgumentException when it is not a canonical UUID string
     */
    public static String requireValid(String id) {
        if (!isValid(id)) {
            throw new IllegalArgumentException("Not a valid id: " + id);
        }
        return id;
    }
}
