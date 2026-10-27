package edu.learnsphere.app.ui.onboarding;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.Workspace;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import org.kordamp.ikonli.feather.Feather;

/**
 * First run (full screen), in three calm steps: a welcome, creating a profile (name, optional password,
 * avatar colour), and getting the study assistant ready, which explains that everything stays on this
 * computer. Finishing opens Home.
 */
public final class OnboardingPage implements View {

    /** Avatar colours offered, as hues. */
    public static final List<Integer> AVATAR_HUES = List.of(18, 42, 150, 190, 226, 268, 320);

    private final AppContext context;
    private final Navigator navigator;
    private final Disposer disposer = new Disposer();
    private final StackPane root = new StackPane();
    private final VBox card = new VBox();
    private final HBox steps = new HBox(8);
    private final TextField name = new TextField();
    private final PasswordField password = new PasswordField();
    private final ToggleGroup hues = new ToggleGroup();
    private int step;

    public OnboardingPage(AppContext context, Navigator navigator) {
        this.context = context;
        this.navigator = navigator;
        root.getStyleClass().addAll("full-screen", "onboarding-page");
        card.getStyleClass().addAll("card", "flat", "onboarding-card");
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        steps.getStyleClass().add("onboarding-steps");
        steps.setAlignment(Pos.CENTER);
        VBox column = new VBox(24, card, steps);
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(520);
        root.getChildren().add(column);
        show(0);
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void dispose() {
        disposer.dispose();
    }

    /** The step on screen: 0 welcome, 1 profile, 2 study assistant. */
    public int step() {
        return step;
    }

    private void show(int index) {
        step = index;
        Node content = switch (index) {
            case 0 -> welcome();
            case 1 -> profile();
            default -> assistant();
        };
        card.getChildren().setAll(content);
        steps.getChildren().clear();
        for (int i = 0; i < 3; i++) {
            Region dot = new Region();
            dot.getStyleClass().add("onboarding-step");
            if (i == index) {
                dot.getStyleClass().add("current");
            } else if (i < index) {
                dot.getStyleClass().add("done");
            }
            steps.getChildren().add(dot);
        }
        steps.setAccessibleText("Step " + (index + 1) + " of 3");
        if (root.getScene() != null) {
            Motion.enter(card);
        }
    }

    private Node welcome() {
        SphereArt art = SphereArt.cover(SphereArt.ACCENT_HUE);
        art.setPrefSize(456, 200);
        art.setMinHeight(200);
        art.setCornerRadius(10);
        Label title = new Label("Welcome to LearnSphere");
        title.getStyleClass().addAll("text-display");
        Label body = new Label("Turn your PDFs into courses, ask questions answered from your own material, and "
                + "practise with reviews timed to help you remember.");
        body.getStyleClass().add("text-reading");
        body.setWrapText(true);
        Button start = new Button("Get started");
        start.getStyleClass().addAll("primary", "large");
        start.setDefaultButton(true);
        start.setOnAction(event -> show(1));
        VBox box = new VBox(16, art, title, body, gap(8), start);
        box.getStyleClass().add("onboarding-step-body");
        return box;
    }

    private Node profile() {
        Label overline = new Label("STEP 2 OF 3");
        overline.getStyleClass().add("text-overline");
        Label title = new Label("Create your profile");
        title.getStyleClass().add("text-title");
        Label body = new Label("Your courses and progress are kept in your profile. Add a password if others use "
                + "this computer.");
        body.getStyleClass().add("text-muted");
        body.setWrapText(true);
        name.setPromptText("Your name");
        password.setPromptText("Password (optional)");
        HBox colours = new HBox(10);
        colours.setAlignment(Pos.CENTER_LEFT);
        for (int hue : AVATAR_HUES) {
            ToggleButton swatch = new ToggleButton(null, new Avatar(initials(name.getText()), hue, 34));
            swatch.getStyleClass().setAll("avatar-swatch");
            swatch.setUserData(hue);
            swatch.setToggleGroup(hues);
            swatch.setAccessibleText("Avatar colour");
            colours.getChildren().add(swatch);
        }
        if (hues.getSelectedToggle() == null) {
            hues.selectToggle(hues.getToggles().getFirst());
        }
        name.textProperty().addListener((obs, old, text) -> {
            for (var toggle : hues.getToggles()) {
                ((ToggleButton) toggle).setGraphic(new Avatar(initials(text), (Integer) toggle.getUserData(), 34));
            }
        });
        Label error = new Label();
        error.getStyleClass().addAll("text-caption", "text-danger");
        Button next = new Button("Create profile");
        next.getStyleClass().addAll("primary", "large");
        next.setDefaultButton(true);
        next.disableProperty().bind(name.textProperty().map(String::isBlank));
        next.setOnAction(event -> create(next, error));
        Button back = new Button("Back");
        back.getStyleClass().addAll("quiet", "neutral");
        back.setOnAction(event -> show(0));
        HBox actions = new HBox(12, next, back);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(16, new VBox(6, overline, title), body, field("Name", name),
                field("Password", password), field("Avatar colour", colours), error, actions);
        box.getStyleClass().add("onboarding-step-body");
        Fx.run(name::requestFocus);
        return box;
    }

    private void create(Button next, Label error) {
        next.setDisable(true);
        String displayName = name.getText().strip();
        int hue = (Integer) hues.getSelectedToggle().getUserData();
        char[] secret = password.getText().isEmpty() ? null : password.getText().toCharArray();
        // Creating the profile may wipe its copy of the password; opening it needs its own.
        char[] forOpening = secret == null ? null : secret.clone();
        password.clear();
        disposer.add(UiTasks.run(() -> {
            var profile = context.profiles().create(new ProfileDraft(displayName, hue, secret));
            return context.open(profile.id(), forOpening);
        }, workspace -> show(2), message -> {
            next.disableProperty().bind(name.textProperty().map(String::isBlank));
            error.setText(message.text());
        }));
    }

    private Node assistant() {
        Label overline = new Label("STEP 3 OF 3");
        overline.getStyleClass().add("text-overline");
        Label title = new Label("Getting your study assistant ready");
        title.getStyleClass().add("text-title");
        title.setWrapText(true);
        Label body = new Label("It reads your PDFs, answers your questions and writes practice questions. "
                + "Everything stays on this computer.");
        body.getStyleClass().add("text-muted");
        body.setWrapText(true);
        StackPane indicator = new StackPane();
        indicator.setMinSize(28, 28);
        Label status = new Label();
        status.getStyleClass().add("medium");
        status.setWrapText(true);
        HBox line = new HBox(12, indicator, status);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("onboarding-status");
        Button finish = new Button("Start learning");
        finish.getStyleClass().addAll("primary", "large");
        finish.setDefaultButton(true);
        finish.setOnAction(event -> finish());
        Workspace workspace = context.workspace();
        if (workspace != null) {
            showStatus(workspace.assistant().status(), indicator, status);
            disposer.add(workspace.assistant().addListener(s -> Fx.run(() -> showStatus(s, indicator, status))));
        } else {
            status.setText("Open a profile to get the study assistant ready.");
        }
        VBox box = new VBox(16, new VBox(6, overline, title), body, line, gap(4), finish);
        box.getStyleClass().add("onboarding-step-body");
        return box;
    }

    private static void showStatus(AssistantStatus s, StackPane indicator, Label status) {
        if (s.state() == AssistantState.READY) {
            Circle done = new Circle(12);
            done.getStyleClass().add("onboarding-ready-dot");
            indicator.getChildren().setAll(done, Icons.of(Feather.CHECK, 14));
            indicator.getStyleClass().setAll("onboarding-ready");
            status.setText("Ready. You can start studying.");
        } else if (s.state() == AssistantState.STARTING) {
            indicator.getChildren().setAll(new ActivityIndicator(22));
            indicator.getStyleClass().clear();
            status.setText(s.message() == null ? "Getting your study assistant ready" : s.message().text());
        } else {
            indicator.getChildren().setAll(Icons.of(Feather.ALERT_CIRCLE, 20));
            indicator.getStyleClass().setAll("onboarding-unavailable");
            status.setText((s.message() == null ? "The study assistant isn't available right now."
                    : s.message().text()) + " You can still study the courses you have.");
        }
    }

    private void finish() {
        disposer.add(UiTasks.run(() -> context.preferences().update(p -> p.withOnboardingCompleted(true)),
                ignored -> navigator.reset(context.workspace() == null ? Destination.PROFILES : Destination.HOME),
                message -> navigator.reset(Destination.HOME)));
    }

    private static Node field(String label, Node control) {
        Label text = new Label(label);
        text.getStyleClass().add("field-label");
        return new VBox(6, text, control);
    }

    private static Region gap(double height) {
        Region gap = new Region();
        gap.setMinHeight(height);
        return gap;
    }

    static String initials(String name) {
        String[] words = name.strip().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) {
            return "";
        }
        String first = words[0].substring(0, 1);
        String last = words.length > 1 ? words[words.length - 1].substring(0, 1) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }
}
