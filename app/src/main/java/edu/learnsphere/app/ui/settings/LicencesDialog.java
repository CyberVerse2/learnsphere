package edu.learnsphere.app.ui.settings;

import edu.learnsphere.app.ui.components.FormDialog;
import edu.learnsphere.app.ui.components.Icons;
import java.util.Objects;
import java.util.Optional;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The licences of everything LearnSphere ships with, grouped as fonts, the study assistant and software, in a
 * scrolling dialog. Each entry names what it is, what it does in the app, its licence and copyright; the font
 * licences can be read in full.
 */
public final class LicencesDialog {

    private static final String STYLESHEET = Objects.requireNonNull(
            LicencesDialog.class.getResource("settings.css"), "settings.css").toExternalForm();

    private LicencesDialog() {
    }

    /** Shows the dialog over {@code anchor}'s window. */
    public static FormDialog.Handle show(Node anchor) {
        FormDialog.Handle handle = FormDialog.of("Licences")
                .message("LearnSphere comes with the fonts and software below. Each one is used under its own licence.")
                .content(list())
                .confirm("Done")
                .cancel(null)
                .wide()
                .show(anchor);
        handle.card().getStylesheets().add(STYLESHEET);
        return handle;
    }

    private static Node list() {
        VBox groups = new VBox(24);
        groups.getStyleClass().add("licences");
        for (Licences.Group group : Licences.ALL) {
            Label title = new Label(group.title().toUpperCase(java.util.Locale.ROOT));
            title.getStyleClass().addAll("text-overline", "licences-group-title");
            VBox entries = new VBox(16);
            for (Licences.Entry entry : group.entries()) {
                entries.getChildren().add(entry(entry));
            }
            groups.getChildren().add(new VBox(12, title, entries));
        }
        ScrollPane scroller = new ScrollPane(groups);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setPrefViewportHeight(400);
        scroller.setMinViewportHeight(160);
        scroller.getStyleClass().add("licences-scroller");
        scroller.setFocusTraversable(true);
        scroller.setAccessibleText("Licences");
        return scroller;
    }

    private static Node entry(Licences.Entry entry) {
        Label name = new Label(entry.name());
        name.getStyleClass().add("licence-name");
        name.setMnemonicParsing(false);
        Label use = new Label(entry.use());
        use.getStyleClass().add("licence-use");
        use.setWrapText(true);
        Label licence = new Label(entry.licence());
        licence.getStyleClass().add("licence-licence");
        licence.setWrapText(true);
        Label notice = new Label(entry.notice());
        notice.getStyleClass().add("licence-notice");
        notice.setWrapText(true);
        VBox box = new VBox(4, name, use, licence, notice);
        box.getStyleClass().add("licence-entry");
        Optional<String> text = entry.text();
        if (text.isPresent()) {
            Label full = new Label(text.get().strip());
            full.getStyleClass().add("licence-text");
            full.setWrapText(true);
            full.setMinHeight(Region.USE_PREF_SIZE);
            full.setVisible(false);
            full.setManaged(false);
            Button toggle = new Button("Read the licence");
            toggle.getStyleClass().addAll("quiet", "small", "licence-toggle");
            toggle.setGraphic(Icons.of(Feather.CHEVRON_DOWN, 14));
            toggle.setMnemonicParsing(false);
            toggle.setOnAction(event -> {
                boolean open = !full.isVisible();
                full.setVisible(open);
                full.setManaged(open);
                toggle.setText(open ? "Hide the licence" : "Read the licence");
                toggle.setGraphic(Icons.of(open ? Feather.CHEVRON_UP : Feather.CHEVRON_DOWN, 14));
            });
            box.getChildren().addAll(toggle, full);
        }
        return box;
    }
}
