package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.service.ModuleSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Modules tab: the course's modules in the order learners follow, each with its PDFs, questions and
 * drafts waiting for review. Modules are added at the end, renamed and given a summary in place, and deleted
 * (after asking when they hold PDFs or questions, with Undo when they are empty). They are reordered by
 * dragging their handle or with Option/Alt+Up and Down on a focused row; the new order is saved at once.
 */
final class ModulesSection extends StudioSection {

    /** What a dragged module carries: its id. */
    static final DataFormat MODULE = new DataFormat("application/x-learnsphere-module");
    static final String TITLE_NEEDED = "Enter a title for the module.";

    private static final int MAX_TITLE = 200;
    private static final int MAX_SUMMARY = 2_000;

    private final VBox root = new VBox(16);
    private final VBox list = new VBox();
    private final TextField addField = new TextField();
    private final Label addError = StudioUi.fieldError();
    private final Button addButton = new Button("Add module", Icons.of(Feather.PLUS, 14));
    private final Refresher<List<ModuleSummary>> refresher;
    private List<ModuleSummary> modules = List.of();
    private String editingId;
    private String draggingId;
    private boolean stale;
    private boolean loaded;
    /** The order the creator last chose, waiting to be saved; null when it is saved. */
    private List<String> wantedOrder;
    private boolean savingOrder;

    ModulesSection(StudioHost host) {
        super(host);
        root.getStyleClass().add("studio-modules");
        list.getStyleClass().add("module-list");
        addField.setPromptText("Module title, for example Isomerism");
        addField.getStyleClass().add("module-add-field");
        addField.setAccessibleText("New module title");
        StudioUi.limit(addField, MAX_TITLE);
        addField.setOnAction(event -> add());
        addField.textProperty().addListener((obs, old, text) -> {
            addField.getStyleClass().remove("invalid");
            StudioUi.showFieldError(addError, null);
        });
        HBox.setHgrow(addField, Priority.ALWAYS);
        addButton.getStyleClass().addAll("primary", "module-add-button");
        addButton.setMinWidth(Region.USE_PREF_SIZE);
        addButton.setOnAction(event -> add());
        VBox skeleton = new VBox(14);
        skeleton.getStyleClass().add("studio-panel");
        for (int i = 0; i < 4; i++) {
            skeleton.getChildren().add(Skeleton.block(-1, 44));
        }
        root.getChildren().setAll(skeleton);
        refresher = new Refresher<>(disposer, () -> workspace().courses().get(courseId()).modules(), this::render,
                message -> root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                        "The modules couldn't load", message.text()).primary("Try again", this::reload)));
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        reload();
        on(CourseChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
        on(DocumentChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
        on(ItemsChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
    }

    private void reload() {
        refresher.request();
    }

    /** The modules on screen, in order (tests). */
    List<String> titles() {
        return modules.stream().map(ModuleSummary::title).toList();
    }

    // -------------------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------------------

    private void render(List<ModuleSummary> latest) {
        if (editingId != null || draggingId != null || savingOrder) {
            // Keep what the creator is working on (or the order being saved); reload once that is done.
            stale = true;
            return;
        }
        stale = false;
        String focusedId = focusedModuleId();
        modules = List.copyOf(latest);
        list.getChildren().clear();
        for (int i = 0; i < modules.size(); i++) {
            list.getChildren().add(row(modules.get(i), i));
        }
        if (!loaded) {
            loaded = true;
            build();
        } else {
            root.getChildren().setAll(content());
        }
        if (focusedId != null) {
            focusRow(focusedId);
        }
    }

    private void build() {
        root.getChildren().setAll(content());
    }

    private List<Node> content() {
        StackPane plus = new StackPane(Icons.of(Feather.PLUS, 14));
        plus.getStyleClass().add("module-add-number");
        HBox addRow = new HBox(12, plus, addField, addButton);
        addRow.setAlignment(Pos.CENTER_LEFT);
        VBox add = new VBox(6, addRow, addError);
        add.getStyleClass().add("module-add");
        addError.setPadding(new javafx.geometry.Insets(0, 0, 0, 40));

        if (modules.isEmpty()) {
            EmptyState empty = new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Plan the course in modules",
                    "Modules set the order learners follow. Each one groups its PDFs and the questions made from them.");
            empty.getStyleClass().add("modules-empty");
            VBox panel = StudioUi.panel(null, null, empty, add);
            return List.of(panel);
        }
        Label keys = new Label("Drag a module by its handle, or press " + StudioFormats.moveKeys()
                + " to move the one you've selected.");
        keys.getStyleClass().add("module-keys");
        keys.setWrapText(true);
        VBox panel = StudioUi.panel(null, null, list, add);
        panel.getStyleClass().add("tight");
        return List.of(heading(), panel, keys);
    }

    private Node heading() {
        int items = modules.stream().mapToInt(ModuleSummary::itemCount).sum();
        int drafts = modules.stream().mapToInt(ModuleSummary::draftItemCount).sum();
        List<String> parts = new ArrayList<>();
        parts.add(Formats.count(modules.size(), "module", "modules"));
        parts.add(Formats.count(items, "approved question", "approved questions"));
        if (drafts > 0) {
            parts.add(Formats.count(drafts, "draft", "drafts") + " to review");
        }
        Label summary = new Label(String.join(" · ", parts));
        summary.getStyleClass().add("text-muted");
        return summary;
    }

    private HBox row(ModuleSummary module, int index) {
        Label handle = new Label(null, Icons.of(Feather.MENU, 16));
        handle.getStyleClass().add("drag-handle");
        handle.setAccessibleText("Drag to move " + module.title());
        Label number = new Label(Integer.toString(index + 1));
        number.getStyleClass().add("module-number");
        number.setMinWidth(Region.USE_PREF_SIZE);

        Label title = new Label(module.title());
        title.getStyleClass().addAll("module-row-title", "medium");
        title.setMnemonicParsing(false);
        title.setWrapText(true);
        String summaryText = module.module().summary();
        Label summary = new Label(summaryText.isBlank() ? "No summary" : summaryText);
        summary.getStyleClass().add("module-row-summary");
        if (summaryText.isBlank()) {
            summary.getStyleClass().add("empty");
        }
        summary.setWrapText(true);
        summary.setMnemonicParsing(false);
        VBox text = new VBox(2, title, summary);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        Label counts = new Label(Formats.count(module.documentCount(), "PDF", "PDFs") + " · "
                + Formats.count(module.itemCount(), "question", "questions"));
        counts.getStyleClass().add("module-row-counts");
        counts.setMinWidth(Region.USE_PREF_SIZE);
        HBox trailing = new HBox(10, counts);
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.setMinWidth(Region.USE_PREF_SIZE);
        if (module.draftItemCount() > 0) {
            trailing.getChildren().add(new Chip(Formats.count(module.draftItemCount(), "draft", "drafts"), Tone.WARNING));
        }

        IconButton edit = new IconButton(Feather.EDIT_2, "Rename " + module.title()).small();
        edit.getStyleClass().add("module-edit");
        edit.setOnAction(event -> startEditing(module.id()));
        IconButton delete = new IconButton(Feather.TRASH_2, "Delete " + module.title()).small();
        delete.getStyleClass().add("module-delete");
        delete.setOnAction(event -> delete(module));

        HBox row = new HBox(14, handle, number, text, trailing, edit, delete);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("module-row");
        if (index == 0) {
            row.getStyleClass().add("first");
        }
        row.setUserData(module.id());
        row.setFocusTraversable(true);
        row.setAccessibleRole(AccessibleRole.LIST_ITEM);
        row.setAccessibleText("Module " + (index + 1) + ", " + module.title());
        row.addEventHandler(KeyEvent.KEY_PRESSED, event -> onRowKey(event, module));
        title.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                startEditing(module.id());
            }
        });
        dragAndDrop(row, handle, module.id());
        return row;
    }

    private void onRowKey(KeyEvent event, ModuleSummary module) {
        if (event.getTarget() != event.getSource() || editingId != null) {
            return;
        }
        int index = indexOf(module.id());
        if (event.isAltDown() && (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN)) {
            event.consume();
            move(index, index + (event.getCode() == KeyCode.UP ? -1 : 1));
            return;
        }
        switch (event.getCode()) {
            case UP, DOWN -> {
                event.consume();
                int next = index + (event.getCode() == KeyCode.UP ? -1 : 1);
                if (next >= 0 && next < modules.size()) {
                    focusRow(modules.get(next).id());
                }
            }
            case ENTER, F2 -> {
                event.consume();
                startEditing(module.id());
            }
            case DELETE, BACK_SPACE -> {
                event.consume();
                delete(module);
            }
            default -> {
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Reordering
    // -------------------------------------------------------------------------------------------------

    private void dragAndDrop(HBox row, Node handle, String moduleId) {
        handle.setOnDragDetected(event -> {
            if (editingId != null) {
                return;
            }
            Dragboard board = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(MODULE, moduleId);
            board.setContent(content);
            board.setDragView(row.snapshot(null, null), event.getX(), event.getY());
            startDrag(moduleId);
            row.getStyleClass().add("dragging");
            event.consume();
        });
        row.setOnDragOver(event -> {
            String dragged = draggedModule(event);
            if (dragged != null && !dragged.equals(moduleId)) {
                event.acceptTransferModes(TransferMode.MOVE);
                boolean above = event.getY() < row.getHeight() / 2;
                row.getStyleClass().removeAll("drop-above", "drop-below");
                row.getStyleClass().add(above ? "drop-above" : "drop-below");
            }
            event.consume();
        });
        row.setOnDragExited(event -> row.getStyleClass().removeAll("drop-above", "drop-below"));
        row.setOnDragDropped(event -> {
            String dragged = draggedModule(event);
            boolean done = false;
            row.getStyleClass().removeAll("drop-above", "drop-below");
            if (dragged != null && !dragged.equals(moduleId)) {
                int from = indexOf(dragged);
                int to = dropIndex(from, indexOf(moduleId), event.getY() < row.getHeight() / 2);
                draggingId = null;
                move(from, to);
                done = true;
            }
            event.setDropCompleted(done);
            event.consume();
        });
        row.setOnDragDone(event -> {
            row.getStyleClass().remove("dragging");
            if (draggingId != null) {
                draggingId = null;
                if (stale) {
                    reload();
                }
            }
        });
    }

    /** Marks {@code moduleId} as the module being dragged; the list holds still until it is dropped. */
    void startDrag(String moduleId) {
        draggingId = moduleId;
    }

    /** The module a drag over this list carries: one of this course's modules, or null for anything else. */
    private String draggedModule(DragEvent event) {
        if (draggingId != null) {
            return draggingId;
        }
        Object carried = event.getDragboard() == null ? null : event.getDragboard().getContent(MODULE);
        return carried instanceof String id && indexOf(id) >= 0 ? id : null;
    }

    /**
     * Where a module dropped on the row at {@code target} ends up: in that row's place when dropped on its
     * upper half, after it when dropped on its lower half. {@code from} is where the module was.
     */
    static int dropIndex(int from, int target, boolean above) {
        int to = above ? target : target + 1;
        return from < to ? to - 1 : to;
    }

    /** Moves the module at {@code from} to {@code to} and saves the new order. Out-of-range moves do nothing. */
    void move(int from, int to) {
        if (editingId != null || from < 0 || from >= modules.size() || to < 0 || to >= modules.size() || from == to) {
            return;
        }
        List<ModuleSummary> reordered = new ArrayList<>(modules);
        ModuleSummary moved = reordered.remove(from);
        reordered.add(to, moved);
        // Show the new order at once; the saved order follows.
        modules = reordered;
        list.getChildren().clear();
        for (int i = 0; i < modules.size(); i++) {
            list.getChildren().add(row(modules.get(i), i));
        }
        focusRow(moved.id());
        wantedOrder = reordered.stream().map(ModuleSummary::id).toList();
        if (!savingOrder) {
            saveOrder();
        }
    }

    /**
     * Saves the order the creator chose, one save at a time: moves made while a save runs are saved together
     * once it finishes, so the last order chosen is the one kept.
     */
    private void saveOrder() {
        List<String> ids = wantedOrder;
        savingOrder = true;
        run(() -> {
            workspace().courses().reorderModules(courseId(), ids);
            return null;
        }, ignored -> {
            if (wantedOrder != null && !wantedOrder.equals(ids)) {
                saveOrder();
                return;
            }
            orderSaved();
        }, message -> {
            orderSaved();
            error(message);
            reload();
        });
    }

    private void orderSaved() {
        wantedOrder = null;
        savingOrder = false;
        if (stale) {
            reload();
        }
    }

    /** True while a new order is being saved (tests). */
    boolean savingOrder() {
        return savingOrder;
    }

    // -------------------------------------------------------------------------------------------------
    // Adding, editing, deleting
    // -------------------------------------------------------------------------------------------------

    private void add() {
        String title = StudioUi.text(addField);
        if (title.isEmpty()) {
            addField.getStyleClass().add("invalid");
            StudioUi.showFieldError(addError, TITLE_NEEDED);
            addField.requestFocus();
            return;
        }
        addButton.setDisable(true);
        run(() -> workspace().courses().addModule(courseId(), title), module -> {
            addButton.setDisable(false);
            addField.clear();
            addField.requestFocus();
        }, message -> {
            addButton.setDisable(false);
            StudioUi.showFieldError(addError, message.text());
        });
    }

    private void startEditing(String moduleId) {
        if (editingId != null) {
            return;
        }
        int index = indexOf(moduleId);
        if (index < 0) {
            return;
        }
        ModuleSummary module = modules.get(index);
        editingId = moduleId;
        TextField title = new TextField(module.title());
        title.getStyleClass().add("module-title-field");
        title.setAccessibleText("Module title");
        StudioUi.limit(title, MAX_TITLE);
        TextField summary = new TextField(module.module().summary());
        summary.setPromptText("What learners will cover (optional)");
        summary.getStyleClass().add("module-summary-field");
        summary.setAccessibleText("Module summary");
        StudioUi.limit(summary, MAX_SUMMARY);
        Label error = StudioUi.fieldError();
        title.textProperty().addListener((obs, old, text) -> {
            title.getStyleClass().remove("invalid");
            StudioUi.showFieldError(error, null);
        });
        Label number = new Label(Integer.toString(index + 1));
        number.getStyleClass().add("module-number");
        number.setMinWidth(Region.USE_PREF_SIZE);
        VBox fields = new VBox(8, title, summary, error);
        HBox.setHgrow(fields, Priority.ALWAYS);
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().addAll("quiet", "neutral", "small");
        Button save = new Button("Save");
        save.getStyleClass().addAll("primary", "small", "module-save");
        HBox buttons = new HBox(8, cancel, save);
        buttons.setAlignment(Pos.TOP_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        Region gap = new Region();
        gap.setMinWidth(24);
        HBox editor = new HBox(14, gap, number, fields, buttons);
        editor.setAlignment(Pos.TOP_LEFT);
        editor.getStyleClass().addAll("module-row", "editing");
        if (index == 0) {
            editor.getStyleClass().add("first");
        }
        editor.setUserData(moduleId);

        Runnable finish = () -> {
            editingId = null;
            if (stale) {
                reload();
            } else {
                list.getChildren().set(indexOf(moduleId), row(module, indexOf(moduleId)));
            }
            focusRow(moduleId);
        };
        cancel.setOnAction(event -> finish.run());
        Runnable commit = () -> {
            String newTitle = StudioUi.text(title);
            String newSummary = StudioUi.text(summary);
            if (newTitle.isEmpty()) {
                title.getStyleClass().add("invalid");
                StudioUi.showFieldError(error, TITLE_NEEDED);
                title.requestFocus();
                return;
            }
            save.setDisable(true);
            run(() -> {
                var courses = workspace().courses();
                if (!newTitle.equals(module.title())) {
                    courses.renameModule(moduleId, newTitle);
                }
                if (!newSummary.equals(module.module().summary())) {
                    courses.setModuleSummary(moduleId, newSummary);
                }
                return null;
            }, ignored -> {
                editingId = null;
                stale = false;
                reload();
                Platform.runLater(() -> focusRow(moduleId));
            }, message -> {
                save.setDisable(false);
                StudioUi.showFieldError(error, message.text());
            });
        };
        save.setOnAction(event -> commit.run());
        title.setOnAction(event -> commit.run());
        summary.setOnAction(event -> commit.run());
        editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                finish.run();
            }
        });
        list.getChildren().set(index, editor);
        title.requestFocus();
        title.selectAll();
    }

    private void delete(ModuleSummary module) {
        if (editingId != null) {
            return;
        }
        boolean hasContent = module.documentCount() > 0 || module.itemCount() > 0 || module.draftItemCount() > 0;
        if (!hasContent) {
            deleteNow(module, true);
            return;
        }
        List<String> parts = new ArrayList<>();
        if (module.documentCount() > 0) {
            parts.add(Formats.count(module.documentCount(), "PDF", "PDFs"));
        }
        int questions = module.itemCount() + module.draftItemCount();
        if (questions > 0) {
            parts.add(Formats.count(questions, "question", "questions"));
        }
        boolean single = parts.size() == 1 && module.documentCount() + questions == 1;
        String message = "Its " + String.join(" and ", parts) + (single ? " stays" : " stay")
                + " in the course without a module.";
        ConfirmDialog.of("Delete “" + module.title() + "”?", message, "Delete module")
                .danger()
                .show(root)
                .thenAccept(confirmed -> {
                    if (confirmed) {
                        deleteNow(module, false);
                    }
                });
    }

    private void deleteNow(ModuleSummary module, boolean undoable) {
        int index = indexOf(module.id());
        List<String> order = modules.stream().map(ModuleSummary::id).toList();
        run(() -> {
            workspace().courses().deleteModule(module.id());
            return null;
        }, ignored -> {
            if (root.getScene() == null) {
                return;
            }
            if (undoable) {
                Toasts.show(root, Toasts.Kind.SUCCESS, "Deleted “" + module.title() + "”", "Undo",
                        () -> restore(module.module(), index, order));
            } else {
                success("Deleted “" + module.title() + "”");
            }
            focusNear(index);
        }, this::error);
    }

    /** Brings back an empty module that was just deleted, in its old place. */
    private void restore(CourseModule module, int index, List<String> order) {
        run(() -> {
            var courses = workspace().courses();
            CourseModule again = courses.addModule(courseId(), module.title());
            if (!module.summary().isBlank()) {
                courses.setModuleSummary(again.id(), module.summary());
            }
            List<String> ids = new ArrayList<>(order.stream().filter(id -> !id.equals(module.id())).toList());
            ids.add(Math.min(index, ids.size()), again.id());
            courses.reorderModules(courseId(), ids);
            return again;
        }, again -> {
        }, this::error);
    }

    // -------------------------------------------------------------------------------------------------
    // Focus
    // -------------------------------------------------------------------------------------------------

    private int indexOf(String moduleId) {
        for (int i = 0; i < modules.size(); i++) {
            if (modules.get(i).id().equals(moduleId)) {
                return i;
            }
        }
        return -1;
    }

    private String focusedModuleId() {
        var scene = root.getScene();
        if (scene == null) {
            return null;
        }
        for (Node n = scene.getFocusOwner(); n != null; n = n.getParent()) {
            if (n.getParent() == list && n.getUserData() instanceof String id) {
                return id;
            }
        }
        return null;
    }

    private void focusRow(String moduleId) {
        for (Node n : list.getChildren()) {
            if (Objects.equals(n.getUserData(), moduleId)) {
                n.requestFocus();
                return;
            }
        }
    }

    private void focusNear(int index) {
        if (list.getChildren().isEmpty()) {
            addField.requestFocus();
            return;
        }
        int at = Math.min(index, list.getChildren().size() - 1);
        list.getChildren().get(Math.max(0, at)).requestFocus();
    }
}
