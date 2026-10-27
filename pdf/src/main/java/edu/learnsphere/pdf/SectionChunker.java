package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.TextBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits a document into passages for search, answers and question generation.
 *
 * <ul>
 *   <li><b>Sections.</b> Headings are found with {@link HeadingDetector} (larger or bold type than the
 *       document's body text, section numbers such as "3.2", Markdown headings on pages read from images). A
 *       passage starts at each heading, which is kept as the passage's first line, and every passage carries
 *       the heading it falls under as its section title. A section too short to stand alone (under a quarter
 *       of the target) runs on into the next one; its passage takes the title of the section that fills most
 *       of it. A short end of a long section is added to the section's last passage instead, so the next
 *       section starts cleanly.</li>
 *   <li><b>Size.</b> Passages grow sentence by sentence to about {@code targetTokens} (measured with
 *       {@link TokenEstimator}) and never end inside a sentence. A sentence longer than the target is cut at
 *       word boundaries, preferring a comma or semicolon. The next passage repeats the previous one's last
 *       sentences, up to about {@code overlapTokens}, so an idea cut at a boundary is whole in one of them;
 *       there is no overlap across a heading.</li>
 *   <li><b>Pages.</b> Each passage records the first and last page it draws text from. A paragraph that
 *       continues on the next page or column is joined back together (with its hyphenated word mended).</li>
 *   <li><b>Never empty.</b> Blank pages and blocks are skipped; a document without text gives no passages,
 *       and a very short one gives a single passage. A short last passage is folded into the one before.</li>
 * </ul>
 *
 * <p>Stateless and thread-safe.
 */
public final class SectionChunker implements Chunker {

    /** Creates a chunker. */
    public SectionChunker() {
    }

    @Override
    public List<ChunkDraft> chunk(List<PageText> pages, Options options) {
        Objects.requireNonNull(options, "options");
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        List<Unit> units = units(pages, bodySize(pages), options.targetTokens());
        return new Assembler(options).assemble(units);
    }

    /** The most common font size of the document's text, weighted by length; 0 when no page has sizes. */
    static float bodySize(List<PageText> pages) {
        SizeHistogram sizes = new SizeHistogram();
        for (PageText page : pages) {
            if (page == null) {
                continue;
            }
            for (TextBlock block : page.blocks()) {
                sizes.add(block.fontSize(), block.text().length());
            }
        }
        return sizes.mode();
    }

    private static List<Unit> units(List<PageText> pages, float bodySize, int targetTokens) {
        List<Unit> units = new ArrayList<>();
        String section = null;
        for (PageText page : pages) {
            if (page == null) {
                continue;
            }
            int pageNumber = Math.max(1, page.pageNumber());
            for (TextBlock block : page.blocks()) {
                String text = clean(block.text());
                if (text.isEmpty()) {
                    continue;
                }
                String heading = HeadingDetector.headingText(block, bodySize);
                if (heading != null) {
                    heading = clean(heading);
                    section = heading;
                    units.add(new Unit(Kind.HEADING, heading, pageNumber, pageNumber, section, true, false));
                    continue;
                }
                boolean continues = !units.isEmpty() && continuesParagraph(units.getLast(), text);
                boolean first = true;
                for (SentenceSplitter.Sentence sentence : SentenceSplitter.split(text)) {
                    if (first && continues) {
                        // The sentence cut by the page or column break becomes one unit again.
                        Unit cut = units.removeLast();
                        boolean firstPiece = true;
                        for (String piece : splitLong(joinAcrossBreak(cut.text, sentence.text()), targetTokens)) {
                            units.add(new Unit(Kind.TEXT, piece, cut.firstPage, pageNumber, section,
                                    firstPiece && cut.startsBlock, firstPiece && cut.lineBreak));
                            firstPiece = false;
                        }
                        first = false;
                        continue;
                    }
                    for (String piece : splitLong(sentence.text(), targetTokens)) {
                        units.add(new Unit(Kind.TEXT, piece, pageNumber, pageNumber, section, first,
                                !first && sentence.afterLineBreak()));
                        first = false;
                    }
                }
            }
        }
        return units;
    }

    /** True when {@code next} carries on a paragraph cut by a page or column break after {@code previous}. */
    private static boolean continuesParagraph(Unit previous, String next) {
        if (previous.kind != Kind.TEXT) {
            return false;
        }
        char last = previous.text.charAt(previous.text.length() - 1);
        if (".!?:;\"')]\u201D\u2019\u2026\u3002".indexOf(last) >= 0) {
            return false;
        }
        return Character.isLowerCase(next.charAt(0));
    }

    /** {@code before} and {@code after} a page or column break, with a word hyphenated across it mended. */
    static String joinAcrossBreak(String before, String after) {
        int n = before.length();
        boolean brokenWord = n >= 2 && TextNormalizer.isHyphen(before.charAt(n - 1)) && Character.isLetter(before.charAt(n - 2))
                && !after.isEmpty() && Character.isLowerCase(after.charAt(0));
        return brokenWord ? before.substring(0, n - 1) + after : before + " " + after;
    }

    /** Pieces of {@code sentence} of at most {@code maxTokens}, cut between words where possible. */
    static List<String> splitLong(String sentence, int maxTokens) {
        if (TokenEstimator.estimate(sentence) <= maxTokens) {
            return List.of(sentence);
        }
        List<String> pieces = new ArrayList<>();
        StringBuilder piece = new StringBuilder();
        int pieceTokens = 0;
        int lastBreak = -1; // length of piece just after a word ending in , ; or :
        for (String word : sentence.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int wordTokens = TokenEstimator.estimate(word);
            if (wordTokens > maxTokens) {
                flushPiece(piece, pieces);
                pieceTokens = 0;
                lastBreak = -1;
                pieces.addAll(splitWord(word, maxTokens));
                continue;
            }
            if (pieceTokens + wordTokens > maxTokens && !piece.isEmpty()) {
                if (lastBreak > piece.length() / 2) {
                    String rest = piece.substring(lastBreak).strip();
                    piece.setLength(lastBreak);
                    flushPiece(piece, pieces);
                    piece.append(rest);
                } else {
                    flushPiece(piece, pieces);
                }
                pieceTokens = TokenEstimator.estimate(piece);
                lastBreak = -1;
            }
            if (!piece.isEmpty()) {
                piece.append(' ');
            }
            piece.append(word);
            pieceTokens += wordTokens;
            char end = word.charAt(word.length() - 1);
            if (end == ',' || end == ';' || end == ':') {
                lastBreak = piece.length();
            }
        }
        flushPiece(piece, pieces);
        return pieces;
    }

    private static void flushPiece(StringBuilder piece, List<String> pieces) {
        String text = piece.toString().strip();
        if (!text.isEmpty()) {
            pieces.add(text);
        }
        piece.setLength(0);
    }

    /** Cuts one enormous "word" (a URL, a formula, a run of digits) into pieces of at most {@code maxTokens}. */
    private static List<String> splitWord(String word, int maxTokens) {
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int length = Math.min(word.length() - start, Math.max(1, maxTokens * 4));
            while (length > 1 && TokenEstimator.estimate(word.substring(start, start + length)) > maxTokens) {
                length = Math.max(1, length * 3 / 4);
            }
            int end = start + length;
            if (end < word.length() && Character.isLowSurrogate(word.charAt(end))) {
                end++;
            }
            pieces.add(word.substring(start, end));
            start = end;
        }
        return pieces;
    }

    /** Trims, drops carriage returns and collapses runs of spaces, keeping line breaks. */
    static String clean(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        boolean lineBreak = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineBreak = !out.isEmpty();
                space = false;
            } else if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = !out.isEmpty();
            } else {
                if (lineBreak) {
                    out.append('\n');
                } else if (space) {
                    out.append(' ');
                }
                lineBreak = false;
                space = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    private enum Kind { HEADING, TEXT }

    /**
     * A heading or a sentence (or piece of one).
     *
     * @param firstPage    page the unit starts on
     * @param lastPage     page the unit ends on (later than {@code firstPage} for a sentence cut by a page break)
     * @param startsBlock  first unit of a block: rendered after a blank line
     * @param lineBreak    the source had a line break before this sentence
     */
    private record Unit(Kind kind, String text, int firstPage, int lastPage, String section, boolean startsBlock,
                        boolean lineBreak) {

        int tokens() {
            return TokenEstimator.estimate(text);
        }
    }

    /** Groups units into passages. One per call. */
    private static final class Assembler {
        private final int target;
        private final int overlap;
        private final int minTokens;
        private final List<ChunkDraft> out = new ArrayList<>();
        private final List<Unit> current = new ArrayList<>();
        private final List<Integer> currentTokens = new ArrayList<>();
        private int tokens;
        private int freshUnits;
        private int freshTokens;
        private List<Unit> lastUnits = List.of();

        Assembler(Options options) {
            this.target = options.targetTokens();
            this.overlap = options.overlapTokens();
            this.minTokens = Math.max(1, target / 4);
        }

        List<ChunkDraft> assemble(List<Unit> units) {
            for (Unit unit : units) {
                int unitTokens = unit.tokens();
                if (unit.kind == Kind.HEADING) {
                    if (freshTokens >= minTokens) {
                        flush(false);
                    } else if (freshUnits == 0 || foldIntoPrevious()) {
                        clear(); // no overlap into a new section
                    }
                } else if (freshUnits > 0 && tokens + unitTokens > target) {
                    boolean smallSoFar = tokens < target / 2 && tokens + unitTokens <= target * 5 / 4;
                    if (!smallSoFar) {
                        flush(true);
                    }
                }
                add(unit, unitTokens, true);
            }
            finish();
            return List.copyOf(out);
        }

        private void add(Unit unit, int unitTokens, boolean fresh) {
            current.add(unit);
            currentTokens.add(unitTokens);
            tokens += unitTokens;
            if (fresh) {
                freshUnits++;
                freshTokens += unitTokens;
            }
        }

        private void clear() {
            current.clear();
            currentTokens.clear();
            tokens = 0;
            freshUnits = 0;
            freshTokens = 0;
        }

        private void flush(boolean withOverlap) {
            if (freshUnits == 0) {
                clear();
                return;
            }
            List<Unit> units = List.copyOf(current);
            List<Integer> unitTokens = List.copyOf(currentTokens);
            emit(units, out.size());
            lastUnits = units;
            clear();
            if (withOverlap) {
                int from = overlapStart(units, unitTokens);
                for (int i = from; i < units.size(); i++) {
                    add(units.get(i), unitTokens.get(i), false);
                }
            }
        }

        /** Index of the first trailing sentence to repeat in the next passage. */
        private int overlapStart(List<Unit> units, List<Integer> unitTokens) {
            int from = units.size();
            int sum = 0;
            for (int i = units.size() - 1; i > 0; i--) {
                Unit unit = units.get(i);
                if (unit.kind != Kind.TEXT) {
                    break;
                }
                int next = sum + unitTokens.get(i);
                boolean fits = next <= overlap || (from == units.size() && next <= overlap * 3 / 2);
                if (!fits) {
                    break;
                }
                sum = next;
                from = i;
            }
            return from;
        }

        private void finish() {
            if (freshUnits > 0 && !foldIntoPrevious()) {
                emit(List.copyOf(current), out.size());
            }
            clear();
        }

        /**
         * Appends the text added since the last passage to that passage when it is a short end of the same
         * section (under a quarter of the target, no heading) and the passage stays within 5/4 of the target.
         * Returns true when it did; the caller then clears the current passage.
         */
        private boolean foldIntoPrevious() {
            if (freshUnits == 0 || freshTokens >= minTokens || out.isEmpty() || lastUnits.isEmpty()) {
                return false;
            }
            List<Unit> fresh = current.subList(current.size() - freshUnits, current.size());
            if (fresh.stream().anyMatch(u -> u.kind == Kind.HEADING)
                    || !Objects.equals(lastUnits.getLast().section, fresh.getFirst().section)
                    || out.getLast().tokenEstimate() + freshTokens > target * 5 / 4) {
                return false;
            }
            List<Unit> merged = new ArrayList<>(lastUnits);
            merged.addAll(fresh);
            out.removeLast();
            emit(merged, out.size());
            lastUnits = merged;
            return true;
        }

        private void emit(List<Unit> units, int ordinal) {
            String text = render(units);
            if (text.isBlank()) {
                return;
            }
            int pageStart = Integer.MAX_VALUE;
            int pageEnd = 0;
            for (Unit unit : units) {
                pageStart = Math.min(pageStart, unit.firstPage);
                pageEnd = Math.max(pageEnd, unit.lastPage);
            }
            out.add(new ChunkDraft(ordinal, pageStart, pageEnd, sectionTitle(units), text, TokenEstimator.estimate(text)));
        }

        /** The section that most of the passage's text belongs to (ties go to the later one). */
        private static String sectionTitle(List<Unit> units) {
            Map<String, Integer> weights = new HashMap<>();
            List<String> order = new ArrayList<>();
            for (Unit unit : units) {
                if (unit.section == null) {
                    continue;
                }
                if (!weights.containsKey(unit.section)) {
                    order.add(unit.section);
                }
                weights.merge(unit.section, Math.max(1, unit.tokens()), Integer::sum);
            }
            String best = null;
            int bestWeight = -1;
            for (String section : order) {
                int weight = weights.get(section);
                if (weight >= bestWeight) {
                    best = section;
                    bestWeight = weight;
                }
            }
            return best;
        }

        private static String render(List<Unit> units) {
            StringBuilder text = new StringBuilder();
            Unit previous = null;
            for (Unit unit : units) {
                if (previous != null) {
                    if (unit.kind == Kind.HEADING || previous.kind == Kind.HEADING || unit.startsBlock) {
                        text.append("\n\n");
                    } else if (unit.lineBreak) {
                        text.append('\n');
                    } else {
                        text.append(' ');
                    }
                }
                text.append(unit.text);
                previous = unit;
            }
            return text.toString();
        }
    }
}
