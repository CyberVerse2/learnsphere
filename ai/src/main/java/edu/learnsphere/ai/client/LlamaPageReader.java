package edu.learnsphere.ai.client;

import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link PageReader} on the vision chat model at {@link ChatPriority#BACKGROUND} (so it makes way for a
 * question the learner is waiting on). The page image is transcribed to Markdown ({@code page-system.txt});
 * parts marked {@code [illegible: …]} become the illegible spans. A reply cut off at the token limit, or one
 * that repeats a line over and over, is trimmed and flagged for the reviewer. Thread-safe.
 */
public final class LlamaPageReader implements PageReader {

    /** Longest transcription, in tokens. */
    public static final int MAX_TOKENS = 4096;
    /** A line repeated more often than this in a row is a generation loop. */
    static final int MAX_REPEATS = 3;

    static final String CUT_OFF = "The end of this page may be missing.";
    static final String REPEATED = "A repeated line was removed from this page.";
    static final String UNREADABLE = "Part of this page couldn't be read.";

    private static final Pattern ILLEGIBLE = Pattern.compile("\\[illegible(?:\\s*[:\\-–]\\s*([^\\]]*))?]",
            Pattern.CASE_INSENSITIVE);

    private final LocalChat chat;

    public LlamaPageReader(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public PageTranscription read(byte[] pngImage, int pageNumber, CancellationToken token) {
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (pngImage == null || pngImage.length == 0) {
            return new PageTranscription("", List.of(UNREADABLE));
        }
        ChatRequest request = ChatRequest.text(List.of(
                ChatMessage.system(Prompts.PAGE_SYSTEM.render()),
                ChatMessage.userWithImage(ChatMessage.Image.png(pngImage),
                        Prompts.PAGE_USER.render(Map.of("page", String.valueOf(pageNumber))))), MAX_TOKENS, 0.0);
        ChatResult result = chat.complete(ChatPriority.BACKGROUND, request, TokenSink.NONE, token);
        return toTranscription(result.text(), result.truncated());
    }

    static PageTranscription toTranscription(String reply, boolean truncated) {
        List<String> spans = new ArrayList<>();
        String markdown = stripFence(reply == null ? "" : reply.strip());
        String looped = removeLoops(markdown);
        if (!looped.equals(markdown)) {
            spans.add(REPEATED);
            markdown = looped;
        }
        Matcher m = ILLEGIBLE.matcher(markdown);
        while (m.find()) {
            String what = m.group(1) == null ? "" : TextCleanup.oneLine(m.group(1));
            spans.add(what.isEmpty() ? UNREADABLE : TextCleanup.capitalize(what));
        }
        if (truncated) {
            spans.add(CUT_OFF);
        }
        return new PageTranscription(markdown.strip(), spans);
    }

    static String stripFence(String text) {
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            String body = firstLine < 0 ? "" : text.substring(firstLine + 1);
            if (body.stripTrailing().endsWith("```")) {
                body = body.stripTrailing();
                body = body.substring(0, body.length() - 3);
            }
            return body.strip();
        }
        return text;
    }

    /** Drops lines that repeat the previous non-empty line more than {@value #MAX_REPEATS} times in a row. */
    static String removeLoops(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        String previous = null;
        int run = 0;
        for (String line : lines) {
            String key = line.strip();
            if (!key.isEmpty() && key.equals(previous)) {
                run++;
                if (run >= MAX_REPEATS) {
                    continue;
                }
            } else if (!key.isEmpty()) {
                previous = key;
                run = 0;
            }
            out.append(line).append('\n');
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
}
