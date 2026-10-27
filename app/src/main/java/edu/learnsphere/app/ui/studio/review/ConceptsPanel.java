package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The course's key concepts, module by module, as chips the creator can add, rename and remove. "Find key
 * concepts" asks the study assistant to read a module's PDFs; concepts it found carry a quill mark until
 * someone renames them. Collapses to one line so the review queue stays in view.
 */
final class ConceptsPanel extends VBox {

    /** What a module row says under its title while or after the study assistant looks for concepts. */
    record Status(Kind kind, String text) {
        enum Kind { WORKING, DONE, PROBLEM }
    }

    /** The data the panel shows. */
    record Data(List<CourseModule> modules, Set<String> readyModuleIds, Set<String> pdfModuleIds, List<Concept> concepts,
                Set<String> extractedIds) {
    }

    private final ReviewQueueTab tab;
    private final String courseId;
    private final Label summary = new Label();
    private final Button toggle = new Button();
    private final VBox rows = new VBox();
    private final Map<String, Status> statuses = new HashMap<>();
    private Data data;
    private Data pending;
    private boolean expanded;
    private boolean expandedChosen;
    private int editing;

    ConceptsPanel(ReviewQueueTab tab, String courseId) {
        this.tab = tab;
        this.courseId = courseId;
        getStyleClass().add("concepts-panel");
        Label title = new Label("Key concepts");
        title.getStyleClass().add("text-heading");
        summary.getStyleClass().add("text-caption");
        summary.setWrapText(true);
        VBox titles = new VBox(2, title, summary);
        HBox.setHgrow(titles, Priority.ALWAYS);
        toggle.getStyleClass().addAll("quiet", "small", "neutral");
        toggle.setMinWidth(Region.USE_PREF_SIZE);
        toggle.setOnAction(event -> {
            expandedChosen = true;
            setExpanded(!expanded);
        });
        HBox head = new HBox(16, titles, toggle);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("concepts-head");
        rows.getStyleClass().add("concepts-rows");
        getChildren().addAll(head, rows);
        setExpanded(false);
    }

    /** Shows {@code next}; expands the first time when there is nothing to review yet. */
    void update(Data next, boolean draftsWaiting) {
        if (!expandedChosen) {
            setExpanded(!draftsWaiting);
        }
        if (editing > 0) {
            pending = next;
            return;
        }
        data = next;
        render();
    }

    boolean isExpanded() {
        return expanded;
    }

    void setExpanded(boolean value) {
        expanded = value;
        rows.setVisible(value);
        rows.setManaged(value);
        toggle.setText(value ? "Hide" : "Show");
        toggle.setGraphic(Icons.of(value ? Feather.CHEVRON_UP : Feather.CHEVRON_DOWN, 14));
        toggle.setAccessibleText(value ? "Hide key concepts" : "Show key concepts");
    }

    /** Shows what the study assistant is doing for a module, or what came of it; null clears it. */
    void setStatus(String moduleId, Status status) {
        if (status == null) {
            statuses.remove(moduleId);
        } else {
            statuses.put(moduleId, status);
        }
        if (status != null && status.kind() != Status.Kind.WORKING) {
            expandedChosen = true;
            setExpanded(true);
        }
        if (editing == 0 && data != null) {
            render();
        }
    }

    Status status(String moduleId) {
        return statuses.get(moduleId);
    }

    /** The concept chips' names in a module, in order (tests). */
    List<String> conceptNames(String moduleId) {
        if (data == null) {
            return List.of();
        }
        return data.concepts().stream().filter(c -> Objects.equals(c.moduleId(), moduleId)).map(Concept::name).toList();
    }

    // ---- rendering -----------------------------------------------------------------------------------

    private void render() {
        List<Concept> concepts = data.concepts();
        long found = concepts.stream().filter(c -> data.extractedIds().contains(c.id())).count();
        if (concepts.isEmpty()) {
            summary.setText(data.modules().isEmpty() ? "Key concepts are found module by module."
                    : "None yet. Find them in a module's PDFs, or add your own.");
        } else {
            String text = Formats.count(concepts.size(), "concept", "concepts");
            if (data.modules().size() > 1) {
                text += " across " + data.modules().size() + " modules";
            }
            if (found > 0) {
                text += " · " + found + " found by AI";
            }
            summary.setText(text);
        }
        List<Node> children = new ArrayList<>();
        for (CourseModule module : data.modules()) {
            children.add(moduleRow(module.id(), module.title(), data.readyModuleIds().contains(module.id())));
        }
        boolean courseWide = concepts.stream().anyMatch(c -> c.moduleId() == null);
        if (courseWide || data.modules().isEmpty()) {
            children.add(moduleRow(null, data.modules().isEmpty() ? "This course" : "Whole course", false));
        }
        if (!children.isEmpty()) {
            children.getFirst().getStyleClass().add("first");
        }
        rows.getChildren().setAll(children);
    }

    private Node moduleRow(String moduleId, String title, boolean ready) {
        Label name = new Label(title);
        name.getStyleClass().add("medium");
        name.setWrapText(true);
        VBox text = new VBox(4, name, statusLine(moduleId, ready));
        text.setMinWidth(200);
        text.setPrefWidth(240);
        text.setMaxWidth(240);

        FlowPane chips = new FlowPane(8, 8);
        chips.getStyleClass().add("concept-chips");
        HBox.setHgrow(chips, Priority.ALWAYS);
        chips.setPrefWrapLength(520);
        for (Concept concept : data.concepts()) {
            if (Objects.equals(concept.moduleId(), moduleId)) {
                chips.getChildren().add(chip(concept, chips));
            }
        }
        chips.getChildren().add(addButton(moduleId, chips));

        HBox row = new HBox(20, text, chips);
        row.setAlignment(Pos.TOP_LEFT);
        row.getStyleClass().add("concept-row");
        if (moduleId != null) {
            Button find = new Button("Find key concepts", Icons.of(Feather.SEARCH, 14));
            find.getStyleClass().add("small");
            find.setMinWidth(Region.USE_PREF_SIZE);
            Status status = statuses.get(moduleId);
            find.setDisable(!ready || status != null && status.kind() == Status.Kind.WORKING);
            find.setAccessibleText("Find key concepts in " + title);
            find.setOnAction(event -> tab.findConcepts(moduleId));
            row.getChildren().add(find);
        }
        return row;
    }

    private Node statusLine(String moduleId, boolean ready) {
        Status status = moduleId == null ? null : statuses.get(moduleId);
        if (status != null) {
            Label line = new Label(status.text());
            line.setWrapText(true);
            switch (status.kind()) {
                case WORKING -> {
                    line.getStyleClass().add("text-caption");
                    HBox working = new HBox(8, new ActivityIndicator(14), line);
                    working.setAlignment(Pos.CENTER_LEFT);
                    return working;
                }
                case DONE -> {
                    line.getStyleClass().addAll("text-caption", "text-success");
                    line.setGraphic(Icons.of(Feather.CHECK, 12));
                }
                case PROBLEM -> line.getStyleClass().addAll("text-caption", "text-danger");
            }
            return line;
        }
        List<Concept> mine = data.concepts().stream().filter(c -> Objects.equals(c.moduleId(), moduleId)).toList();
        boolean waiting = moduleId != null && !ready && data.pdfModuleIds().contains(moduleId);
        String text;
        if (moduleId != null && !ready && mine.isEmpty()) {
            text = waiting ? "Its PDFs are getting ready. Find key concepts once they are."
                    : "Add a PDF to this module to find its key concepts.";
        } else if (mine.isEmpty()) {
            text = "No key concepts";
        } else {
            long found = mine.stream().filter(c -> data.extractedIds().contains(c.id())).count();
            long yours = mine.size() - found;
            List<String> parts = new ArrayList<>();
            if (found > 0) {
                parts.add(found + " found by AI");
            }
            if (yours > 0) {
                parts.add(yours + (found > 0 ? " yours" : yours == 1 ? " concept" : " concepts"));
            }
            text = String.join(" · ", parts);
        }
        Label line = new Label(text);
        line.getStyleClass().add("text-caption");
        line.setWrapText(true);
        if (moduleId == null || ready || mine.isEmpty()) {
            return line;
        }
        // Why "Find key concepts" is off for this module.
        Label why = new Label(waiting ? "Its PDFs are getting ready" : "Add a PDF to find more");
        why.getStyleClass().addAll("text-caption", "text-muted");
        why.setWrapText(true);
        return new VBox(2, line, why);
    }

    private Node chip(Concept concept, FlowPane chips) {
        boolean ai = data.extractedIds().contains(concept.id());
        Button name = new Button(concept.name());
        name.getStyleClass().add("concept-name");
        name.setMnemonicParsing(false);
        if (ai) {
            name.setGraphic(Icons.of(Feather.FEATHER, 12));
        }
        name.setAccessibleText("Rename " + concept.name() + (ai ? ", found by AI" : ""));
        IconButton remove = new IconButton(Feather.X, "Remove " + concept.name()).small();
        remove.getStyleClass().add("concept-remove");
        HBox chip = new HBox(0, name, remove);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.getStyleClass().addAll("concept-chip", ai ? "found" : "yours");
        name.setOnAction(event -> rename(concept, chip, chips));
        remove.setOnAction(event -> ConfirmDialog.of("Remove “" + concept.name() + "”?",
                        "Questions tagged with it lose the tag. The questions stay.", "Remove concept")
                .danger().show(this).thenAccept(confirmed -> {
                    if (confirmed) {
                        tab.deleteConcept(concept);
                    }
                }));
        return chip;
    }

    private Node addButton(String moduleId, FlowPane chips) {
        Button add = new Button("Add concept", Icons.of(Feather.PLUS, 12));
        add.getStyleClass().add("concept-add");
        add.setAccessibleText("Add a concept");
        add.setOnAction(event -> {
            TextField field = editor("", "Concept name");
            int at = chips.getChildren().indexOf(add);
            chips.getChildren().set(at, field);
            startEditing(field, text -> {
                if (text.isBlank()) {
                    finishEditing();
                    return;
                }
                tab.addConcept(moduleId, text, this::finishEditing);
            });
        });
        return add;
    }

    private void rename(Concept concept, Node chip, FlowPane chips) {
        TextField field = editor(concept.name(), "Concept name");
        int at = chips.getChildren().indexOf(chip);
        chips.getChildren().set(at, field);
        startEditing(field, text -> {
            if (text.isBlank() || text.strip().equals(concept.name())) {
                finishEditing();
                return;
            }
            tab.renameConcept(concept, text, this::finishEditing);
        });
    }

    private TextField editor(String value, String prompt) {
        TextField field = new TextField(value);
        field.setPromptText(prompt);
        field.getStyleClass().add("concept-editor");
        field.setPrefColumnCount(Math.max(10, value.length() + 2));
        field.setAccessibleText(prompt);
        return field;
    }

    /** Edits in place: Enter saves, Escape cancels, leaving the field saves. */
    private void startEditing(TextField field, java.util.function.Consumer<String> save) {
        editing++;
        boolean[] done = {false};
        Runnable commit = () -> {
            if (!done[0]) {
                done[0] = true;
                save.accept(field.getText());
            }
        };
        field.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                commit.run();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                done[0] = true;
                finishEditing();
            }
        });
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commit.run();
            }
        });
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
    }

    private void finishEditing() {
        editing = Math.max(0, editing - 1);
        if (editing == 0) {
            if (pending != null) {
                data = pending;
                pending = null;
            }
            if (data != null) {
                render();
            }
        }
    }
}
