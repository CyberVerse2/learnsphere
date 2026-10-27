package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.ConceptExtractor;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Names the most frequent content words of the passages as concepts (up to {@link #MAX_CONCEPTS}, most
 * frequent first, ties alphabetical), each described by the first sentence that mentions it.
 */
public final class FakeConceptExtractor implements ConceptExtractor {

    public static final int MAX_CONCEPTS = 5;

    @Override
    public List<ConceptDraft> extract(String moduleTitle, List<Passage> passages, CancellationToken token) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Passage passage : passages) {
            token.throwIfCancelled();
            for (String word : FakeText.words(passage.text())) {
                if (FakeText.isContentWord(word) && word.length() >= 4) {
                    counts.merge(word, 1, Integer::sum);
                }
            }
        }
        List<String> top = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_CONCEPTS)
                .map(Map.Entry::getKey)
                .toList();
        List<ConceptDraft> drafts = new ArrayList<>();
        for (String word : top) {
            drafts.add(new ConceptDraft(FakeText.capitalize(word), describe(word, passages)));
        }
        return drafts;
    }

    private static String describe(String word, List<Passage> passages) {
        for (Passage passage : passages) {
            for (String sentence : FakeText.sentences(passage.text())) {
                if (FakeText.words(sentence).contains(word)) {
                    return FakeText.endWithPeriod(sentence);
                }
            }
        }
        return "";
    }
}
