package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.ToggleChip;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemDetail;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;
import org.kordamp.ikonli.feather.Feather;

/**
 * Edits a question, or writes a new one by hand. The form follows the question's type (multiple choice
 * gets a correct answer and two to four wrong options, a fill-in-the-blank sentence gets its blank, a
 * flashcard its front and back, a short answer its model answer and key points) and checks it as the
 * creator types, with the same rules saving applies. The source passage sits beside the form, highlighting
 * the sentences that carry the answer as it changes.
 */
final class ItemEditorDialog {

    /** True or false, for the statement's answer. */
    enum Truth { TRUE, FALSE }

    /** An entry of the module list: a module, or the whole course for null. */
    record Place(String moduleId, String title) {
    }

    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
    private static final List<ItemType> TYPES =
            List.of(ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.CLOZE, ItemType.SHORT_ANSWER, ItemType.FLASHCARD);

    private final ReviewQueueTab tab;
    private final ItemDetail existing;
    private final List<Concept> concepts;
    private final ReviewModal modal;
    private ItemType type;

    private final SegmentedControl<ItemType> typeControl;
    private final ChoiceBox<Place> place = new ChoiceBox<>();
    private final TextArea prompt = area(3);
    private final Label promptLabel = fieldLabel("");
    private final Label promptHint = hint("");
    private final Button insertBlank = new Button("Insert blank", Icons.of(Feather.MINUS, 14));
    private final TextField shortAnswer = new TextField();
    private final TextArea longAnswer = area(4);
    private final SegmentedControl<Truth> truth =
            new SegmentedControl<>(List.of(Truth.values()), t -> t == Truth.TRUE ? "True" : "False");
    private final VBox optionRows = new VBox(8);
    private final List<TextField> options = new ArrayList<>();
    private final Button addOption = new Button("Add wrong option", Icons.of(Feather.PLUS, 14));
    private final VBox pointRows = new VBox(8);
    private final List<TextField> points = new ArrayList<>();
    private final Button addPoint = new Button("Add key point", Icons.of(Feather.PLUS, 14));
    private final TextArea explanation = area(3);
    private final SegmentedControl<Difficulty> difficulty = new SegmentedControl<>(List.of(Difficulty.values()),
            ReviewLabels::difficulty);
    private final FlowPane conceptChips = new FlowPane(8, 8);
    private final Label conceptNote = hint("");
    private final Map<String, ToggleChip> conceptToggles = new LinkedHashMap<>();
    private final Set<String> chosenConcepts = new LinkedHashSet<>();
    private final VBox typeFields = new VBox(22);
    private final SourcePassageView source;

    private final Label promptError = error();
    private final Label answerError = error();
    private final Label optionsError = error();
    private final Label explanationError = error();
    private final Label footerError = error();
    private final Set<ItemRules.Field> touched = EnumSet.noneOf(ItemRules.Field.class);
    /** Wrong options just added and not yet typed in or left: no complaint about them until then. */
    private final Set<TextField> freshOptions = new HashSet<>();
    private boolean attempted;
    private boolean saving;
    private List<ItemRules.Problem> problems = List.of();
    private final Button primary = new Button();
    private final Button secondary = new Button();
    private final String initialState;

    /**
     * @param existing      the question to edit, or null to write a new one
     * @param modules       the course's modules
     * @param concepts      the course's concepts
     * @param presetModule  for a new question, the module to put it in (null for the whole course)
     */
    ItemEditorDialog(ReviewQueueTab tab, ItemDetail existing, List<CourseModule> modules, List<Concept> concepts,
                     String presetModule) {
        this.tab = tab;
        this.existing = existing;
        this.concepts = List.copyOf(concepts);
        LearningItem item = existing == null ? null : existing.item();
        type = item == null ? ItemType.MCQ : item.type();

        typeControl = new SegmentedControl<>(TYPES, ReviewLabels::type);
        typeControl.setValue(type);
        typeControl.setAccessibleText("Question type");
        typeControl.valueProperty().addListener((obs, old, value) -> {
            if (value != null && value != type) {
                type = value;
                buildTypeFields();
                validate();
            }
        });

        List<Place> places = new ArrayList<>();
        for (CourseModule module : modules) {
            places.add(new Place(module.id(), module.title()));
        }
        places.add(new Place(null, "Whole course"));
        place.getItems().setAll(places);
        place.setConverter(new StringConverter<>() {
            @Override
            public String toString(Place p) {
                return p == null ? "" : p.title();
            }

            @Override
            public Place fromString(String text) {
                return null;
            }
        });
        String moduleId = item != null ? item.moduleId() : presetModule != null ? presetModule
                : modules.isEmpty() ? null : modules.getFirst().id();
        place.setValue(places.stream().filter(p -> Objects.equals(p.moduleId(), moduleId)).findFirst().orElse(places.getLast()));
        place.setAccessibleText("Module");
        place.setMaxWidth(Double.MAX_VALUE);
        place.valueProperty().addListener((obs, old, value) -> showConcepts());

        insertBlank.getStyleClass().addAll("quiet", "small");
        insertBlank.setOnAction(event -> {
            prompt.replaceSelection(ItemType.CLOZE_BLANK);
            prompt.requestFocus();
        });
        shortAnswer.setPromptText("");
        truth.setAccessibleText("The statement is");
        addOption.getStyleClass().addAll("quiet", "small");
        addOption.setOnAction(event -> {
            TextField field = addOptionRow("");
            freshOptions.add(field);
            renderOptions();
            validate();
            field.requestFocus();
        });
        addPoint.getStyleClass().addAll("quiet", "small");
        addPoint.setOnAction(event -> {
            TextField field = addPointRow("");
            renderPoints();
            field.requestFocus();
        });
        explanation.setAccessibleText("Explanation");

        // Values.
        if (item != null) {
            prompt.setText(item.prompt());
            explanation.setText(item.explanation());
            difficulty.setValue(item.difficulty());
            switch (item.type()) {
                case MCQ, CLOZE -> shortAnswer.setText(item.answer());
                case TRUE_FALSE -> truth.setValue(item.answer().strip().equalsIgnoreCase("false") ? Truth.FALSE : Truth.TRUE);
                case SHORT_ANSWER -> {
                    ShortAnswerText parsed = ShortAnswerText.parse(item.answer());
                    longAnswer.setText(parsed.modelAnswer());
                    parsed.keyPoints().forEach(this::addPointRow);
                }
                case FLASHCARD -> longAnswer.setText(item.answer());
            }
            item.distractors().forEach(this::addOptionRow);
            existing.concepts().forEach(c -> chosenConcepts.add(c.id()));
        } else {
            difficulty.setValue(Difficulty.MEDIUM);
            truth.setValue(Truth.TRUE);
        }
        if (type != ItemType.MCQ || options.isEmpty()) {
            while (options.size() < ItemRules.MIN_WRONG_OPTIONS) {
                addOptionRow("");
            }
        }
        difficulty.setAccessibleText("Difficulty");
        conceptChips.getStyleClass().add("editor-concepts");
        // Rows wrap at about the form's width when the dialog sizes itself.
        conceptChips.setPrefWrapLength(540);

        for (TextInputControl input : List.of(prompt, shortAnswer, longAnswer, explanation)) {
            input.textProperty().addListener((obs, old, value) -> {
                touched.add(fieldOf(input));
                validate();
            });
        }
        truth.valueProperty().addListener((obs, old, value) -> validate());

        // Layout.
        VBox form = new VBox(22);
        form.getStyleClass().add("dialog-form");
        if (existing == null) {
            form.getChildren().addAll(field("Type", typeControl, null, null), field("Module", place, null, null));
        }
        form.getChildren().add(typeFields);
        form.getChildren().addAll(
                field("Explanation", explanation, explanationError, hint("Optional. Learners see it after answering.")),
                field("Difficulty", difficulty, null, null),
                field("Concepts", conceptChips, null, conceptNote));
        buildTypeFields();
        showConcepts();

        Node body = form;
        // Wide enough for the five question types side by side beside a scroll bar.
        double width = 720;
        if (existing != null && (existing.source() != null || item.origin() == ItemOrigin.AI)) {
            source = new SourcePassageView(item, existing.source(), tab::openPage);
            Label sourceLabel = fieldLabel("Source passage");
            VBox side = new VBox(10, sourceLabel, source);
            side.getStyleClass().add("editor-source");
            side.setPrefWidth(380);
            side.setMinWidth(320);
            form.setPrefWidth(560);
            HBox.setHgrow(form, Priority.ALWAYS);
            HBox columns = new HBox(32, form, side);
            body = columns;
            width = 1040;
        } else {
            source = null;
        }

        footerError.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(footerError, Priority.ALWAYS);
        Button cancel = new Button("Cancel");
        primary.getStyleClass().add("primary");
        primary.setMinWidth(Region.USE_PREF_SIZE);
        secondary.setMinWidth(Region.USE_PREF_SIZE);
        boolean draftLike = item == null || item.status() == ItemStatus.DRAFT;
        if (draftLike) {
            secondary.setText("Save draft");
            secondary.setOnAction(event -> save(false));
            primary.setText("Save and approve");
            primary.setOnAction(event -> save(true));
        } else {
            primary.setText("Save changes");
            primary.setOnAction(event -> save(false));
        }
        modal = new ReviewModal(existing == null ? "New question" : "Edit question", width)
                .owner(this)
                .subtitle(existing == null ? "Write it yourself. Save it as a draft, or approve it straight away."
                        : ReviewLabels.type(item.type()) + " · " + ReviewLabels.origin(item.origin()))
                .body(body);
        if (draftLike) {
            modal.footer(footerError, cancel, secondary, primary);
        } else {
            modal.footer(footerError, cancel, primary);
        }
        cancel.setOnAction(event -> requestClose());
        modal.setOnCloseRequest(this::requestClose);
        modal.getStylesheets().add(ReviewQueueTab.STYLESHEET);
        modal.setInitialFocus(existing == null ? typeControl.button(type) : prompt);
        modal.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isShortcutDown()) {
                event.consume();
                primary.fire();
            }
        });
        validate();
        initialState = state();
    }

    void open(Node anchor) {
        modal.open(anchor);
    }

    ReviewModal modal() {
        return modal;
    }

    // ---- what tests and the queue use ------------------------------------------------------------------

    TextArea promptField() {
        return prompt;
    }

    /** The correct answer (multiple choice) or the missing words (fill in the blank). */
    TextField answerField() {
        return shortAnswer;
    }

    /** The back of a flashcard or a short answer's model answer. */
    TextArea longAnswerField() {
        return longAnswer;
    }

    List<TextField> optionFields() {
        return List.copyOf(options);
    }

    List<TextField> pointFields() {
        return List.copyOf(points);
    }

    Button addOptionButton() {
        return addOption;
    }

    Button addPointButton() {
        return addPoint;
    }

    TextArea explanationField() {
        return explanation;
    }

    SegmentedControl<ItemType> typeControl() {
        return typeControl;
    }

    SegmentedControl<Truth> truthControl() {
        return truth;
    }

    Button primaryButton() {
        return primary;
    }

    Button secondaryButton() {
        return secondary;
    }

    SourcePassageView sourceView() {
        return source;
    }

    /** Current problems, in the order saving would report them. */
    List<ItemRules.Problem> problems() {
        return problems;
    }

    /** The visible message under a part of the form, or null. */
    String shownError(ItemRules.Field field) {
        Label label = switch (field) {
            case PROMPT -> promptError;
            case ANSWER -> answerError;
            case OPTIONS -> optionsError;
            case EXPLANATION -> explanationError;
        };
        return label.isVisible() ? label.getText() : null;
    }

    String footerErrorText() {
        return footerError.isVisible() ? footerError.getText() : null;
    }

    /** The content the form holds now. */
    ItemContent content() {
        String answer = switch (type) {
            case MCQ, CLOZE -> shortAnswer.getText();
            case TRUE_FALSE -> truth.getValue() == Truth.FALSE ? "false" : "true";
            case SHORT_ANSWER -> new ShortAnswerText(longAnswer.getText(),
                    points.stream().map(TextField::getText).toList()).compose();
            case FLASHCARD -> longAnswer.getText();
        };
        List<String> distractors = type == ItemType.MCQ ? options.stream().map(TextField::getText).toList() : List.of();
        Set<String> tags = new LinkedHashSet<>();
        conceptToggles.forEach((id, chip) -> {
            if (chip.isSelected()) {
                tags.add(id);
            }
        });
        return new ItemContent(type, prompt.getText(), answer, explanation.getText(), distractors, difficulty.getValue(), tags);
    }

    /** True when something differs from what the dialog opened with. */
    boolean isDirty() {
        return !state().equals(initialState);
    }

    // ---- building ------------------------------------------------------------------------------------

    private void buildTypeFields() {
        promptLabel.setText(switch (type) {
            case MCQ, SHORT_ANSWER -> "Question";
            case TRUE_FALSE -> "Statement";
            case FLASHCARD -> "Front";
            case CLOZE -> "Sentence";
        });
        prompt.setAccessibleText(promptLabel.getText());
        promptHint.setText(type == ItemType.CLOZE ? "Mark the missing words with " + ItemType.CLOZE_BLANK + "." : "");
        HBox promptHead = new HBox(8, promptLabel);
        promptHead.setAlignment(Pos.CENTER_LEFT);
        if (type == ItemType.CLOZE) {
            Region gap = new Region();
            HBox.setHgrow(gap, Priority.ALWAYS);
            promptHead.getChildren().addAll(gap, insertBlank);
        }
        VBox promptField = new VBox(8, promptHead, prompt, promptError);
        if (type == ItemType.CLOZE) {
            promptField.getChildren().add(2, promptHint);
        }
        List<Node> fields = new ArrayList<>();
        fields.add(promptField);
        switch (type) {
            case MCQ -> {
                shortAnswer.setAccessibleText("Correct answer");
                fields.add(field("Correct answer", shortAnswer, answerError, null));
                renderOptions();
                fields.add(field("Wrong options", new VBox(8, optionRows, addOption), optionsError,
                        hint("Two to four. Options are shuffled when shown.")));
            }
            case TRUE_FALSE -> fields.add(field("The statement is", truth, answerError, null));
            case CLOZE -> {
                shortAnswer.setAccessibleText("Missing words");
                fields.add(field("Missing words", shortAnswer, answerError, null));
            }
            case SHORT_ANSWER -> {
                longAnswer.setAccessibleText("Model answer");
                fields.add(field("Model answer", longAnswer, answerError, null));
                renderPoints();
                fields.add(field("Key points", new VBox(8, pointRows, addPoint), null,
                        hint("Optional. What a good answer has to mention.")));
            }
            case FLASHCARD -> {
                longAnswer.setAccessibleText("Back");
                fields.add(field("Back", longAnswer, answerError, null));
            }
        }
        typeFields.getChildren().setAll(fields);
    }

    private TextField addOptionRow(String value) {
        TextField field = new TextField(value);
        field.setAccessibleText("Wrong option");
        field.textProperty().addListener((obs, old, text) -> {
            freshOptions.remove(field);
            touched.add(ItemRules.Field.OPTIONS);
            validate();
        });
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused && freshOptions.remove(field)) {
                validate();
            }
        });
        options.add(field);
        return field;
    }

    private void renderOptions() {
        List<Node> rows = new ArrayList<>();
        for (TextField field : options) {
            IconButton remove = new IconButton(Feather.X, "Remove this option").small();
            remove.setDisable(options.size() <= ItemRules.MIN_WRONG_OPTIONS);
            remove.setOnAction(event -> {
                options.remove(field);
                freshOptions.remove(field);
                touched.add(ItemRules.Field.OPTIONS);
                renderOptions();
                validate();
            });
            HBox.setHgrow(field, Priority.ALWAYS);
            HBox row = new HBox(8, Icons.of(Feather.CIRCLE, 14), field, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("option-row");
            rows.add(row);
        }
        optionRows.getChildren().setAll(rows);
        addOption.setDisable(options.size() >= ItemRules.MAX_WRONG_OPTIONS);
    }

    private TextField addPointRow(String value) {
        TextField field = new TextField(value);
        field.setAccessibleText("Key point");
        field.textProperty().addListener((obs, old, text) -> validate());
        points.add(field);
        return field;
    }

    private void renderPoints() {
        List<Node> rows = new ArrayList<>();
        for (TextField field : points) {
            IconButton remove = new IconButton(Feather.X, "Remove this key point").small();
            remove.setOnAction(event -> {
                points.remove(field);
                renderPoints();
                validate();
            });
            HBox.setHgrow(field, Priority.ALWAYS);
            Label bullet = new Label("•");
            bullet.getStyleClass().add("text-muted");
            HBox row = new HBox(8, bullet, field, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            rows.add(row);
        }
        pointRows.getChildren().setAll(rows);
    }

    private void showConcepts() {
        conceptToggles.forEach((id, chip) -> {
            if (chip.isSelected()) {
                chosenConcepts.add(id);
            } else {
                chosenConcepts.remove(id);
            }
        });
        String moduleId = existing != null ? existing.item().moduleId()
                : place.getValue() == null ? null : place.getValue().moduleId();
        conceptToggles.clear();
        conceptChips.getChildren().clear();
        for (Concept concept : concepts) {
            boolean here = concept.moduleId() == null || Objects.equals(concept.moduleId(), moduleId);
            if (here || chosenConcepts.contains(concept.id())) {
                ToggleChip chip = new ToggleChip(concept.name());
                chip.setSelected(chosenConcepts.contains(concept.id()));
                conceptToggles.put(concept.id(), chip);
                conceptChips.getChildren().add(chip);
            }
        }
        boolean none = conceptToggles.isEmpty();
        conceptChips.setVisible(!none);
        conceptChips.setManaged(!none);
        conceptNote.setText(none ? "This module has no key concepts to tag it with." : "");
        conceptNote.setVisible(none);
        conceptNote.setManaged(none);
    }

    // ---- checking and saving ---------------------------------------------------------------------------

    private void validate() {
        ItemContent content = content();
        problems = ItemRules.check(type, content.prompt(), content.answer(), content.explanation(), content.distractors());
        show(promptError, ItemRules.Field.PROMPT);
        show(answerError, ItemRules.Field.ANSWER);
        show(explanationError, ItemRules.Field.EXPLANATION);
        boolean optionsChecked = attempted || touched.contains(ItemRules.Field.OPTIONS);
        ItemRules.Problem optionProblem = null;
        for (ItemRules.Problem problem : problems) {
            if (problem.field() == ItemRules.Field.OPTIONS && optionsChecked && !isFreshOption(problem.option())) {
                optionProblem = problem;
                break;
            }
        }
        optionsError.setText(optionProblem == null ? "" : optionProblem.message());
        optionsError.setVisible(optionProblem != null);
        optionsError.setManaged(optionProblem != null);
        for (int i = 0; i < options.size(); i++) {
            int index = i;
            boolean bad = optionsChecked && !isFreshOption(index)
                    && problems.stream().anyMatch(p -> p.field() == ItemRules.Field.OPTIONS && p.option() == index);
            options.get(i).pseudoClassStateChanged(INVALID, bad);
        }
        prompt.pseudoClassStateChanged(INVALID, promptError.isVisible());
        shortAnswer.pseudoClassStateChanged(INVALID, answerError.isVisible());
        longAnswer.pseudoClassStateChanged(INVALID, answerError.isVisible());
        explanation.pseudoClassStateChanged(INVALID, explanationError.isVisible());
        if (attempted && problems.isEmpty()) {
            setFooterError(null);
        } else if (attempted) {
            setFooterError("Fix what's marked to save this question.");
        }
        if (source != null) {
            source.setEvidence(SupportingText.evidence(type, content.prompt(), content.answer()));
        }
    }

    private boolean isFreshOption(int index) {
        return index >= 0 && index < options.size() && freshOptions.contains(options.get(index));
    }

    private void show(Label label, ItemRules.Field field) {
        ItemRules.Problem problem = ItemRules.first(problems, field);
        boolean visible = problem != null && (attempted || touched.contains(field));
        label.setText(visible ? problem.message() : "");
        label.setVisible(visible);
        label.setManaged(visible);
    }

    private void save(boolean approve) {
        if (saving) {
            return;
        }
        attempted = true;
        freshOptions.clear();
        validate();
        if (!problems.isEmpty()) {
            focusProblem(problems.getFirst());
            return;
        }
        saving = true;
        primary.setDisable(true);
        secondary.setDisable(true);
        String moduleId = place.getValue() == null ? null : place.getValue().moduleId();
        tab.saveItem(existing == null ? null : existing.item().id(), moduleId, content(), approve, () -> {
            saving = false;
            modal.close();
        }, message -> {
            saving = false;
            primary.setDisable(false);
            secondary.setDisable(false);
            setFooterError(message);
        });
    }

    private void focusProblem(ItemRules.Problem problem) {
        Node target = switch (problem.field()) {
            case PROMPT -> prompt;
            case ANSWER -> type == ItemType.SHORT_ANSWER || type == ItemType.FLASHCARD ? longAnswer
                    : type == ItemType.TRUE_FALSE ? truth : shortAnswer;
            case OPTIONS -> problem.option() >= 0 && problem.option() < options.size() ? options.get(problem.option())
                    : addOption.isDisabled() ? options.getLast() : addOption;
            case EXPLANATION -> explanation;
        };
        target.requestFocus();
    }

    private void setFooterError(String message) {
        footerError.setText(message == null ? "" : message);
        footerError.setVisible(message != null);
        footerError.setManaged(message != null);
    }

    private void requestClose() {
        if (saving) {
            return;
        }
        if (!isDirty()) {
            modal.close();
            return;
        }
        ConfirmDialog.of("Discard your changes?", "What you changed in this question is lost.", "Discard changes")
                .cancelLabel("Keep editing").danger().show(modal.card()).thenAccept(discard -> {
                    if (discard) {
                        modal.close();
                    }
                });
    }

    private String state() {
        ItemContent c = content();
        String module = place.getValue() == null ? "" : String.valueOf(place.getValue().moduleId());
        return c.type() + "\u0000" + c.prompt() + "\u0000" + c.answer() + "\u0000" + c.explanation() + "\u0000"
                + c.distractors() + "\u0000" + c.difficulty() + "\u0000" + c.conceptIds().stream().sorted().toList()
                + "\u0000" + module;
    }

    private ItemRules.Field fieldOf(TextInputControl input) {
        if (input == prompt) {
            return ItemRules.Field.PROMPT;
        }
        if (input == explanation) {
            return ItemRules.Field.EXPLANATION;
        }
        return ItemRules.Field.ANSWER;
    }

    // ---- small builders ------------------------------------------------------------------------------

    private static VBox field(String label, Node control, Label error, Label hint) {
        VBox field = new VBox(8, fieldLabel(label), control);
        if (hint != null) {
            field.getChildren().add(hint);
        }
        if (error != null) {
            field.getChildren().add(error);
        }
        return field;
    }

    private static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("text-caption");
        label.setWrapText(true);
        return label;
    }

    private static Label error() {
        Label label = new Label();
        label.getStyleClass().addAll("text-caption", "text-danger", "field-error");
        label.setWrapText(true);
        label.setVisible(false);
        label.setManaged(false);
        return label;
    }

    private static TextArea area(int rows) {
        TextArea area = new TextArea();
        area.setWrapText(true);
        area.setPrefRowCount(rows);
        area.getStyleClass().add("editor-area");
        return area;
    }
}
