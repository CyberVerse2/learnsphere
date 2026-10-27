package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.charts.BarChart;
import edu.learnsphere.app.ui.components.charts.CanvasChart.Tip;
import edu.learnsphere.core.service.ConceptMastery;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Mastery by concept as horizontal bars, weakest first, with a notch at the 60% line under which a concept
 * counts as weak. Weak concepts take the warning colour, and "Practise weak concepts" starts a session on
 * them. A long list shows its weakest concepts first, with a button for the rest.
 */
final class MasterySection {

    /** Concepts shown before "Show all". */
    static final int LIMIT = 8;
    /** How far the footer moves up into the card's spacing. */
    private static final double FOOTER_PULL = -18;

    private final List<ConceptMastery> measured;
    private final long unmeasured;
    private final Map<String, String> conceptCourses;
    private final BarChart chart = new BarChart();
    private final Card card;
    private boolean expanded;

    /**
     * @param concepts       every concept in scope
     * @param conceptCourses course titles by concept id (overview of several courses), else empty
     * @param practise       starts a session on the weak concepts
     * @param expanded       whether to start with every concept shown
     */
    MasterySection(List<ConceptMastery> concepts, Map<String, String> conceptCourses, Runnable practise, boolean expanded) {
        this.measured = concepts.stream().filter(ConceptMastery::hasMastery)
                .sorted(Comparator.comparingDouble(ConceptMastery::mastery).thenComparing(ConceptMastery::name)).toList();
        this.unmeasured = concepts.size() - measured.size();
        this.conceptCourses = conceptCourses;
        this.expanded = expanded;
        long weak = measured.stream().filter(ConceptMastery::isWeak).count();

        SectionHeader header = new SectionHeader("Mastery by concept").subtitle(weak == 0
                ? "Weakest first. Every concept you've studied is at 60% or above."
                : "Weakest first. Concepts under 60% need work, and come back more often when you study.");
        card = new Card(header);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "mastery-card");
        card.setSpacing(20);
        if (weak > 0) {
            Button button = new Button("Practise weak concepts", Icons.of(Feather.TARGET, 15));
            button.getStyleClass().addAll("primary", "practise-weak");
            button.setOnAction(event -> practise.run());
            header.trailing(button);
        }

        if (measured.isEmpty()) {
            Label none = new Label("Answer a few questions and each concept's mastery appears here.");
            none.getStyleClass().add("text-muted");
            none.setWrapText(true);
            card.getChildren().add(none);
            return;
        }
        chart.setTitle("Mastery by concept, weakest first");
        chart.setMarker(ConceptMastery.WEAK_THRESHOLD, Formats.percent(ConceptMastery.WEAK_THRESHOLD));
        chart.getStyleClass().add("mastery-chart");
        card.getChildren().add(chart);
        fill();
        HBox footer = footer();
        if (footer != null) {
            card.getChildren().add(footer);
        }
    }

    Card node() {
        return card;
    }

    BarChart chart() {
        return chart;
    }

    boolean isExpanded() {
        return expanded;
    }

    private void fill() {
        List<ConceptMastery> shown = expanded ? measured : measured.stream().limit(LIMIT).toList();
        chart.setBars(shown.stream().map(this::bar).toList());
    }

    private BarChart.Bar bar(ConceptMastery c) {
        String course = conceptCourses.get(c.conceptId());
        List<String> parts = new ArrayList<>();
        parts.add(Formats.percent(c.mastery()) + " mastery");
        if (c.itemCount() > 0) {
            parts.add(c.reviewedItemCount() + " of " + Formats.count(c.itemCount(), "question", "questions") + " seen");
        }
        String value = String.join(" · ", parts);
        if (course != null) {
            value = value + "\n" + course;
        }
        return new BarChart.Bar(c.name(), c.mastery(), Formats.percent(c.mastery()), c.isWeak(), new Tip(c.name(), value));
    }

    private HBox footer() {
        List<String> notes = new ArrayList<>();
        if (unmeasured > 0) {
            notes.add(Formats.count(unmeasured, "concept", "concepts") + " not studied yet");
        }
        boolean more = measured.size() > LIMIT;
        if (notes.isEmpty() && !more) {
            return null;
        }
        Label note = new Label(String.join(" · ", notes));
        note.getStyleClass().add("text-caption");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox footer = new HBox(12, note, gap);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("mastery-footer");
        // The marker's label already leaves room under the bars, and the footer's text sits in the middle of
        // a button-high row: pull it up so the note follows the "60%" at the card's usual rhythm.
        VBox.setMargin(footer, new Insets(FOOTER_PULL, 0, 0, 0));
        if (more) {
            Button toggle = new Button();
            toggle.getStyleClass().addAll("quiet", "small", "show-all");
            toggle.setContentDisplay(ContentDisplay.RIGHT);
            Runnable label = () -> {
                toggle.setText(expanded ? "Show the weakest " + LIMIT : "Show all " + measured.size() + " concepts");
                toggle.setGraphic(Icons.of(expanded ? Feather.CHEVRON_UP : Feather.CHEVRON_DOWN, 14));
            };
            label.run();
            toggle.setOnAction(event -> {
                expanded = !expanded;
                label.run();
                fill();
            });
            footer.getChildren().add(toggle);
        }
        return footer;
    }
}
