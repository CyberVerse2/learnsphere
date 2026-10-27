package edu.learnsphere.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prompt kept as a UTF-8 resource next to this class ({@code edu/learnsphere/ai/prompt/<name>}), with
 * {@code {{placeholder}}} markers filled in by {@link #render}. Every placeholder must be given a value.
 * Immutable and thread-safe.
 */
public final class PromptTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-z][a-zA-Z0-9_]*)}}");

    private final String name;
    private final String text;
    private final Set<String> placeholders;

    private PromptTemplate(String name, String text) {
        this.name = name;
        this.text = text;
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        this.placeholders = Set.copyOf(found);
    }

    /**
     * Loads the resource {@code name} from this package.
     *
     * @throws IllegalStateException when the resource is missing
     */
    public static PromptTemplate load(String name) {
        Objects.requireNonNull(name, "name");
        try (InputStream in = PromptTemplate.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource " + name);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").strip();
            return new PromptTemplate(name, text);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read prompt resource " + name, e);
        }
    }

    /** A template from literal text (tests). */
    public static PromptTemplate of(String name, String text) {
        return new PromptTemplate(name, text.strip());
    }

    /** The resource name. */
    public String name() {
        return name;
    }

    /** The raw text with placeholders. */
    public String text() {
        return text;
    }

    /** The placeholder names it contains. */
    public Set<String> placeholders() {
        return placeholders;
    }

    /**
     * The text with each {@code {{key}}} replaced by its value. Values are inserted literally (a value
     * containing {@code {{x}}} is not expanded again).
     *
     * @throws IllegalArgumentException when a placeholder has no value
     */
    public String render(Map<String, String> values) {
        Objects.requireNonNull(values, "values");
        for (String placeholder : placeholders) {
            if (values.get(placeholder) == null) {
                throw new IllegalArgumentException("Prompt " + name + " needs a value for {{" + placeholder + "}}");
            }
        }
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 256);
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(values.get(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The text when it has no placeholders. */
    public String render() {
        return render(Map.of());
    }

    @Override
    public String toString() {
        return "PromptTemplate[" + name + "]";
    }
}
