package edu.learnsphere.search;

/** Validation and L2 normalization of embeddings before they reach the index or a query. */
final class Vectors {

    /** Vectors whose length is this close to 1 are used as they are. */
    private static final double UNIT_TOLERANCE = 1e-4;

    private Vectors() {
    }

    /**
     * {@code vector} scaled to unit length (the same array when it already is).
     *
     * @throws IllegalArgumentException when it has the wrong length, a non-finite value, or only zeros
     */
    static float[] unit(float[] vector, int dimensions) {
        String problem = problem(vector, dimensions);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return normalize(vector);
    }

    /** Like {@link #unit} but returns null instead of throwing. */
    static float[] unitOrNull(float[] vector, int dimensions) {
        return problem(vector, dimensions) == null ? normalize(vector) : null;
    }

    /** What is wrong with {@code vector}, or null when it can be indexed. */
    static String problem(float[] vector, int dimensions) {
        if (vector == null) {
            return "Embedding is missing";
        }
        if (vector.length != dimensions) {
            return "Embedding has " + vector.length + " values, the index needs " + dimensions;
        }
        double sumOfSquares = 0;
        for (float x : vector) {
            if (!Float.isFinite(x)) {
                return "Embedding has a value that is not a finite number";
            }
            sumOfSquares += (double) x * x;
        }
        if (sumOfSquares == 0) {
            return "Embedding has only zeros";
        }
        return null;
    }

    private static float[] normalize(float[] vector) {
        double sumOfSquares = 0;
        for (float x : vector) {
            sumOfSquares += (double) x * x;
        }
        double norm = Math.sqrt(sumOfSquares);
        if (Math.abs(norm - 1) <= UNIT_TOLERANCE) {
            return vector;
        }
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = (float) (vector[i] / norm);
        }
        return unit;
    }
}
