package edu.learnsphere.app.ui;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Page;
import edu.learnsphere.app.ui.components.SphereArt;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/** The home screen's opening view: what LearnSphere does and where to begin. */
public final class WelcomePage extends Page {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault());

    public WelcomePage(AppContext context, Navigator navigator) {
        Label date = new Label(LocalDate.now(context.clock()).format(DATE).toUpperCase(Locale.getDefault()));
        date.getStyleClass().add("text-overline");
        Label title = new Label("Welcome to LearnSphere");
        title.getStyleClass().add("text-display");
        Label intro = new Label("Turn your PDFs into courses, ask questions answered from your own material, "
                + "and practise with reviews planned around what you remember.");
        intro.getStyleClass().add("welcome-intro");
        intro.setWrapText(true);
        intro.setMaxWidth(640);
        VBox header = new VBox(8, date, title, intro);

        column().getChildren().addAll(header, hero(navigator), features());
        column().setSpacing(32);
    }

    private static Card hero(Navigator navigator) {
        Label heading = new Label("Learn from your own material");
        heading.getStyleClass().add("text-title");
        Label body = new Label("Bring the PDFs you study from. Every answer, quiz and flashcard comes from them, "
                + "and shows the page it came from.");
        body.getStyleClass().addAll("text-body", "text-muted");
        body.setWrapText(true);
        Button start = new Button("Open your library", Icons.of(Feather.ARROW_RIGHT));
        start.getStyleClass().add("primary");
        start.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        start.setOnAction(event -> navigator.navigate(Route.LIBRARY));
        FlowPane chips = new FlowPane(8, 8, chip("Quizzes"), chip("Flashcards"), chip("Spaced reviews"),
                chip("Cited answers"));
        VBox copy = new VBox(heading, body, chips, start);
        copy.getStyleClass().add("hero-copy");
        VBox.setMargin(start, new Insets(10, 0, 0, 0));
        VBox.setMargin(chips, new Insets(2, 0, 0, 0));
        copy.setMinWidth(340);
        copy.setPrefWidth(480);
        HBox.setHgrow(copy, Priority.ALWAYS);

        SphereArt art = SphereArt.cover(SphereArt.ACCENT_HUE);
        art.setCornerRadius(10);
        art.setPrefSize(400, 260);
        art.setMinSize(220, 240);
        StackPane artBox = new StackPane(art);
        artBox.getStyleClass().add("hero-art");
        HBox.setHgrow(artBox, Priority.SOMETIMES);

        HBox row = new HBox(copy, artBox);
        row.setAlignment(Pos.CENTER_LEFT);
        Card card = new Card(row);
        card.getStyleClass().add("hero-card");
        card.setInteractive(false);
        return card;
    }

    private static GridPane features() {
        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.addRow(0,
                feature(Feather.MESSAGE_CIRCLE, "Ask your material",
                        "Get answers drawn only from your PDFs, each with the page it came from."),
                feature(Feather.REPEAT, "Practise at the right time",
                        "Quizzes and flashcards, with reviews planned around what you remember."),
                feature(Feather.LOCK, "Private by design",
                        "Your courses and your study assistant stay on this computer."));
        for (int i = 0; i < 3; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            column.setFillWidth(true);
            grid.getColumnConstraints().add(column);
        }
        RowConstraints row = new RowConstraints();
        row.setFillHeight(true);
        row.setValignment(VPos.TOP);
        grid.getRowConstraints().add(row);
        return grid;
    }

    private static Card feature(Ikon icon, String heading, String text) {
        StackPane badge = new StackPane(Icons.of(icon));
        badge.getStyleClass().add("icon-badge");
        Label title = new Label(heading);
        title.getStyleClass().add("text-heading");
        Label body = new Label(text);
        body.getStyleClass().addAll("text-body", "text-muted");
        body.setWrapText(true);
        VBox copy = new VBox(6, title, body);
        Card card = new Card(badge, copy);
        card.getStyleClass().add("feature-card");
        card.setInteractive(false);
        card.setMinWidth(0);
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return card;
    }

    private static Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chip");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        return chip;
    }
}
