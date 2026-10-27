package edu.learnsphere.app.ui.settings;

import atlantafx.base.controls.ToggleSwitch;
import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.service.AppSettings;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.SettingKey;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.ThemePreference;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Settings: appearance (system, light or dark, and reduce motion) for the whole app, then this profile's
 * study preferences, importing, the study assistant and the profile itself. Every change applies at once.
 */
public final class SettingsPage extends PageView {

    private record Data(AppPreferences preferences, AppSettings settings, AssistantStatus assistant) {
    }

    public SettingsPage(AppContext context, Navigator navigator) {
        super(context, navigator);
        show(Skeleton.block(220, 40), Skeleton.block(-1, 160), Skeleton.block(-1, 220));
    }

    @Override
    public void onShown() {
        load(() -> new Data(context.preferences().get(), workspace().settings().current(),
                workspace().assistant().status()), this::render);
        var workspace = context.workspace();
        if (workspace != null) {
            disposer.add(workspace.assistant().addListener(status -> Fx.run(() -> {
                if (assistantPill != null) {
                    showAssistant(status);
                }
            })));
        }
    }

    private StatusPill assistantPill;
    private Label assistantMessage;

    private void render(Data data) {
        PageHeader header = new PageHeader("Settings").subtitle("Changes apply straight away.");
        show(header, appearance(data.preferences()), studying(data.settings()), importing(data.settings()),
                assistant(data.assistant()), profile());
    }

    private Node appearance(AppPreferences preferences) {
        SegmentedControl<ThemePreference> theme = new SegmentedControl<>(List.of(ThemePreference.values()),
                SettingsPage::themeLabel);
        theme.setValue(preferences.theme());
        theme.valueProperty().addListener((obs, old, value) -> savePreferences(p -> p.withTheme(value)));
        ToggleSwitch reduce = new ToggleSwitch();
        reduce.setSelected(preferences.reduceMotion());
        reduce.selectedProperty().addListener((obs, old, on) -> savePreferences(p -> p.withReduceMotion(on)));
        reduce.setAccessibleText("Reduce motion");
        return section("Appearance", "For every profile on this computer.",
                row("Theme", "Match your computer, or always use light or dark.", theme),
                row("Reduce motion", "Replace movement with simple fades.", reduce));
    }

    private Node studying(AppSettings settings) {
        Spinner<Integer> perDay = spinner(0, 200, settings.newItemsPerDay(), 5, SettingKeys.NEW_ITEMS_PER_DAY);
        Spinner<Integer> size = spinner(5, 100, settings.sessionSize(), 5, SettingKeys.SESSION_SIZE);
        return section("Studying", null,
                row("New questions per day", "How many questions you haven't seen before can join your reviews each day.",
                        perDay),
                row("Session length", "How many questions a Study next session holds.", size));
    }

    private Node importing(AppSettings settings) {
        CheckBox figures = new CheckBox("Describe figures and diagrams");
        figures.setSelected(settings.describeFigures());
        figures.selectedProperty().addListener((obs, old, on) -> saveSetting(SettingKeys.DESCRIBE_FIGURES, on));
        Label note = new Label("Lets answers draw on charts and diagrams in your PDFs. Importing takes a little longer.");
        note.getStyleClass().add("settings-row-detail");
        note.setWrapText(true);
        return section("Importing PDFs", null, new VBox(6, figures, note));
    }

    private Node assistant(AssistantStatus status) {
        assistantPill = new StatusPill(StatusPill.Status.WORKING);
        assistantMessage = new Label();
        assistantMessage.getStyleClass().add("settings-row-detail");
        assistantMessage.setWrapText(true);
        Button restart = new Button("Restart");
        restart.getStyleClass().add("small");
        restart.setOnAction(event -> run(() -> {
            workspace().assistant().restart();
            return null;
        }, ignored -> { }, message -> Toasts.error(page(), message)));
        showAssistant(status);
        VBox text = new VBox(8, assistantPill, assistantMessage);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox line = new HBox(16, text, restart);
        line.setAlignment(Pos.CENTER_LEFT);
        return section("Study assistant", "Answers questions and drafts quizzes. Everything stays on this computer.", line);
    }

    private void showAssistant(AssistantStatus status) {
        StatusPill.Status pill = switch (status.state()) {
            case READY -> StatusPill.Status.READY;
            case STARTING -> StatusPill.Status.WORKING;
            case UNAVAILABLE -> StatusPill.Status.NEEDS_REVIEW;
            case FAILED -> StatusPill.Status.FAILED;
        };
        assistantPill.set(pill, switch (status.state()) {
            case READY -> "Ready";
            case STARTING -> "Getting ready";
            case UNAVAILABLE -> "Not available";
            case FAILED -> "Stopped";
        });
        assistantMessage.setText(status.message() == null ? "" : status.message().text());
        assistantMessage.setVisible(status.state() != AssistantState.READY || status.message() != null);
    }

    private Node profile() {
        var profile = workspace().profile();
        Avatar avatar = new Avatar(profile.initials(), profile.avatarHue(), 44);
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("text-heading");
        Label detail = new Label(profile.passwordProtected() ? "Protected with a password" : "No password");
        detail.getStyleClass().add("settings-row-detail");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button switchProfile = new Button("Switch profile");
        switchProfile.getStyleClass().add("small");
        switchProfile.setOnAction(event -> navigator.navigate(Destination.PROFILES));
        HBox line = new HBox(14, avatar, text, switchProfile);
        line.setAlignment(Pos.CENTER_LEFT);
        return section("Profile", null, line);
    }

    private Spinner<Integer> spinner(int min, int max, int value, int step, SettingKey<Integer> key) {
        Spinner<Integer> spinner = new Spinner<>(min, max, value, step);
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
        spinner.valueProperty().addListener((obs, old, chosen) -> {
            if (chosen != null) {
                saveSetting(key, chosen);
            }
        });
        return spinner;
    }

    private <T> void saveSetting(SettingKey<T> key, T value) {
        run(() -> {
            workspace().settings().set(key, value);
            return null;
        }, ignored -> { }, message -> Toasts.error(page(), message));
    }

    private void savePreferences(java.util.function.UnaryOperator<AppPreferences> change) {
        run(() -> context.preferences().update(change), ignored -> { }, message -> Toasts.error(page(), message));
    }

    private static Node section(String title, String subtitle, Node... rows) {
        Label heading = new Label(title);
        heading.getStyleClass().add("text-heading");
        VBox head = new VBox(4, heading);
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("text-muted");
            sub.setWrapText(true);
            head.getChildren().add(sub);
        }
        Card card = new Card(head);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "settings-section");
        card.setSpacing(18);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) {
                Region rule = new Region();
                rule.getStyleClass().add("hairline");
                card.getChildren().add(rule);
            }
            card.getChildren().add(rows[i]);
        }
        card.setMaxWidth(760);
        return card;
    }

    private static Node row(String title, String detail, Node control) {
        Label label = new Label(title);
        label.getStyleClass().add("settings-row-title");
        Label sub = new Label(detail);
        sub.getStyleClass().add("settings-row-detail");
        sub.setWrapText(true);
        VBox text = new VBox(3, label, sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(24, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    static String themeLabel(ThemePreference preference) {
        return switch (preference) {
            case SYSTEM -> "Match computer";
            case LIGHT -> "Light";
            case DARK -> "Dark";
        };
    }
}
