package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ItemGenerator} on the chat model at {@link ChatPriority#USER_WAITING}.
 *
 * <p>The request becomes a <em>plan</em>: one slot per wanted item, cycling through the wanted types,
 * through easy/medium/hard (unless one difficulty is asked for) independently of type, through the passages
 * and the concepts, and alternating true and false statements. Slots are written {@value #BATCH} at a time;
 * each batch's JSON schema is a tuple with one entry per slot whose type, difficulty and true/false answer
 * are fixed, so the reply always matches the plan's shape. Drafts are then checked against the
 * {@link ItemType} conventions and the quality rules (no references to "the passage", no echoed
 * instructions, distinct plausible distractors, exactly one cloze blank that hides the answer, no
 * duplicates); failed slots are written once more, so fewer than {@code count} drafts may come back.
 * Thread-safe.
 */
public final class LlamaItemGenerator implements ItemGenerator {

    private static final Logger LOG = LoggerFactory.getLogger(LlamaItemGenerator.class);

    /** Items per request. */
    public static final int BATCH = 5;
    /** Passage text budget per prompt, in characters (about 8,000 tokens). */
    public static final int PASSAGE_BUDGET = 32_000;
    /** Questions listed as "already written" in later batches. */
    static final int AVOID_LIMIT = 20;

    private static final Difficulty[] LADDER = {Difficulty.MEDIUM, Difficulty.EASY, Difficulty.HARD};
    private static final Pattern SOURCE_REFERENCE = Pattern.compile(
            "(?i)\\b(passages?|the excerpt|course material|your material|the author)\\b"
                    + "|\\b(according to|in|from|based on|stated in) the (text|material|reading|document|notes)\\b"
                    + "|\\[\\d+]");
    private static final Pattern INSTRUCTION_ECHO = Pattern.compile(
            "(?i)\\b(multiple[- ]choice|true or false|true/false|fill[- ]in[- ]the[- ]blank|cloze|flash ?card"
                    + "|here (is|are) (a|an|the|some)|here's (a|an|the)"
                    + "|(write|generate|create) (a|an|one|the) (question|item|statement|quiz|flash ?card))\\b");
    private static final Pattern LEADING_META = Pattern.compile(
            "(?i)^(true or false|true/false|statement|question|front|q|fill in the blank)\\s*[:.\\-–]\\s*"
                    + "|^(according to|based on|as stated in|as described in|in) (the )?(passage|text|material|excerpt)s?"
                    + "( \\[?\\d+]?)?,?\\s*");
    private static final Pattern OPTION_LETTER = Pattern.compile("^(\\(?[A-Da-d][).:]|option [A-Da-d][).:]?)\\s+");
    private static final Pattern BARE_LETTER = Pattern.compile("(?i)^(option )?\\(?[a-d]\\)?\\.?$");
    private static final Pattern BLANKS = Pattern.compile("_{2,}|\\[blank]|\\(blank\\)");

    /**
     * One planned item.
     *
     * @param truth   for true/false: whether the statement must be true; null otherwise
     * @param passage the passage number suggested for it
     * @param concept the concept suggested for it, or null
     */
    record Slot(int index, ItemType type, Difficulty difficulty, Boolean truth, int passage, String concept) {
    }

    private final LocalChat chat;
    private final double temperature;

    public LlamaItemGenerator(LocalChat chat) {
        this(chat, 0.4);
    }

    public LlamaItemGenerator(LocalChat chat, double temperature) {
        this.chat = Objects.requireNonNull(chat, "chat");
        this.temperature = temperature;
    }

    @Override
    public List<ItemDraft> generate(ItemGenerationRequest request, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (request.passages().isEmpty()) {
            return List.of();
        }
        List<Passage> passages = PassageFormat.fit(request.passages(), PASSAGE_BUDGET);
        List<Slot> pending = plan(request, passages);
        Map<Integer, ItemDraft> accepted = new TreeMap<>();
        Set<String> keys = new HashSet<>();
        List<String> avoid = new ArrayList<>();
        for (int round = 0; round < 2 && !pending.isEmpty(); round++) {
            List<Slot> failed = new ArrayList<>();
            for (int from = 0; from < pending.size(); from += BATCH) {
                List<Slot> batch = pending.subList(from, Math.min(pending.size(), from + BATCH));
                List<JsonNode> written = write(batch, passages, request.conceptNames(), avoid, token);
                for (int i = 0; i < batch.size(); i++) {
                    Slot slot = batch.get(i);
                    Optional<ItemDraft> draft = i < written.size()
                            ? validate(written.get(i), slot, passages, request.conceptNames())
                            : Optional.empty();
                    if (draft.isPresent() && keys.add(TextCleanup.key(draft.get().prompt()))) {
                        accepted.put(slot.index(), draft.get());
                        avoid.add(draft.get().prompt());
                    } else {
                        failed.add(slot);
                    }
                }
            }
            pending = failed;
        }
        if (!pending.isEmpty()) {
            LOG.info("Item generation kept {} of {} drafts", accepted.size(), request.count());
        }
        return List.copyOf(accepted.values());
    }

    // ------------------------------------------------------------------ plan

    /** The slots for a request over the passages that fit in the prompt. */
    static List<Slot> plan(ItemGenerationRequest request, List<Passage> passages) {
        List<ItemType> types = request.types().stream().sorted(Comparator.comparingInt(Enum::ordinal)).toList();
        List<Slot> slots = new ArrayList<>(request.count());
        int trueFalse = 0;
        for (int i = 0; i < request.count(); i++) {
            ItemType type = types.get(i % types.size());
            Difficulty difficulty = request.difficulty() != null
                    ? request.difficulty()
                    : LADDER[(i + i / types.size()) % LADDER.length];
            Boolean truth = type == ItemType.TRUE_FALSE ? trueFalse++ % 2 == 0 : null;
            int passage = passages.get(i % passages.size()).number();
            String concept = request.conceptNames().isEmpty() ? null
                    : request.conceptNames().get(i % request.conceptNames().size());
            slots.add(new Slot(i, type, difficulty, truth, passage, concept));
        }
        return slots;
    }

    static String planText(List<Slot> batch) {
        StringBuilder plan = new StringBuilder();
        int line = 1;
        for (Slot slot : batch) {
            plan.append(line++).append(". ").append(slot.type().code()).append(", ")
                    .append(slot.difficulty().name().toLowerCase(Locale.ROOT));
            if (slot.truth() != null) {
                plan.append(", a ").append(slot.truth() ? "TRUE" : "FALSE").append(" statement");
            }
            if (slot.concept() != null) {
                plan.append(", testing \"").append(slot.concept()).append('"');
            }
            plan.append(", preferably from passage [").append(slot.passage()).append("]\n");
        }
        return plan.toString().strip();
    }

    // ------------------------------------------------------------------ request

    private List<JsonNode> write(List<Slot> batch, List<Passage> passages, List<String> concepts, List<String> avoid,
                                 CancellationToken token) {
        String user = Prompts.ITEMS_USER.render(Map.of(
                "passages", PassageFormat.format(passages),
                "concepts", concepts.isEmpty() ? ""
                        : "\nConcepts to test (tag each item with the ones it tests): " + String.join("; ", concepts) + "\n",
                "avoid", avoidText(avoid),
                "plan", planText(batch)));
        List<Integer> numbers = passages.stream().map(Passage::number).toList();
        ObjectNode schema = JsonSchemas.object(Map.of("items", JsonSchemas.tuple(
                batch.stream().map(slot -> slotSchema(slot, numbers, concepts)).toList())));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.USER_WAITING,
                List.of(ChatMessage.system(Prompts.ITEMS_SYSTEM.render()), ChatMessage.user(user)),
                "items", schema, 320 * batch.size() + 200, temperature, token);
        List<JsonNode> items = new ArrayList<>();
        reply.path("items").forEach(items::add);
        return items;
    }

    private static String avoidText(List<String> avoid) {
        if (avoid.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("\nThese items already exist; write different ones:\n");
        for (String prompt : avoid.subList(Math.max(0, avoid.size() - AVOID_LIMIT), avoid.size())) {
            text.append("- ").append(prompt).append('\n');
        }
        return text.toString();
    }

    /** The schema of one slot: fixed type and difficulty, fields in the order the model should write them. */
    static ObjectNode slotSchema(Slot slot, List<Integer> passageNumbers, List<String> concepts) {
        Map<String, JsonNode> props = new LinkedHashMap<>();
        props.put("type", JsonSchemas.constant(slot.type().code()));
        props.put("difficulty", JsonSchemas.constant(slot.difficulty().name().toLowerCase(Locale.ROOT)));
        props.put("passage", JsonSchemas.integerIn(passageNumbers));
        switch (slot.type()) {
            case MCQ -> {
                props.put("prompt", JsonSchemas.string());
                props.put("answer", JsonSchemas.string());
                props.put("distractors", JsonSchemas.array(JsonSchemas.string(), 3, 3));
            }
            case TRUE_FALSE -> {
                props.put("answer", JsonSchemas.constant(Boolean.TRUE.equals(slot.truth()) ? "true" : "false"));
                props.put("prompt", JsonSchemas.string());
            }
            case CLOZE -> {
                props.put("answer", JsonSchemas.string());
                props.put("prompt", JsonSchemas.string());
            }
            case SHORT_ANSWER, FLASHCARD -> {
                props.put("prompt", JsonSchemas.string());
                props.put("answer", JsonSchemas.string());
            }
        }
        props.put("explanation", JsonSchemas.string());
        if (!concepts.isEmpty()) {
            props.put("concepts", JsonSchemas.array(JsonSchemas.oneOf(concepts), 1, Math.min(2, concepts.size())));
        }
        return JsonSchemas.object(props);
    }

    // ------------------------------------------------------------------ validation

    /** The draft for one written item, or empty when it breaks a rule (the reason is logged). */
    static Optional<ItemDraft> validate(JsonNode node, Slot slot, List<Passage> passages, List<String> concepts) {
        String reason = null;
        ItemType type = slot.type();
        String prompt = stripMeta(TextCleanup.oneLine(node.path("prompt").asText("")));
        String answer = TextCleanup.oneLine(node.path("answer").asText(""));
        String explanation = TextCleanup.sentence(node.path("explanation").asText(""));
        int passage = node.path("passage").asInt(slot.passage());
        List<String> distractors = new ArrayList<>();
        if (passages.stream().noneMatch(p -> p.number() == passage)) {
            reason = "unknown passage " + passage;
        } else if (prompt.isEmpty() || answer.isEmpty()) {
            reason = "empty prompt or answer";
        } else if (SOURCE_REFERENCE.matcher(prompt).find()) {
            reason = "prompt refers to the source";
        } else if (INSTRUCTION_ECHO.matcher(prompt).find()) {
            reason = "prompt echoes the instructions";
        } else {
            switch (type) {
                case MCQ -> {
                    answer = stripLetter(answer);
                    Set<String> seen = new HashSet<>();
                    seen.add(TextCleanup.key(answer));
                    for (JsonNode d : node.path("distractors")) {
                        String option = stripLetter(TextCleanup.oneLine(d.asText("")));
                        String key = TextCleanup.key(option);
                        if (!option.isEmpty() && !key.matches("(all|none|both|neither) of (the )?(above|these)")
                                && seen.add(key) && distractors.size() < 4) {
                            distractors.add(option);
                        }
                    }
                    if (BARE_LETTER.matcher(answer).matches()) {
                        reason = "answer is an option letter";
                    } else if (distractors.size() < 2) {
                        reason = "fewer than two distinct distractors";
                    }
                }
                case TRUE_FALSE -> {
                    answer = Boolean.TRUE.equals(slot.truth()) ? "true" : "false";
                    if (prompt.endsWith("?")) {
                        reason = "true/false prompt is a question";
                    }
                }
                case CLOZE -> {
                    prompt = BLANKS.matcher(prompt).replaceAll(ItemType.CLOZE_BLANK);
                    int blanks = prompt.split(Pattern.quote(ItemType.CLOZE_BLANK), -1).length - 1;
                    answer = answer.replaceAll("^[\"'“]+|[\"'”.]+$", "").strip();
                    if (blanks != 1) {
                        reason = blanks + " blanks in cloze prompt";
                    } else if (answer.isEmpty() || answer.split("\\s+").length > 6) {
                        reason = "cloze answer is not a short term";
                    } else if (TextCleanup.key(prompt).contains(TextCleanup.key(answer))) {
                        reason = "cloze prompt contains the answer";
                    }
                }
                case SHORT_ANSWER, FLASHCARD -> { }
            }
        }
        if (reason != null) {
            LOG.debug("Dropped a {} draft: {} ({})", type.code(), reason, prompt);
            return Optional.empty();
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode c : node.path("concepts")) {
            String name = c.asText("");
            concepts.stream().filter(known -> known.equalsIgnoreCase(name.strip())).findFirst()
                    .filter(known -> !tags.contains(known))
                    .ifPresent(tags::add);
        }
        if (tags.isEmpty() && slot.concept() != null) {
            tags.add(slot.concept());
        }
        return Optional.of(new ItemDraft(type, prompt, answer, explanation, distractors, slot.difficulty(), passage, tags));
    }

    static String stripMeta(String prompt) {
        String result = prompt;
        for (int i = 0; i < 3; i++) {
            String next = LEADING_META.matcher(result).replaceFirst("");
            if (next.equals(result)) {
                break;
            }
            result = next.strip();
        }
        return TextCleanup.capitalize(result);
    }

    private static String stripLetter(String option) {
        return OPTION_LETTER.matcher(option).replaceFirst("").strip();
    }
}
