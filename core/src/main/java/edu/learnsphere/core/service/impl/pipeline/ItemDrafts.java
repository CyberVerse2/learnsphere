package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.Passage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks drafts from the item generator against the {@link ItemType} content conventions and ties each one
 * to the passage it cites. Pure and thread-safe.
 */
final class ItemDrafts {

    private static final Logger LOG = LoggerFactory.getLogger(ItemDrafts.class);

    static final int MIN_DISTRACTORS = 2;
    static final int MAX_DISTRACTORS = 4;

    private ItemDrafts() {
    }

    /**
     * A valid draft with its source.
     *
     * @param passage the passage it cites
     * @param chunk   the stored passage behind {@code passage}
     */
    record Candidate(ItemType type, String prompt, String answer, String explanation, List<String> distractors,
                     Difficulty difficulty, List<String> conceptNames, Passage passage, Chunk chunk) {

        Candidate {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(passage, "passage");
            Objects.requireNonNull(chunk, "chunk");
            distractors = List.copyOf(distractors);
            conceptNames = List.copyOf(conceptNames);
        }
    }

    /**
     * The drafts that follow their type's rules, are of a wanted type and cite one of {@code passages},
     * cleaned up (trimmed, true/false answers lower-cased, duplicate or blank options removed), in order.
     *
     * @param chunks the stored passages, in the same order as {@code passages}
     */
    static List<Candidate> candidates(List<ItemDraft> drafts, List<Passage> passages, List<Chunk> chunks,
                                      Set<ItemType> types) {
        if (passages.size() != chunks.size()) {
            throw new IllegalArgumentException("Every passage needs its chunk");
        }
        List<Candidate> result = new ArrayList<>();
        int dropped = 0;
        for (ItemDraft draft : drafts == null ? List.<ItemDraft>of() : drafts) {
            Candidate candidate = draft == null ? null : candidate(draft, passages, chunks, types);
            if (candidate == null) {
                dropped++;
            } else {
                result.add(candidate);
            }
        }
        if (dropped > 0) {
            LOG.debug("Dropped {} of {} drafts that broke their type's rules", dropped, result.size() + dropped);
        }
        return result;
    }

    private static Candidate candidate(ItemDraft draft, List<Passage> passages, List<Chunk> chunks, Set<ItemType> types) {
        if (!types.contains(draft.type())) {
            return null;
        }
        int number = draft.passageNumber();
        if (number < 1 || number > passages.size()) {
            return null;
        }
        String prompt = draft.prompt().strip();
        String answer = draft.answer().strip();
        if (prompt.isEmpty() || answer.isEmpty()) {
            return null;
        }
        List<String> distractors = List.of();
        switch (draft.type()) {
            case MCQ -> {
                distractors = distractors(draft.distractors(), answer);
                if (distractors.size() < MIN_DISTRACTORS) {
                    return null;
                }
            }
            case TRUE_FALSE -> {
                answer = answer.toLowerCase(Locale.ROOT);
                if (!answer.equals("true") && !answer.equals("false")) {
                    return null;
                }
            }
            case CLOZE -> {
                int first = prompt.indexOf(ItemType.CLOZE_BLANK);
                if (first < 0 || prompt.indexOf(ItemType.CLOZE_BLANK, first + ItemType.CLOZE_BLANK.length()) >= 0) {
                    return null;
                }
            }
            case SHORT_ANSWER, FLASHCARD -> {
                // Any non-blank prompt and answer.
            }
        }
        List<String> concepts = draft.conceptNames().stream().filter(Objects::nonNull).map(String::strip)
                .filter(name -> !name.isEmpty()).toList();
        return new Candidate(draft.type(), prompt, answer, draft.explanation().strip(), distractors, draft.difficulty(),
                concepts, passages.get(number - 1), chunks.get(number - 1));
    }

    /** Trimmed, distinct (ignoring case) wrong options other than the answer, at most {@value #MAX_DISTRACTORS}. */
    private static List<String> distractors(List<String> options, String answer) {
        Map<String, String> unique = new LinkedHashMap<>();
        String answerKey = answer.toLowerCase(Locale.ROOT);
        for (String option : options) {
            String cleaned = option == null ? "" : option.strip();
            String key = cleaned.toLowerCase(Locale.ROOT);
            if (!cleaned.isEmpty() && !key.equals(answerKey)) {
                unique.putIfAbsent(key, cleaned);
            }
            if (unique.size() == MAX_DISTRACTORS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }
}
