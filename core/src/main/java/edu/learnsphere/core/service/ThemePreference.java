package edu.learnsphere.core.service;

import edu.learnsphere.core.model.CodedEnum;

/** Light or dark appearance. Stored as its lowercase code. */
public enum ThemePreference implements CodedEnum {
    /** Follow the system appearance. */
    SYSTEM,
    LIGHT,
    DARK;

    private final String code = CodedEnum.codeOf(this);

    @Override
    public String code() {
        return code;
    }

    public static ThemePreference fromCode(String code) {
        return CodedEnum.fromCode(ThemePreference.class, code);
    }
}
