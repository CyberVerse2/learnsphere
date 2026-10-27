package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemType;
import java.util.Objects;

/** A flag with the item it is about, for the Studio's feedback list. */
public record FlagSummary(Flag flag, String itemId, ItemType itemType, String itemPrompt, String moduleTitle) {

    public FlagSummary {
        Objects.requireNonNull(flag, "flag");
        Objects.requireNonNull(itemId, "itemId");
    }
}
