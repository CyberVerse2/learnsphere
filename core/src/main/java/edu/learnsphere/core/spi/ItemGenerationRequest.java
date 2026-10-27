package edu.learnsphere.core.spi;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import java.util.List;
import java.util.Set;

/**
 * Input for {@link ItemGenerator}.
 *
 * @param passages     numbered evidence
 * @param conceptNames concepts the items should test (may be empty)
 * @param types        item types wanted
 * @param count        how many drafts are wanted at most
 * @param difficulty   wanted difficulty, or null for a mix
 */
public record ItemGenerationRequest(List<Passage> passages, List<String> conceptNames, Set<ItemType> types, int count,
                                    Difficulty difficulty) {

    public ItemGenerationRequest {
        passages = List.copyOf(passages);
        conceptNames = conceptNames == null ? List.of() : List.copyOf(conceptNames);
        types = types == null || types.isEmpty() ? Set.of(ItemType.values()) : Set.copyOf(types);
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1: " + count);
        }
    }
}
