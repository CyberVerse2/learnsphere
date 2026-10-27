package edu.learnsphere.core.model;

/** How sure the learner was before seeing the result. Stored as {@link #value()} 1–3. */
public enum Confidence {

    /** "Guessing". */
    GUESSING(1),
    /** "Fairly sure". */
    FAIRLY_SURE(2),
    /** "Certain". */
    CERTAIN(3);

    private final int value;

    Confidence(int value) {
        this.value = value;
    }

    /** The stored value, 1 to 3. */
    public int value() {
        return value;
    }

    /**
     * The confidence stored as {@code value}.
     *
     * @throws IllegalArgumentException outside 1–3
     */
    public static Confidence fromValue(int value) {
        for (Confidence confidence : values()) {
            if (confidence.value == value) {
                return confidence;
            }
        }
        throw new IllegalArgumentException("Confidence must be 1-3: " + value);
    }
}
