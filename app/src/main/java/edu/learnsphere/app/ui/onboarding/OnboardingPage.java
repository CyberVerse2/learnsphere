package edu.learnsphere.app.ui.onboarding;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.FormDialog;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.SphereArt;
import edu.learnsphere.app.ui.profiles.AvatarPicker;
import edu.learnsphere.app.ui.profiles.ProfileActions;
import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * First run (full screen), in three calm steps: a welcome; creating a profile (name, an optional password
 * typed twice, avatar colour); and the study assistant getting ready, with its live readiness, what happens
 * when this computer can't run it, and that everything stays on this computer. Finishing opens Home.
 *
 * <p>Adding a profile from the profile choice ({@link Destination.AddProfile}) shows only the profile step,
 * with Cancel; creating the profile opens it and shows Home.
 */
public final class OnboardingPage implements View {

    /** The steps of first run. */
    public enum Step {
        WELCOME, PROFILE, ASSISTANT
    }

    /** What still works when the study assistant can't run on this computer (onboarding and Settings say it). */
    public static final String WITHOUT_ASSISTANT = "You can still study, practise and follow your progress. Asking "
            + "questions and making practice questions need the study assistant.";

    /** Avatar colours offered, as hues. */
    public static final List<Integer> AVATAR_HUES = AvatarPicker.COLOURS.stream().map(AvatarPicker.Colour::hue).toList();

    private static final String STYLESHEET = Objects.requireNonNull(
            OnboardingPage.class.getResource("onboarding.css"), "onboarding.css").toExternalForm();

    private final AppContext context;
    private final Navigator navigator;
    private final boolean addingProfile;
    private final Disposer disposer = new Disposer();
    private Disposer stepScope = new Disposer();
    private final StackPane root = new StackPane();
    private final VBox card = new VBox();
    private final HBox dots = new HBox(8);
    private final TextField name = new TextField();
    private final PasswordField password = new PasswordField();
    private final PasswordField again = new PasswordField();
    private final AvatarPicker colours = new AvatarPicker(AvatarPicker.COLOURS.getFirst().hue());
    private final Label profileError = new Label();
    private final AssistantOrbit orbit = new AssistantOrbit();
    private final Label statusTitle = new Label();
    private final Label statusDetail = new Label();
    private final Button restart = new Button("Restart");
    private Step step;
    /** The profile made on the profile step, so a failed open is retried without making another. */
    private volatile String createdProfileId;

    /** First run. */
    public OnboardingPage(AppContext context, Navigator navigator) {
        this(context, navigator, false);
    }

    /**
     * @param addingProfile true to show only the profile step, for another profile on this computer
     */
    public OnboardingPage(AppContext context, Navigator navigator, boolean addingProfile) {
        this.context = context;
        this.navigator = navigator;
        this.addingProfile = addingProfile;
        root.getStyleClass().addAll("full-screen", "onboarding-page");
        root.getStylesheets().add(STYLESHEET);
        card.getStyleClass().addAll("card", "flat", "onboarding-card");
        card.setMaxWidth(520);
        card.setMinWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        dots.getStyleClass().add("onboarding-steps");
        dots.setAlignment(Pos.CENTER);
        dots.setVisible(!addingProfile);
        dots.setManaged(!addingProfile);
        VBox column = new VBox(24, card, dots);
        column.setAlignment(Pos.CENTER);
        column.setMaxWidth(520);
        root.getChildren().add(column);

        name.setPromptText("Your name");
        name.getStyleClass().add("onboarding-name");
        name.textProperty().addListener((obs, old, text) -> {
            colours.setName(text);
            profileError.setText("");
        });
        password.textProperty().addListener((obs, old, text) -> {
            profileError.setText("");
            if (text.isEmpty()) {
                // No password, nothing to confirm: the hidden confirmation must not keep an old one.
                again.clear();
            }
        });
        again.textProperty().addListener((obs, old, text) -> profileError.setText(""));
        profileError.getStyleClass().add("onboarding-error");
        profileError.setWrapText(true);
        profileError.setAccessibleRole(AccessibleRole.TEXT);
        profileError.managedProperty().bind(profileError.textProperty().isNotEmpty());
        profileError.visibleProperty().bind(profileError.managedProperty());
        statusTitle.getStyleClass().add("onboarding-status-title");
        statusTitle.setWrapText(true);
        statusDetail.getStyleClass().add("onboarding-status-detail");
        statusDetail.setWrapText(true);
        restart.getStyleClass().addAll("small", "onboarding-restart");
        restart.setOnAction(event -> restartAssistant());
        root.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && step == Step.PROFILE) {
                event.consume();
                back();
            }
        });
        show(addingProfile ? Step.PROFILE : Step.WELCOME);
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void dispose() {
        stepScope.dispose();
        disposer.dispose();
        password.clear();
        again.clear();
    }

    /** The step on screen. */
    public Step step() {
        return step;
    }

    /** True when this page adds another profile rather than running first run. */
    public boolean isAddingProfile() {
        return addingProfile;
    }

    /** Shows {@code target} without going through the steps before it (screenshot tour). */
    public void showStep(Step target) {
        show(target);
    }

    /** The assistant drawing of the last step (tests). */
    public AssistantOrbit orbit() {
        return orbit;
    }

    /** The status sentence of the last step (tests). */
    public String statusText() {
        return statusTitle.getText();
    }

    /** What the status means for the person, under the sentence (tests). */
    public String statusDetailText() {
        return statusDetail.getText();
    }

    /** The name field of the profile step (tests and the screenshot tour). */
    public TextField nameField() {
        return name;
    }

    /** The optional password field of the profile step (tests). */
    public PasswordField passwordField() {
        return password;
    }

    /** The field that repeats the password (tests). */
    public PasswordField confirmField() {
        return again;
    }

    /** The avatar colours of the profile step (tests). */
    public AvatarPicker colours() {
        return colours;
    }

    /** The sentence under the profile fields when something needs fixing, else empty (tests). */
    public String profileErrorText() {
        return profileError.getText();
    }

    private void show(Step target) {
        step = target;
        stepScope.dispose();
        stepScope = new Disposer();
        Node content = switch (target) {
            case WELCOME -> welcome();
            case PROFILE -> profile();
            case ASSISTANT -> assistant();
        };
        card.getChildren().setAll(content);
        dots.getChildren().clear();
        for (Step each : Step.values()) {
            Region dot = new Region();
            dot.getStyleClass().add("onboarding-step");
            if (each == target) {
                dot.getStyleClass().add("current");
            } else if (each.ordinal() < target.ordinal()) {
                dot.getStyleClass().add("done");
            }
            dots.getChildren().add(dot);
        }
        dots.setAccessibleText("Step " + (target.ordinal() + 1) + " of " + Step.values().length);
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
        title.getStyleClass().add("text-display");
        title.setWrapText(true);
        Label body = new Label("Turn your PDFs into courses, ask questions answered from your own material, and "
                + "practise with reviews timed to help you remember.");
        body.getStyleClass().addAll("text-reading", "onboarding-lead");
        body.setWrapText(true);
        Button start = new Button("Get started");
        start.getStyleClass().addAll("primary", "large");
        start.setDefaultButton(true);
        start.setOnAction(event -> show(Step.PROFILE));
        VBox box = new VBox(art, new VBox(12, title, body), start);
        box.getStyleClass().addAll("onboarding-step-body", "welcome");
        Platform.runLater(start::requestFocus);
        return box;
    }

    private Node profile() {
        Label overline = new Label(addingProfile ? "NEW PROFILE" : "STEP 2 OF 3");
        overline.getStyleClass().add("text-overline");
        Label title = new Label(addingProfile ? "Add a profile" : "Create your profile");
        title.getStyleClass().add("text-title");
        Label body = new Label("A profile keeps its own courses and progress. Add a password if other people use "
                + "this computer.");
        body.getStyleClass().add("onboarding-body");
        body.setWrapText(true);

        password.setPromptText("Leave empty for no password");
        VBox confirm = FormDialog.field("Type the password again", again,
                "Keep it somewhere safe: a forgotten password can't be recovered.");
        confirm.visibleProperty().bind(password.textProperty().isNotEmpty());
        confirm.managedProperty().bind(confirm.visibleProperty());

        // Once the profile exists (an earlier open failed), the button opens it rather than making another.
        Button create = new Button(createdProfileId == null ? "Create profile" : "Continue");
        create.getStyleClass().addAll("primary", "large");
        create.setDefaultButton(true);
        create.disableProperty().bind(name.textProperty().map(String::isBlank));
        create.setOnAction(event -> create(create));
        Button back = new Button(addingProfile ? "Cancel" : "Back");
        back.getStyleClass().addAll("quiet", "neutral");
        back.setOnAction(event -> back());
        HBox actions = new HBox(12, create, back);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("onboarding-actions");

        VBox fields = new VBox(16, FormDialog.field("Name", name), FormDialog.field("Password (optional)", password),
                confirm, FormDialog.field("Avatar colour", colours));
        fields.getStyleClass().add("onboarding-fields");
        VBox box = new VBox(new VBox(8, overline, title, body), fields, profileError, actions);
        box.getStyleClass().addAll("onboarding-step-body", "profile");
        Platform.runLater(name::requestFocus);
        return box;
    }

    private void back() {
        if (!addingProfile) {
            show(Step.WELCOME);
        } else if (!navigator.back()) {
            navigator.reset(Destination.PROFILES);
        }
    }

    private void create(Button create) {
        String displayName = name.getText().strip();
        if (displayName.isEmpty()) {
            return;
        }
        boolean withPassword = !password.getText().isEmpty();
        if (withPassword && !password.getText().equals(again.getText())) {
            // Clearing the field clears the sentence too, so the sentence comes after.
            again.clear();
            profileError.setText(ProfileActions.PASSWORDS_DIFFER.text());
            again.requestFocus();
            return;
        }
        int hue = colours.getHue();
        char[] secret = withPassword ? password.getText().toCharArray() : null;
        // Creating the profile wipes its copy of the password; opening it needs its own.
        char[] forOpening = secret == null ? null : secret.clone();
        create.disableProperty().unbind();
        create.setDisable(true);
        lockProfileFields(true);
        disposer.add(UiTasks.run(() -> {
            if (createdProfileId == null) {
                createdProfileId = context.profiles().create(new ProfileDraft(displayName, hue, secret)).id();
            } else if (secret != null) {
                java.util.Arrays.fill(secret, '\0');
            }
            return context.open(createdProfileId, forOpening);
        }, workspace -> {
            password.clear();
            again.clear();
            if (addingProfile) {
                navigator.reset(Destination.HOME);
            } else {
                show(Step.ASSISTANT);
            }
        }, message -> {
            if (createdProfileId != null) {
                // The profile exists as it was typed: trying again opens it, so its fields stay as they are.
                create.setText("Try again");
            } else {
                lockProfileFields(false);
            }
            create.disableProperty().bind(name.textProperty().map(String::isBlank));
            profileError.setText(message.text());
        }));
    }

    private void lockProfileFields(boolean locked) {
        name.setDisable(locked);
        password.setDisable(locked);
        again.setDisable(locked);
        colours.setDisable(locked);
    }

    private Node assistant() {
        Label overline = new Label("STEP 3 OF 3");
        overline.getStyleClass().add("text-overline");
        Label title = new Label("Meet your study assistant");
        title.getStyleClass().add("text-title");
        title.setWrapText(true);
        Label body = new Label("It answers your questions from your own PDFs and shows the page each answer comes "
                + "from. It also writes practice questions for you to check.");
        body.getStyleClass().add("onboarding-body");
        body.setWrapText(true);

        VBox text = new VBox(4, statusTitle, statusDetail, restart);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox status = new HBox(16, orbit, text);
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("onboarding-status");

        var lock = Icons.of(Feather.LOCK, 14);
        lock.getStyleClass().add("onboarding-privacy-icon");
        Label privacy = new Label("Everything stays on this computer. Your PDFs, questions and progress are never "
                + "sent anywhere.", lock);
        privacy.getStyleClass().add("onboarding-privacy");
        privacy.setWrapText(true);
        privacy.setGraphicTextGap(10);

        Button finish = new Button("Start learning");
        finish.getStyleClass().addAll("primary", "large");
        finish.setDefaultButton(true);
        finish.setOnAction(event -> finish(finish));

        Workspace workspace = context.workspace();
        if (workspace != null) {
            AtomicBoolean heard = new AtomicBoolean();
            stepScope.add(Fx.subscribe(workspace.events(), AssistantStatusChanged.class, event -> {
                heard.set(true);
                showStatus(event.status());
            }));
            stepScope.add(UiTasks.run(() -> workspace.assistant().status(), current -> {
                if (!heard.get()) {
                    showStatus(current);
                }
            }, message -> showStatus(AssistantStatus.failed(null))));
        } else {
            showStatus(AssistantStatus.starting(null));
        }
        VBox box = new VBox(new VBox(8, overline, title, body), status, privacy, finish);
        box.getStyleClass().addAll("onboarding-step-body", "assistant");
        Platform.runLater(finish::requestFocus);
        return box;
    }

    private void showStatus(AssistantStatus status) {
        orbit.show(status.state());
        statusTitle.setText(status.message().text());
        statusDetail.setText(switch (status.state()) {
            case STARTING -> "The first time can take a minute. You can start now: it keeps getting ready while "
                    + "you look around.";
            case READY -> "Ask it about your PDFs whenever you like.";
            case UNAVAILABLE -> WITHOUT_ASSISTANT;
            case FAILED -> "You can still study, practise and follow your progress while it's stopped.";
        });
        boolean canRestart = status.state() == AssistantState.FAILED;
        restart.setVisible(canRestart);
        restart.setManaged(canRestart);
    }

    private void restartAssistant() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        restart.setDisable(true);
        stepScope.add(UiTasks.run(() -> {
            workspace.assistant().restart();
            return true;
        }, ok -> restart.setDisable(false), message -> {
            restart.setDisable(false);
            statusDetail.setText(message.text());
        }));
    }

    private void finish(Button finish) {
        finish.setDisable(true);
        disposer.add(UiTasks.run(() -> context.preferences().update(p -> p.withOnboardingCompleted(true)),
                ignored -> navigator.reset(context.workspace() == null ? Destination.PROFILES : Destination.HOME),
                message -> navigator.reset(context.workspace() == null ? Destination.PROFILES : Destination.HOME)));
    }
}
