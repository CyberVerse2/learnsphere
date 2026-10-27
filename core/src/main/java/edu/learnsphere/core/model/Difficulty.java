package edu.learnsphere.core.model;

/** Intended difficulty of a learning item. Stored as {@link #value()} 1–3. */
public enum Difficulty {

    /** Recall of a single fact or definition. */
    EASY(1),
    /** Understanding or connecting two ideas. */
    MEDIUM(2),
    /** Applying or reasoning across ideas. */
    HARD(3);

    private final int value;

    Difficulty(int value) {
        this.value = value;
    }

    /** The stored value, 1 to 3. */
    public int value() {
        return value;
    }

    /**
     * The difficulty stored as {@code value}.
     *
     * @throws IllegalArgumentException outside 1–3
     */
    public static Difficulty fromValue(int value) {
        for (Difficulty difficulty : values()) {
            if (difficulty.value == value) {
                return difficulty;
            }
        }
        throw new IllegalArgumentException("Difficulty must be 1-3: " + value);
    }
}
