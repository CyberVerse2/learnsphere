package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.ModuleSuggester;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A module for each PDF, and within a PDF for each numbered chapter ("2 Propositional Logic", "2.1 Syntax", …,
 * "3 Predicate Logic"). A chapter's module is titled by its first heading without the number; a PDF's by its title
 * without a leading "Lecture 3 –" or "Week 2:".
 */
public final class FakeModuleSuggester implements ModuleSuggester {

    private static final Pattern CHAPTER = Pattern.compile("^(\\d+)(?:\\.\\d+)*\\.?\\s+(.+)$");
    private static final Pattern SERIES = Pattern.compile("^\\p{L}+\\s+\\d+\\s*[–:-]\\s*(.+)$");

    @Override
    public List<Suggestion> suggest(String course, List<Section> sections, CancellationToken token) {
        token.throwIfCancelled();
        List<Suggestion> modules = new ArrayList<>();
        String document = null;
        String chapter = null;
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            Matcher numbered = CHAPTER.matcher(section.heading());
            String number = numbered.matches() ? numbered.group(1) : null;
            boolean newDocument = !section.documentTitle().equals(document);
            boolean newChapter = number != null && !number.equals(chapter);
            if (newDocument || newChapter) {
                String title = number != null ? numbered.group(2) : withoutSeries(section.documentTitle());
                modules.add(new Suggestion(title, "Key ideas of " + title + ".", i));
            }
            document = section.documentTitle();
            chapter = number != null ? number : newDocument ? null : chapter;
        }
        return modules;
    }

    private static String withoutSeries(String title) {
        Matcher series = SERIES.matcher(title.strip());
        return series.matches() ? series.group(1).strip() : title.strip();
    }
}
