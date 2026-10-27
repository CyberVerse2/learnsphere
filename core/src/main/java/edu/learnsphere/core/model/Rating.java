package edu.learnsphere.core.model;

/** How well an item was recalled; drives FSRS scheduling. Stored as {@link #value()} 1–4. */
public enum Rating {

    /** Forgotten or wrong. */
    AGAIN(1),
    /** Recalled with serious difficulty, low confidence or a hint. */
    HARD(2),
    /** Recalled with some effort. */
    GOOD(3),
    /** Recalled easily. */
    EASY(4);

    private final int value;

    Rating(int value) {
        this.value = value;
    }

    /** The stored value, 1 (again) to 4 (easy). */
    public int value() {
        return value;
    }

    /** True for every rating except {@link #AGAIN}. */
    public boolean isPass() {
        return this != AGAIN;
    }

    /**
     * The rating stored as {@code value}.
     *
     * @throws IllegalArgumentException outside 1–4
     */
    public static Rating fromValue(int value) {
        for (Rating rating : values()) {
            if (rating.value == value) {
                return rating;
            }
        }
        throw new IllegalArgumentException("Rating must be 1-4: " + value);
    }
}
