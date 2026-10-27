package edu.learnsphere.core.service;

import java.util.Objects;

/** How the class does on an item. */
public record ItemInsight(String itemId, String prompt, int attempts, Double accuracy, int learners) {

    public ItemInsight {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(prompt, "prompt");
    }
}
