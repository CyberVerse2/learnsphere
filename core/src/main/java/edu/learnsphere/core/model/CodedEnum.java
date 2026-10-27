package edu.learnsphere.core.model;

import java.util.Locale;

/**
 * An enum stored in the database as a lowercase text code (for example {@code needs_review}). The code
 * is part of the schema: changing one needs a migration.
 */
public interface CodedEnum {

    /** The lowercase code stored in the database and in exported files. */
    String code();

    /**
     * Looks up the constant with {@code code}.
     *
     * @throws IllegalArgumentException when no constant has that code
     */
    static <E extends Enum<E> & CodedEnum> E fromCode(Class<E> type, String code) {
        if (code != null) {
            for (E constant : type.getEnumConstants()) {
                if (constant.code().equals(code)) {
                    return constant;
                }
            }
        }
        throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " code: " + code);
    }

    /** {@link #fromCode} for a nullable column: null stays null. */
    static <E extends Enum<E> & CodedEnum> E fromNullableCode(Class<E> type, String code) {
        return code == null ? null : fromCode(type, code);
    }

    /** The default code for a constant: its name in lowercase. */
    static String codeOf(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }
}
