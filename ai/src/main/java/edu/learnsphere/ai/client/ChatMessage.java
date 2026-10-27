package edu.learnsphere.ai.client;

import java.util.List;
import java.util.Objects;

/**
 * One message of a chat request: a role and its content parts (text, and images for the user).
 */
public record ChatMessage(Role role, List<Part> parts) {

    /** Who wrote a message. */
    public enum Role {
        SYSTEM, USER, ASSISTANT;

        /** The role name in the OpenAI-compatible API. */
        public String apiName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** A piece of message content. */
    public sealed interface Part permits Text, Image {
    }

    /** Text content. */
    public record Text(String text) implements Part {
        public Text {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * Image content, sent as a base64 data URL.
     *
     * @param mediaType for example {@code image/png}
     */
    public record Image(byte[] bytes, String mediaType) implements Part {
        public Image {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(mediaType, "mediaType");
            if (bytes.length == 0) {
                throw new IllegalArgumentException("An image needs bytes");
            }
        }

        /** A PNG image. */
        public static Image png(byte[] bytes) {
            return new Image(bytes, "image/png");
        }

        @Override
        public String toString() {
            return "Image[" + mediaType + ", " + bytes.length + " bytes]";
        }
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        parts = List.copyOf(parts);
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("A message needs content");
        }
    }

    /** A system message. */
    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, List.of(new Text(text)));
    }

    /** A user message. */
    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new Text(text)));
    }

    /** A user message with an image followed by text. */
    public static ChatMessage userWithImage(Image image, String text) {
        return new ChatMessage(Role.USER, List.of(image, new Text(text)));
    }

    /** An assistant message (for few-shot examples). */
    public static ChatMessage assistant(String text) {
        return new ChatMessage(Role.ASSISTANT, List.of(new Text(text)));
    }

    /** True when the message carries an image. */
    public boolean hasImage() {
        return parts.stream().anyMatch(Image.class::isInstance);
    }
}
