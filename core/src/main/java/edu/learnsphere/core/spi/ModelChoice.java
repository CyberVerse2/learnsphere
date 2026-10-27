package edu.learnsphere.core.spi;

import edu.learnsphere.core.model.CodedEnum;

/**
 * Which chat model the study assistant uses. Stored in settings as its {@link #code()}; {@link #AUTO}
 * picks the larger model only on machines with 16 GB of memory or more where its files exist.
 */
public enum ModelChoice implements CodedEnum {

    /** Choose by hardware. */
    AUTO("auto", null),
    /** The 4B model (faster, smaller). */
    CHAT_4B("chat-4b", "chat-4b"),
    /** The 9B model (more thorough, needs more memory). */
    CHAT_9B("chat-9b", "chat-9b");

    private final String code;
    private final String folder;

    ModelChoice(String code, String folder) {
        this.code = code;
        this.folder = folder;
    }

    @Override
    public String code() {
        return code;
    }

    /** The folder under {@code runtime/models/} holding this model, or null for {@link #AUTO}. */
    public String folder() {
        return folder;
    }

    public static ModelChoice fromCode(String code) {
        return CodedEnum.fromCode(ModelChoice.class, code);
    }
}
