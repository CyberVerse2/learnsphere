package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/**
 * Drafts quiz questions and flashcards from passages as structured output (ai module,
 * {@link ChatPriority#USER_WAITING}). Every draft must be valid for its type; invalid model output is
 * dropped, so fewer than {@code count} drafts may come back.
 */
public interface ItemGenerator {

    List<ItemDraft> generate(ItemGenerationRequest request, CancellationToken token);
}
