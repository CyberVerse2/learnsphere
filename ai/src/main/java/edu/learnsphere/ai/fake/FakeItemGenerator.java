package edu.learnsphere.ai.fake;

import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds {@code count} valid drafts from the passages, cycling through the requested types in
 * declaration order and through the passages in order, so a request for every type with
 * {@code count = 5} yields one draft of each type. Returns nothing when there are no passages.
 */
public final class FakeItemGenerator implements ItemGenerator {

    @Override
    public List<ItemDraft> generate(ItemGenerationRequest request, CancellationToken token) {
        List<ItemDraft> drafts = new ArrayList<>();
        if (request.passages().isEmpty()) {
            return drafts;
        }
        List<ItemType> types = request.types().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        Difficulty difficulty = request.difficulty() == null ? Difficulty.MEDIUM : request.difficulty();
        List<String> concepts = request.conceptNames().isEmpty() ? List.of() : List.of(request.conceptNames().getFirst());
        for (int i = 0; i < request.count(); i++) {
            token.throwIfCancelled();
            ItemType type = types.get(i % types.size());
            Passage passage = request.passages().get(i % request.passages().size());
            drafts.add(draft(type, passage, difficulty, concepts));
        }
        return drafts;
    }

    private static ItemDraft draft(ItemType type, Passage passage, Difficulty difficulty, List<String> concepts) {
        String fact = FakeText.firstSentence(passage.text());
        String key = keyWord(fact);
        String explanation = "From " + passage.pageLabel() + ": " + fact;
        int n = passage.number();
        return switch (type) {
            case MCQ -> new ItemDraft(type, "Which statement matches your material about " + key + "?", fact,
                    explanation, List.of(negate(fact), "None of the material mentions " + key + ".",
                            "The material describes " + key + " only as an example."),
                    difficulty, n, concepts);
            case TRUE_FALSE -> new ItemDraft(type, fact, "true", explanation, List.of(), difficulty, n, concepts);
            case SHORT_ANSWER -> new ItemDraft(type, "In your own words, what does the material say about " + key + "?",
                    fact, explanation, List.of(), difficulty, n, concepts);
            case FLASHCARD -> new ItemDraft(type, FakeText.capitalize(key), fact, explanation, List.of(), difficulty, n,
                    concepts);
            case CLOZE -> new ItemDraft(type, blankOut(fact, key), key, explanation, List.of(), difficulty, n, concepts);
        };
    }

    /** The longest content word of the sentence (the first one on a tie), or "this topic". */
    static String keyWord(String sentence) {
        String best = null;
        for (String word : FakeText.words(sentence)) {
            if (FakeText.isContentWord(word) && (best == null || word.length() > best.length())) {
                best = word;
            }
        }
        return best == null ? "this topic" : best;
    }

    private static String blankOut(String sentence, String key) {
        int at = sentence.toLowerCase(Locale.ROOT).indexOf(key);
        if (at < 0) {
            return ItemType.CLOZE_BLANK + " — " + sentence;
        }
        return sentence.substring(0, at) + ItemType.CLOZE_BLANK + sentence.substring(at + key.length());
    }

    private static String negate(String sentence) {
        String body = sentence.substring(0, sentence.length() - 1);
        return "It is not true that " + Character.toLowerCase(body.charAt(0)) + body.substring(1) + ".";
    }
}
