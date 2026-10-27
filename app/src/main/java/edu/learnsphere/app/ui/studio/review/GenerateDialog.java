package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.ToggleChip;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.GenerationRequest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;

/**
 * "Generate questions": where to draw from, which concepts to focus on, how many, which kinds and how hard.
 * The study assistant writes them in the background, so the dialog closes as soon as the work is queued.
 */
final class GenerateDialog {

    /** The difficulty choice; {@code MIX} lets the study assistant vary it. */
    enum Mix {
        MIX("A mix", null), EASY("Easy", Difficulty.EASY), MEDIUM("Medium", Difficulty.MEDIUM), HARD("Hard", Difficulty.HARD);

        final String label;
        final Difficulty difficulty;

        Mix(String label, Difficulty difficulty) {
            this.label = label;
            this.difficulty = difficulty;
        }
    }

    /** One entry of the "Draw from" list: a module, or the whole course for null. */
    record Scope(String moduleId, String title, boolean ready) {
    }

    static final int DEFAULT_COUNT = 10;
    private static final double WIDTH = 600;
    /** Room for the form inside the card: chip rows wrap at this width when the dialog sizes itself. */
    private static final double CONTENT_WIDTH = WIDTH - 70;
    private static final List<ItemType> TYPES =
            List.of(ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.CLOZE, ItemType.SHORT_ANSWER, ItemType.FLASHCARD);

    private final ReviewQueueTab tab;
    private final String courseId;
    private final List<Concept> concepts;
    private final ReviewModal modal;
    private final ChoiceBox<Scope> scope = new ChoiceBox<>();
    private final Label scopeNote = new Label();
    private final FlowPane conceptChips = new FlowPane(8, 8);
    private final Label conceptNote = new Label();
    private final Map<String, ToggleChip> conceptToggles = new LinkedHashMap<>();
    private final TextField count = new TextField(Integer.toString(DEFAULT_COUNT));
    private final Map<ItemType, ToggleChip> typeToggles = new LinkedHashMap<>();
    private final Label typeNote = new Label();
    private final SegmentedControl<Mix> mix = new SegmentedControl<>(List.of(Mix.values()), m -> m.label);
    private final Label error = new Label();
    private final Button generate = new Button();

    /**
     * @param presetModuleId the module to start with, or null for the whole course
     */
    GenerateDialog(ReviewQueueTab tab, String courseId, List<CourseModule> modules, Set<String> readyModuleIds,
                   boolean courseReady, List<Concept> concepts, String presetModuleId) {
        this.tab = tab;
        this.courseId = courseId;
        this.concepts = List.copyOf(concepts);

        List<Scope> scopes = new ArrayList<>();
        scopes.add(new Scope(null, "Whole course", courseReady));
        for (CourseModule module : modules) {
            scopes.add(new Scope(module.id(), module.title(), readyModuleIds.contains(module.id())));
        }
        scope.getItems().setAll(scopes);
        scope.setConverter(new StringConverter<>() {
            @Override
            public String toString(Scope s) {
                return s == null ? "" : s.title() + (s.ready() ? "" : "  (no PDF ready)");
            }

            @Override
            public Scope fromString(String text) {
                return null;
            }
        });
        scope.setValue(scopes.stream().filter(s -> Objects.equals(s.moduleId(), presetModuleId)).findFirst()
                .orElse(scopes.getFirst()));
        scope.setAccessibleText("Draw from");
        scope.setMaxWidth(Double.MAX_VALUE);
        scope.valueProperty().addListener((obs, old, value) -> {
            showConcepts();
            refresh();
        });
        note(scopeNote);

        conceptChips.getStyleClass().add("generate-concepts");
        conceptChips.setPrefWrapLength(CONTENT_WIDTH);
        note(conceptNote);

        count.setTextFormatter(new TextFormatter<>(change -> change.getControlNewText().matches("\\d{0,3}") ? change : null));
        count.setPrefColumnCount(3);
        count.setAccessibleText("How many questions");
        count.getStyleClass().add("count-field");
        count.textProperty().addListener((obs, old, value) -> refresh());
        count.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.UP) {
                step(1);
                event.consume();
            } else if (event.getCode() == KeyCode.DOWN) {
                step(-1);
                event.consume();
            }
        });
        count.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                count.setText(Integer.toString(countValue()));
            }
        });
        IconButton fewer = new IconButton(Feather.MINUS, "Fewer");
        fewer.setOnAction(event -> step(-1));
        IconButton more = new IconButton(Feather.PLUS, "More");
        more.setOnAction(event -> step(1));
        HBox stepper = new HBox(4, fewer, count, more);
        stepper.setAlignment(Pos.CENTER_LEFT);
        stepper.getStyleClass().add("count-stepper");

        FlowPane typeChips = new FlowPane(8, 8);
        typeChips.setPrefWrapLength(CONTENT_WIDTH);
        for (ItemType type : TYPES) {
            ToggleChip chip = new ToggleChip(ReviewLabels.typePlural(type));
            chip.setSelected(true);
            chip.selectedProperty().addListener((obs, old, on) -> refresh());
            typeToggles.put(type, chip);
            typeChips.getChildren().add(chip);
        }
        note(typeNote);
        typeNote.getStyleClass().add("text-danger");

        mix.setValue(Mix.MIX);
        mix.setAccessibleText("Difficulty");

        error.getStyleClass().addAll("text-danger", "dialog-error");
        error.setWrapText(true);
        error.setGraphic(Icons.of(Feather.ALERT_CIRCLE, 14));
        show(error, false);

        VBox form = new VBox(22,
                field("Draw from", scope, scopeNote),
                field("Focus on", conceptChips, conceptNote),
                field("How many", stepper, null),
                field("Question types", typeChips, typeNote),
                field("Difficulty", mix, null),
                error);
        form.getStyleClass().add("dialog-form");

        Label hint = new Label("You can keep reviewing while they're written.");
        hint.getStyleClass().add("text-caption");
        hint.setWrapText(true);
        HBox.setHgrow(hint, Priority.ALWAYS);
        hint.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button("Cancel");
        generate.getStyleClass().add("primary");
        generate.setDefaultButton(true);
        generate.setMinWidth(Region.USE_PREF_SIZE);
        generate.setOnAction(event -> submit());

        modal = new ReviewModal("Generate questions", WIDTH)
                .owner(this)
                .subtitle("The study assistant drafts them from your PDFs. Each one waits here for you to check.")
                .body(form)
                .footer(hint, cancel, generate);
        cancel.setOnAction(event -> modal.close());
        modal.getStylesheets().add(ReviewQueueTab.STYLESHEET);
        modal.setInitialFocus(scope);
        showConcepts();
        refresh();
    }

    void open(Node anchor) {
        modal.open(anchor);
    }

    ReviewModal modal() {
        return modal;
    }

    /** The request the dialog would send now, or null when it can't be sent. */
    GenerationRequest request() {
        Set<ItemType> types = EnumSet.noneOf(ItemType.class);
        typeToggles.forEach((type, chip) -> {
            if (chip.isSelected()) {
                types.add(type);
            }
        });
        Scope chosen = scope.getValue();
        if (types.isEmpty() || chosen == null || !chosen.ready()) {
            return null;
        }
        Set<String> focus = new LinkedHashSet<>();
        conceptToggles.forEach((id, chip) -> {
            if (chip.isSelected()) {
                focus.add(id);
            }
        });
        return new GenerationRequest(courseId, chosen.moduleId(), types, countValue(), mix.getValue().difficulty, focus);
    }

    /** Selects or clears a question type (tests). */
    void setType(ItemType type, boolean on) {
        typeToggles.get(type).setSelected(on);
    }

    void setCount(int value) {
        count.setText(Integer.toString(value));
    }

    void setScope(String moduleId) {
        scope.getItems().stream().filter(s -> Objects.equals(s.moduleId(), moduleId)).findFirst().ifPresent(scope::setValue);
    }

    Button generateButton() {
        return generate;
    }

    String errorText() {
        return error.isVisible() ? error.getText() : null;
    }

    private void submit() {
        GenerationRequest request = request();
        if (request == null) {
            return;
        }
        generate.setDisable(true);
        tab.startGeneration(request, modal::close, message -> {
            error.setText(message);
            show(error, true);
            refresh();
        });
    }

    private void showConcepts() {
        Scope chosen = scope.getValue();
        conceptToggles.clear();
        conceptChips.getChildren().clear();
        for (Concept concept : concepts) {
            if (chosen == null || chosen.moduleId() == null || chosen.moduleId().equals(concept.moduleId())
                    || concept.moduleId() == null) {
                ToggleChip chip = new ToggleChip(concept.name());
                conceptToggles.put(concept.id(), chip);
                conceptChips.getChildren().add(chip);
            }
        }
        boolean none = conceptToggles.isEmpty();
        conceptChips.setVisible(!none);
        conceptChips.setManaged(!none);
        conceptNote.setText(none
                ? "No key concepts here. Questions cover the whole text; find key concepts first to focus them."
                : "Leave them all off to cover every concept.");
    }

    private void refresh() {
        Scope chosen = scope.getValue();
        boolean ready = chosen != null && chosen.ready();
        scopeNote.setText(ready ? "" : chosen != null && chosen.moduleId() != null
                ? "Add a PDF to this module and wait until it's ready."
                : "Add a PDF to this course and wait until it's ready.");
        show(scopeNote, !ready);
        scopeNote.getStyleClass().remove("text-warning");
        if (!ready) {
            scopeNote.getStyleClass().add("text-warning");
        }
        boolean anyType = typeToggles.values().stream().anyMatch(ToggleChip::isSelected);
        typeNote.setText("Choose at least one question type.");
        show(typeNote, !anyType);
        int n = countValue();
        generate.setText(n == 1 ? "Generate 1 question" : "Generate " + n + " questions");
        generate.setDisable(!ready || !anyType);
    }

    private int countValue() {
        try {
            return Math.clamp(Integer.parseInt(count.getText().strip()), 1, GenerationRequest.MAX_COUNT);
        } catch (NumberFormatException e) {
            return DEFAULT_COUNT;
        }
    }

    private void step(int delta) {
        int n = countValue();
        // Steps of one up to 20, then of five.
        int size = (delta > 0 ? n >= 20 : n > 20) ? 5 : 1;
        int next = Math.clamp(n + (long) delta * size, 1, GenerationRequest.MAX_COUNT);
        count.setText(Integer.toString(next));
    }

    private static VBox field(String label, Node control, Label note) {
        Label title = new Label(label);
        title.getStyleClass().add("field-label");
        VBox field = new VBox(8, title, control);
        if (note != null) {
            field.getChildren().add(note);
        }
        return field;
    }

    private static void note(Label label) {
        label.getStyleClass().add("text-caption");
        label.setWrapText(true);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
