package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ThreadSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * The course's conversations, newest first and grouped by day (Today, Yesterday, Earlier), with New
 * conversation at the top. Each row opens its conversation; its menu (the "…" button, a right-click, or the
 * keyboard: F2 renames, Delete deletes) renames it in place or deletes it. Up and Down move between rows.
 * FX thread only.
 */
final class ThreadList extends VBox {

    /** Width of the list beside the conversation. */
    static final double WIDTH = 248;

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    /** What the list asks of the screen. */
    interface Actions {
        void open(ThreadSummary thread);

        void newConversation();

        void rename(ThreadSummary thread, String title);

        void delete(ThreadSummary thread);
    }

    private final Actions actions;
    private final Clock clock;
    private final VBox rows = new VBox();
    private final ScrollPane scroller = new ScrollPane(rows);
    private final Button newButton = new Button("New conversation", Icons.of(Feather.PLUS, 16));
    private final List<Row> rowList = new ArrayList<>();
    private String selectedId;

    ThreadList(Actions actions, Clock clock) {
        this.actions = Objects.requireNonNull(actions, "actions");
        this.clock = Objects.requireNonNull(clock, "clock");
        getStyleClass().add("ask-threads");
        setMinWidth(WIDTH);
        setPrefWidth(WIDTH);
        setMaxWidth(WIDTH);

        Label heading = new Label("Conversations");
        heading.getStyleClass().add("text-heading");
        newButton.getStyleClass().addAll("ask-new-thread");
        newButton.setMaxWidth(Double.MAX_VALUE);
        newButton.setAlignment(Pos.CENTER_LEFT);
        newButton.setOnAction(event -> actions.newConversation());
        Tooltip.install(newButton, tooltip("Start a new conversation (Shortcut+N)"));
        VBox top = new VBox(12, heading, newButton);
        top.getStyleClass().add("ask-threads-top");

        rows.getStyleClass().add("ask-thread-rows");
        rows.setFillWidth(true);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("ask-threads-scroller");
        VBox.setVgrow(scroller, Priority.ALWAYS);
        getChildren().addAll(top, scroller);
        showLoading();
    }

    Button newButton() {
        return newButton;
    }

    /** Placeholder rows while the conversations load. */
    void showLoading() {
        rowList.clear();
        List<Node> shapes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Skeleton title = Skeleton.line(i % 2 == 0 ? 168 : 132);
            Skeleton meta = Skeleton.line(84);
            VBox box = new VBox(8, title, meta);
            box.getStyleClass().add("ask-thread-skeleton");
            shapes.add(box);
        }
        rows.getChildren().setAll(shapes);
    }

    /** Says the conversations couldn't be listed. */
    void showError(UserMessage message) {
        rowList.clear();
        Label label = new Label(message.text());
        label.getStyleClass().addAll("text-caption", "ask-threads-note");
        label.setWrapText(true);
        rows.getChildren().setAll(label);
    }

    /** Shows {@code threads}, newest first, with {@code selected} (or none) marked. */
    void setThreads(List<ThreadSummary> threads, String selected) {
        boolean hadFocus = rowList.stream().anyMatch(Node::isFocused);
        String focusedId = rowList.stream().filter(Node::isFocused).map(r -> r.thread.id()).findFirst().orElse(null);
        selectedId = selected;
        rowList.clear();
        List<Node> nodes = new ArrayList<>();
        if (threads.isEmpty()) {
            Label none = new Label("Your questions about this course are kept here.");
            none.getStyleClass().addAll("text-caption", "ask-threads-note");
            none.setWrapText(true);
            nodes.add(none);
        }
        LocalDate today = LocalDate.now(clock);
        String group = null;
        for (ThreadSummary thread : sorted(threads)) {
            String label = group(activity(thread), today);
            if (!label.equals(group)) {
                group = label;
                Label header = new Label(label.toUpperCase(java.util.Locale.ROOT));
                header.getStyleClass().addAll("text-overline", "ask-thread-group");
                nodes.add(header);
            }
            Row row = new Row(thread);
            rowList.add(row);
            nodes.add(row);
        }
        rows.getChildren().setAll(nodes);
        if (hadFocus) {
            Row again = rowList.stream().filter(r -> r.thread.id().equals(focusedId)).findFirst()
                    .orElse(rowList.isEmpty() ? null : rowList.getFirst());
            if (again != null) {
                again.requestFocus();
            } else {
                newButton.requestFocus();
            }
        }
    }

    /** Marks the conversation on screen (null for a new one). */
    void select(String threadId) {
        selectedId = threadId;
        for (Row row : rowList) {
            row.pseudoClassStateChanged(SELECTED, row.thread.id().equals(threadId));
        }
        rowList.stream().filter(r -> r.thread.id().equals(threadId)).findFirst().ifPresent(this::reveal);
    }

    /** The conversations shown, top to bottom. */
    List<ThreadSummary> shown() {
        return rowList.stream().map(r -> r.thread).toList();
    }

    /** The row of {@code threadId}, or null. */
    Node rowOf(String threadId) {
        return rowList.stream().filter(r -> r.thread.id().equals(threadId)).findFirst().orElse(null);
    }

    /** Starts renaming {@code threadId} in place. */
    void startRename(String threadId) {
        rowList.stream().filter(r -> r.thread.id().equals(threadId)).findFirst().ifPresent(Row::startRename);
    }

    /** Newest activity first. */
    static List<ThreadSummary> sorted(List<ThreadSummary> threads) {
        return threads.stream().sorted(Comparator.comparingLong(ThreadList::activity).reversed()).toList();
    }

    /** When a conversation was last active: its latest message, or when it changed. */
    static long activity(ThreadSummary thread) {
        return thread.lastMessageAt() != null ? thread.lastMessageAt() : thread.updatedAt();
    }

    /** "Today", "Yesterday" or "Earlier". */
    String group(long at, LocalDate today) {
        LocalDate day = Instant.ofEpochMilli(at).atZone(clock.getZone()).toLocalDate();
        long days = ChronoUnit.DAYS.between(day, today);
        if (days <= 0) {
            return "Today";
        }
        return days == 1 ? "Yesterday" : "Earlier";
    }

    private void reveal(Node row) {
        Platform.runLater(() -> {
            if (row.getScene() == null || rows.getHeight() <= scroller.getViewportBounds().getHeight()) {
                return;
            }
            double y = row.getBoundsInParent().getMinY();
            double bottom = row.getBoundsInParent().getMaxY();
            double viewport = scroller.getViewportBounds().getHeight();
            double range = rows.getHeight() - viewport;
            double top = scroller.getVvalue() * range;
            if (y < top) {
                scroller.setVvalue(y / range);
            } else if (bottom > top + viewport) {
                scroller.setVvalue((bottom - viewport) / range);
            }
        });
    }

    private static Tooltip tooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setShowDelay(Duration.millis(400));
        return tooltip;
    }

    private void move(Row from, int delta) {
        int index = rowList.indexOf(from) + delta;
        if (index >= 0 && index < rowList.size()) {
            Row target = rowList.get(index);
            target.requestFocus();
            reveal(target);
        } else if (index < 0) {
            newButton.requestFocus();
        }
    }

    /** One conversation in the list. */
    private final class Row extends HBox {

        private final ThreadSummary thread;
        private final VBox text;
        private final Label title;
        private final IconButton more;
        private TextField editor;

        Row(ThreadSummary thread) {
            this.thread = thread;
            getStyleClass().add("ask-thread-row");
            setAlignment(Pos.CENTER_LEFT);
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.BUTTON);
            String name = AnswerText.title(thread.title());
            setAccessibleText(name);
            title = new Label(name);
            title.getStyleClass().add("ask-thread-title");
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            title.setMinWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            Label meta = new Label(meta(thread));
            meta.getStyleClass().add("ask-thread-meta");
            meta.setMinWidth(0);
            text = new VBox(3, title, meta);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            more = new IconButton(Feather.MORE_HORIZONTAL, "More for this conversation").small();
            more.getStyleClass().add("ask-thread-more");
            more.setFocusTraversable(false);
            more.setOnAction(event -> menu().show(more, Side.BOTTOM, 0, 4));
            StackPane moreBox = new StackPane(more);
            moreBox.setMinWidth(Region.USE_PREF_SIZE);
            getChildren().addAll(text, moreBox);
            pseudoClassStateChanged(SELECTED, thread.id().equals(selectedId));
            if (!thread.title().isBlank() && thread.title().length() > 28) {
                Tooltip.install(this, tooltip(thread.title()));
            }

            setOnMouseClicked(event -> {
                if (editor != null) {
                    return;
                }
                if (event.getButton() == MouseButton.PRIMARY) {
                    if (event.getClickCount() == 2) {
                        startRename();
                    } else {
                        actions.open(thread);
                    }
                }
            });
            setOnContextMenuRequested(event -> {
                if (editor == null) {
                    menu().show(this, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            addEventHandler(KeyEvent.KEY_PRESSED, event -> {
                if (editor != null) {
                    return;
                }
                switch (event.getCode()) {
                    case ENTER, SPACE -> actions.open(thread);
                    case F2 -> startRename();
                    case DELETE, BACK_SPACE -> actions.delete(thread);
                    case UP -> move(this, -1);
                    case DOWN -> move(this, 1);
                    case CONTEXT_MENU -> menu().show(more, Side.BOTTOM, 0, 4);
                    default -> {
                        return;
                    }
                }
                event.consume();
            });
        }

        private ContextMenu menu() {
            MenuItem rename = new MenuItem("Rename");
            rename.setGraphic(Icons.of(Feather.EDIT_2, 14));
            rename.setOnAction(event -> startRename());
            MenuItem delete = new MenuItem("Delete");
            delete.setGraphic(Icons.of(Feather.TRASH_2, 14));
            delete.getStyleClass().add("danger");
            delete.setOnAction(event -> actions.delete(thread));
            ContextMenu menu = new ContextMenu(rename, delete);
            menu.getStyleClass().add("ask-thread-menu");
            return menu;
        }

        void startRename() {
            if (editor != null) {
                return;
            }
            editor = new TextField(thread.title().isBlank() ? "" : thread.title());
            editor.setPromptText(AnswerText.UNTITLED);
            editor.getStyleClass().add("ask-thread-editor");
            editor.setAccessibleText("Conversation name");
            HBox.setHgrow(editor, Priority.ALWAYS);
            int index = getChildren().indexOf(text);
            getChildren().set(index, editor);
            getChildren().getLast().setVisible(false);
            editor.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    commit();
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    event.consume();
                    cancelRename();
                }
            });
            editor.focusedProperty().addListener((obs, was, focused) -> {
                if (!focused && editor != null) {
                    commit();
                }
            });
            Platform.runLater(() -> {
                if (editor != null) {
                    editor.requestFocus();
                    editor.selectAll();
                }
            });
        }

        private void commit() {
            TextField field = editor;
            if (field == null) {
                return;
            }
            String value = field.getText() == null ? "" : field.getText().strip();
            cancelRename();
            if (!value.isEmpty() && !value.equals(thread.title())) {
                title.setText(value);
                actions.rename(thread, value);
            }
        }

        private void cancelRename() {
            TextField field = editor;
            if (field == null) {
                return;
            }
            editor = null;
            boolean refocus = field.isFocused();
            int index = getChildren().indexOf(field);
            if (index >= 0) {
                getChildren().set(index, text);
            }
            getChildren().getLast().setVisible(true);
            if (refocus) {
                requestFocus();
            }
        }
    }

    /** "2 h ago · 3 questions". */
    private String meta(ThreadSummary thread) {
        int questions = (thread.messageCount() + 1) / 2;
        String when = Formats.ago(activity(thread), clock);
        when = Character.toUpperCase(when.charAt(0)) + when.substring(1);
        return questions == 0 ? when : when + " · " + Formats.count(questions, "question", "questions");
    }
}
