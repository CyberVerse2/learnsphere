package edu.learnsphere.app.ui.settings;

import atlantafx.base.controls.ToggleSwitch;
import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.AppInfo;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.profiles.ProfileActions;
import edu.learnsphere.core.events.AssistantStatusChanged;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.service.AppSettings;
import edu.learnsphere.core.service.AssistantStatus;
import edu.learnsphere.core.service.ModelOption;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.SettingKey;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.spi.ModelChoice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Settings, one card per section with a short index beside them that follows the scroll position:
 * Appearance (system, light or dark, and reduce motion) for the whole app; Study (how well to remember,
 * session length, new questions per day); the Study assistant (readiness, restart, how it answers, and
 * describing figures in PDFs); the Profile (name, colour, password, switching); Storage (where the profile is
 * kept, the space it uses, backing it up); and About (version and licences). Every change applies at once.
 */
public final class SettingsPage extends PageView {

    /** The sections, in order, as the index names them. */
    public enum Section {
        APPEARANCE("Appearance"),
        STUDY("Study"),
        ASSISTANT("Study assistant"),
        PROFILE("Profile"),
        STORAGE("Storage"),
        ABOUT("About");

        private final String title;

        Section(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /** The desired retention the schedule is tuned for. */
    static final double RECOMMENDED_RETENTION = SettingKeys.DESIRED_RETENTION.defaultValue();

    private static final String STYLESHEET = Objects.requireNonNull(
            SettingsPage.class.getResource("settings.css"), "settings.css").toExternalForm();
    private static final double INDEX_WIDTH = 176;

    private record Data(AppPreferences preferences, AppSettings settings, AssistantStatus assistant,
                        List<ModelOption> models) {
    }

    private final Supplier<SystemFiles> files;
    private final ProfileActions actions;
    private final Map<Section, Node> sections = new LinkedHashMap<>();
    private final Map<Section, ToggleButton> indexItems = new LinkedHashMap<>();
    private final ToggleGroup indexGroup = new ToggleGroup();
    private final VBox index = new VBox(2);
    private final VBox modelChoices = new VBox(12);
    private final StatusPill assistantPill = new StatusPill(StatusPill.Status.WORKING, "Getting ready");
    private final Label assistantMessage = new Label();
    private final Button restart = new Button("Restart");
    private final HBox profileLine = new HBox(16);
    private final Label spaceUsed = new Label();
    private SegmentedControl<ThemePreference> theme;
    private ToggleSwitch reduceMotion;
    private ChangeListener<Number> scrollSpy;
    /** The section chosen in the index, kept selected while the page stays where that choice scrolled it. */
    private Section pinned;
    private double pinnedTop = Double.NaN;
    private boolean spyQueued;

    public SettingsPage(AppContext context, Navigator navigator) {
        this(context, navigator, SystemFiles::standard);
    }

    public SettingsPage(AppContext context, Navigator navigator, Supplier<SystemFiles> files) {
        super(context, navigator);
        this.files = Objects.requireNonNull(files, "files");
        this.actions = new ProfileActions(context, files);
        page().getStylesheets().add(STYLESHEET);
        page().getStyleClass().add("settings-page");
        PageHeader header = new PageHeader("Settings").subtitle("Changes apply straight away.");
        VBox placeholder = new VBox(24, Skeleton.block(-1, 180), Skeleton.block(-1, 260), Skeleton.block(-1, 220));
        placeholder.setMaxWidth(760);
        show(header, placeholder);
    }

    @Override
    public void onShown() {
        load(() -> new Data(context.preferences().get(), workspace().settings().current(),
                workspace().assistant().status(), workspace().assistant().modelOptions()), this::render);
        on(AssistantStatusChanged.class, event -> {
            showAssistant(event.status());
            run(() -> workspace().assistant().modelOptions(), this::showModels, message -> { });
        });
        disposer.add(context.preferences().addListener(changed -> Fx.run(() -> showPreferences(changed))));
        ChangeListener<ProfileSummary> profileListener = (obs, old, profile) -> showProfile(profile);
        context.profileProperty().addListener(profileListener);
        disposer.onDispose(() -> context.profileProperty().removeListener(profileListener));
    }

    @Override
    public void dispose() {
        if (scrollSpy != null) {
            page().vvalueProperty().removeListener(scrollSpy);
        }
        super.dispose();
    }

    /** The card of {@code section}, once loaded (tests and the screenshot tour). */
    public Node section(Section section) {
        return sections.get(section);
    }

    /** Scrolls the page so {@code section} is at the top. */
    public void scrollTo(Section section) {
        Node target = sections.get(section);
        if (target == null) {
            return;
        }
        page().applyCss();
        page().layout();
        Node content = page().getContent();
        double contentHeight = content.getLayoutBounds().getHeight();
        double viewport = page().getViewportBounds().getHeight();
        if (contentHeight <= viewport) {
            return;
        }
        Bounds bounds = content.sceneToLocal(target.localToScene(target.getLayoutBounds()));
        double top = Math.max(0, Math.min(bounds.getMinY() - 16, contentHeight - viewport));
        pinned = section;
        pinnedTop = top;
        page().setVvalue(top / (contentHeight - viewport));
        select(section);
    }

    /** The section the index marks (tests). */
    public Section selectedSection() {
        return indexItems.entrySet().stream().filter(e -> e.getValue().isSelected()).map(Map.Entry::getKey)
                .findFirst().orElse(null);
    }

    private void render(Data data) {
        sections.clear();
        sections.put(Section.APPEARANCE, appearance(data.preferences()));
        sections.put(Section.STUDY, study(data.settings()));
        sections.put(Section.ASSISTANT, assistant(data));
        sections.put(Section.PROFILE, profile());
        sections.put(Section.STORAGE, storage());
        sections.put(Section.ABOUT, about());
        VBox cards = new VBox(24);
        cards.getStyleClass().add("settings-sections");
        cards.getChildren().setAll(sections.values());
        cards.setMaxWidth(760);
        HBox.setHgrow(cards, Priority.ALWAYS);

        index.getChildren().clear();
        indexItems.clear();
        index.getStyleClass().setAll("settings-index");
        index.setMinWidth(INDEX_WIDTH);
        index.setPrefWidth(INDEX_WIDTH);
        index.setMaxWidth(INDEX_WIDTH);
        // As tall as its items, so it can travel down beside the sections.
        index.setMaxHeight(Region.USE_PREF_SIZE);
        for (Section section : Section.values()) {
            ToggleButton item = new ToggleButton(section.title());
            item.getStyleClass().setAll("settings-index-item");
            item.setToggleGroup(indexGroup);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setMnemonicParsing(false);
            item.setOnAction(event -> scrollTo(section));
            indexItems.put(section, item);
            index.getChildren().add(item);
        }
        select(Section.APPEARANCE);
        indexGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null) {
                old.setSelected(true);
            }
        });

        HBox body = new HBox(40, index, cards);
        body.getStyleClass().add("settings-body");
        PageHeader header = new PageHeader("Settings").subtitle("Changes apply straight away.");
        show(header, body);
        followScroll(body);
        loadSpaceUsed();
    }

    /**
     * Keeps the index in view while the sections scroll, and marks the section at the top. The marking waits
     * for the layout after a scroll, so it reads where the sections ended up; a section chosen in the index
     * stays marked until the page moves away from where that choice scrolled it (the last sections can't
     * reach the top).
     */
    private void followScroll(HBox body) {
        if (scrollSpy != null) {
            page().vvalueProperty().removeListener(scrollSpy);
        }
        scrollSpy = (obs, old, value) -> {
            placeIndex(body);
            if (!spyQueued) {
                spyQueued = true;
                javafx.application.Platform.runLater(() -> {
                    spyQueued = false;
                    placeIndex(body);
                    markSection();
                });
            }
        };
        page().vvalueProperty().addListener(scrollSpy);
    }

    private double scrollTop() {
        Node content = page().getContent();
        double contentHeight = content.getLayoutBounds().getHeight();
        double viewport = page().getViewportBounds().getHeight();
        return Math.max(0, (contentHeight - viewport) * page().getVvalue());
    }

    private void placeIndex(HBox body) {
        Node content = page().getContent();
        if (body.getScene() == null) {
            return;
        }
        Bounds bodyBounds = content.sceneToLocal(body.localToScene(body.getLayoutBounds()));
        double maxShift = Math.max(0, bodyBounds.getHeight() - index.getHeight());
        index.setTranslateY(Math.min(maxShift, Math.max(0, scrollTop() - bodyBounds.getMinY() + 16)));
    }

    private void markSection() {
        Node content = page().getContent();
        double top = scrollTop();
        if (pinned != null && Math.abs(top - pinnedTop) < 2) {
            select(pinned);
            return;
        }
        pinned = null;
        double viewport = page().getViewportBounds().getHeight();
        Section current = Section.APPEARANCE;
        for (Map.Entry<Section, Node> entry : sections.entrySet()) {
            Node card = entry.getValue();
            if (card.getScene() == null) {
                continue;
            }
            Bounds bounds = content.sceneToLocal(card.localToScene(card.getLayoutBounds()));
            if (bounds.getMinY() <= top + viewport * 0.3) {
                current = entry.getKey();
            }
        }
        if (page().getVvalue() >= 0.999) {
            current = Section.ABOUT;
        }
        select(current);
    }

    private void select(Section section) {
        ToggleButton item = indexItems.get(section);
        if (item != null && !item.isSelected()) {
            item.setSelected(true);
        }
    }

    // ---- Appearance ------------------------------------------------------------------------------------

    private Node appearance(AppPreferences preferences) {
        theme = new SegmentedControl<>(List.of(ThemePreference.values()), SettingsPage::themeLabel);
        theme.setValue(preferences.theme());
        theme.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                savePreferences(p -> p.withTheme(value));
            }
        });
        theme.setAccessibleText("Theme");
        reduceMotion = new ToggleSwitch();
        reduceMotion.setSelected(preferences.reduceMotion());
        reduceMotion.selectedProperty().addListener((obs, old, on) -> savePreferences(p -> p.withReduceMotion(on)));
        reduceMotion.setAccessibleText("Reduce motion");
        return section(Section.APPEARANCE, "For every profile on this computer.",
                row("Theme", "Match your computer, or always use light or dark.", theme),
                row("Reduce motion", "Replace movement with simple fades.", reduceMotion));
    }

    private void showPreferences(AppPreferences preferences) {
        if (theme != null && theme.getValue() != preferences.theme()) {
            theme.setValue(preferences.theme());
        }
        if (reduceMotion != null && reduceMotion.isSelected() != preferences.reduceMotion()) {
            reduceMotion.setSelected(preferences.reduceMotion());
        }
    }

    // ---- Study -----------------------------------------------------------------------------------------

    private Node study(AppSettings settings) {
        Slider retention = new Slider(0.70, 0.97, settings.desiredRetention());
        retention.setBlockIncrement(0.01);
        retention.setMajorTickUnit(0.01);
        retention.setMinorTickCount(0);
        retention.setSnapToTicks(true);
        retention.setPrefWidth(220);
        retention.setAccessibleText("How well to remember");
        retention.getStyleClass().add("retention-slider");
        Label value = new Label();
        value.getStyleClass().add("retention-value");
        value.setMinWidth(Region.USE_PREF_SIZE);
        Chip recommended = new Chip("Recommended", Tone.ACCENT);
        Label meaning = new Label();
        meaning.getStyleClass().add("settings-row-detail");
        meaning.setWrapText(true);
        Runnable describe = () -> {
            double chosen = round(retention.getValue());
            value.setText(percent(chosen));
            meaning.setText(retentionMeaning(chosen));
            boolean isRecommended = Math.abs(chosen - RECOMMENDED_RETENTION) < 0.001;
            recommended.setVisible(isRecommended);
            recommended.setManaged(isRecommended);
        };
        describe.run();
        retention.valueProperty().addListener((obs, old, v) -> {
            describe.run();
            if (!retention.isValueChanging() && round(old.doubleValue()) != round(v.doubleValue())) {
                saveSetting(SettingKeys.DESIRED_RETENTION, round(v.doubleValue()));
            }
        });
        retention.valueChangingProperty().addListener((obs, was, changing) -> {
            if (!changing) {
                saveSetting(SettingKeys.DESIRED_RETENTION, round(retention.getValue()));
            }
        });
        HBox valueLine = new HBox(10, value, recommended);
        valueLine.setAlignment(Pos.CENTER_LEFT);
        VBox control = new VBox(8, valueLine, retention);
        control.setMinWidth(Region.USE_PREF_SIZE);

        Label title = new Label("How well to remember");
        title.getStyleClass().add("settings-row-title");
        Label detail = new Label("Questions come back just before you're likely to forget them. Aim higher for "
                + "more reviews and a stronger memory, or lower for fewer reviews.");
        detail.getStyleClass().add("settings-row-detail");
        detail.setWrapText(true);
        VBox text = new VBox(3, title, detail, meaning);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox retentionRow = new HBox(32, text, control);
        retentionRow.setAlignment(Pos.CENTER_LEFT);
        retentionRow.getStyleClass().add("settings-row");

        Spinner<Integer> size = spinner(5, 100, settings.sessionSize(), 5, SettingKeys.SESSION_SIZE, "Session length");
        Spinner<Integer> perDay = spinner(0, 200, settings.newItemsPerDay(), 5, SettingKeys.NEW_ITEMS_PER_DAY,
                "New questions per day");
        return section(Section.STUDY, "For this profile.", retentionRow,
                row("Session length", "How many questions a Study next session holds.", size),
                row("New questions per day", "How many questions you haven't seen before can join your reviews each day.",
                        perDay));
    }

    /** What a desired retention means in plain words. */
    static String retentionMeaning(double retention) {
        int percent = (int) Math.round(retention * 100);
        String share = percent % 10 == 0 ? (percent / 10) + " in 10" : percent + " in 100";
        return "You'll remember about " + share + " questions when they come back.";
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String percent(double value) {
        return Math.round(value * 100) + "%";
    }

    private Spinner<Integer> spinner(int min, int max, int value, int step, SettingKey<Integer> key, String name) {
        Spinner<Integer> spinner = new Spinner<>(min, max, value, step);
        spinner.setEditable(true);
        spinner.setPrefWidth(120);
        spinner.setAccessibleText(name);
        spinner.getStyleClass().add("settings-spinner");
        spinner.valueProperty().addListener((obs, old, chosen) -> {
            if (chosen != null && !chosen.equals(old)) {
                saveSetting(key, chosen);
            }
        });
        // A typed number counts when the field loses focus, not only on Enter.
        spinner.getEditor().focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commit(spinner);
            }
        });
        return spinner;
    }

    private static void commit(Spinner<Integer> spinner) {
        String text = spinner.getEditor().getText();
        try {
            spinner.getValueFactory().setValue(Integer.valueOf(text.strip()));
        } catch (NumberFormatException e) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
        if (spinner.getValue() != null) {
            spinner.getEditor().setText(String.valueOf(spinner.getValue()));
        }
    }

    // ---- Study assistant ---------------------------------------------------------------------------------

    private Node assistant(Data data) {
        assistantMessage.getStyleClass().add("settings-row-detail");
        assistantMessage.setWrapText(true);
        restart.getStyleClass().add("small");
        restart.setOnAction(event -> {
            restart.setDisable(true);
            run(() -> {
                workspace().assistant().restart();
                return true;
            }, ok -> restart.setDisable(false), message -> {
                restart.setDisable(false);
                Toasts.error(page(), message);
            });
        });
        showAssistant(data.assistant());
        VBox text = new VBox(8, assistantPill, assistantMessage);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox status = new HBox(24, text, restart);
        status.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("settings-row");

        showModels(data.models());
        Label choiceTitle = new Label("How it answers");
        choiceTitle.getStyleClass().add("settings-row-title");
        Label choiceDetail = new Label("Changing this restarts the study assistant, which takes a moment.");
        choiceDetail.getStyleClass().add("settings-row-detail");
        choiceDetail.setWrapText(true);
        VBox choice = new VBox(14, new VBox(3, choiceTitle, choiceDetail), modelChoices);
        choice.getStyleClass().add("settings-row");

        ToggleSwitch figures = new ToggleSwitch();
        figures.setSelected(data.settings().describeFigures());
        figures.setAccessibleText("Describe figures and diagrams");
        figures.selectedProperty().addListener((obs, old, on) -> saveSetting(SettingKeys.DESCRIBE_FIGURES, on));
        return section(Section.ASSISTANT, "Answers your questions and writes practice questions. Everything stays on "
                        + "this computer.", status, choice,
                row("Describe figures and diagrams", "Lets answers draw on charts and diagrams in your PDFs. Adding a "
                        + "PDF takes a little longer.", figures));
    }

    private void showAssistant(AssistantStatus status) {
        assistantPill.set(switch (status.state()) {
            case READY -> StatusPill.Status.READY;
            case STARTING -> StatusPill.Status.WORKING;
            case UNAVAILABLE -> StatusPill.Status.NEEDS_REVIEW;
            case FAILED -> StatusPill.Status.FAILED;
        }, switch (status.state()) {
            case READY -> "Ready";
            case STARTING -> "Getting ready";
            case UNAVAILABLE -> "Can't run here";
            case FAILED -> "Stopped";
        });
        assistantMessage.setText(status.message().text());
        restart.setVisible(status.canRestart());
        restart.setManaged(status.canRestart());
    }

    private void showModels(List<ModelOption> options) {
        ToggleGroup group = new ToggleGroup();
        List<Node> rows = new ArrayList<>();
        ModelChoice recommended = options.stream().filter(ModelOption::recommended).map(ModelOption::choice)
                .findFirst().orElse(null);
        for (ModelOption option : options) {
            RadioButton button = new RadioButton(modelLabel(option.choice()));
            button.setToggleGroup(group);
            button.setSelected(option.selected());
            button.setDisable(!option.available() && !option.selected());
            button.setUserData(option.choice());
            button.getStyleClass().add("model-option");
            button.setMnemonicParsing(false);
            Label detail = new Label(modelDetail(option, recommended));
            detail.getStyleClass().addAll("settings-row-detail", "model-option-detail");
            detail.setWrapText(true);
            HBox head = new HBox(8, button);
            head.setAlignment(Pos.CENTER_LEFT);
            if (option.recommended()) {
                head.getChildren().add(new Chip("Best fit here", Tone.ACCENT));
            }
            VBox row = new VBox(4, head, detail);
            row.getStyleClass().add("model-option-row");
            if (button.isDisabled()) {
                row.getStyleClass().add("unavailable");
            }
            button.setAccessibleHelp(detail.getText());
            rows.add(row);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                return;
            }
            ModelChoice choice = (ModelChoice) selected.getUserData();
            run(() -> {
                workspace().assistant().setModelChoice(choice);
                return choice;
            }, saved -> { }, message -> {
                Toasts.error(page(), message);
                if (old != null) {
                    old.setSelected(true);
                }
            });
        });
        modelChoices.getChildren().setAll(rows);
    }

    /** The name of a way of answering, as people see it. */
    static String modelLabel(ModelChoice choice) {
        return switch (choice) {
            case AUTO -> "Automatic";
            case CHAT_4B -> "Quick";
            case CHAT_9B -> "Thorough";
        };
    }

    /** What a way of answering does and needs, and whether this computer has it. */
    static String modelDetail(ModelOption option, ModelChoice recommended) {
        String base = switch (option.choice()) {
            case AUTO -> recommended == null ? "Picks the best fit for this computer."
                    : "Picks the best fit for this computer: " + modelLabel(recommended) + ".";
            case CHAT_4B -> "Fast answers that run well on most computers.";
            case CHAT_9B -> "More careful answers, a little slower. Needs 12 GB of memory or more; best with 16 GB.";
        };
        return option.available() ? base : base + " Not available on this computer.";
    }

    // ---- Profile ---------------------------------------------------------------------------------------

    private Node profile() {
        profileLine.setAlignment(Pos.CENTER_LEFT);
        profileLine.getStyleClass().add("settings-row");
        showProfile(context.profile());
        return section(Section.PROFILE, "Each profile keeps its own courses and progress.", profileLine);
    }

    private void showProfile(ProfileSummary profile) {
        if (profile == null) {
            profileLine.getChildren().clear();
            return;
        }
        Avatar avatar = new Avatar(profile.initials(), profile.avatarHue(), 44);
        Label name = new Label(profile.displayName());
        name.getStyleClass().add("text-heading");
        name.setMnemonicParsing(false);
        Label detail = new Label(profile.passwordProtected() ? "Has a password" : "No password");
        detail.getStyleClass().add("settings-row-detail");
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button edit = new Button("Edit profile");
        edit.getStyleClass().add("small");
        edit.setOnAction(event -> actions.edit(context.profile(), page(), saved -> { }));
        Button password = new Button(profile.passwordProtected() ? "Change password" : "Add a password");
        password.getStyleClass().add("small");
        password.setOnAction(event -> {
            ProfileSummary current = context.profile();
            if (current.passwordProtected()) {
                actions.changePassword(current, page(), saved -> { });
            } else {
                actions.addPassword(current, page(), saved -> { });
            }
        });
        Button switchProfile = new Button("Switch profile");
        switchProfile.getStyleClass().addAll("small", "quiet");
        switchProfile.setOnAction(event -> navigator.navigate(Destination.PROFILES));
        HBox buttons = new HBox(8, edit, password, switchProfile);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        profileLine.getChildren().setAll(avatar, text, buttons);
    }

    // ---- Storage ---------------------------------------------------------------------------------------

    private Node storage() {
        SystemFiles system = files.get();
        Node where;
        if (system.canReveal()) {
            Button reveal = new Button("Show in " + SystemFiles.fileManagerName());
            reveal.getStyleClass().add("small");
            reveal.setOnAction(event -> {
                String id = context.openProfileId();
                if (id != null) {
                    system.reveal(context.paths().profile(id).root());
                }
            });
            where = row("Where it's kept", "On this computer, in LearnSphere's own folder for this profile.", reveal);
        } else {
            where = row("Where it's kept", "On this computer, in LearnSphere's own folder for this profile.", new Region());
        }
        spaceUsed.getStyleClass().add("space-used");
        spaceUsed.setText("…");
        spaceUsed.setMinWidth(Region.USE_PREF_SIZE);
        Button backup = new Button("Back up…");
        backup.getStyleClass().add("small");
        backup.setOnAction(event -> actions.backup(context.profile(), page()));
        return section(Section.STORAGE, null, where,
                row("Space used", "Your PDFs, questions and progress in this profile.", spaceUsed),
                row("Back up", "Save this profile's courses, PDFs and progress in one file you can keep somewhere "
                        + "safe, then restore it from the profile choice.", backup));
    }

    private void loadSpaceUsed() {
        String id = context.openProfileId();
        if (id == null) {
            spaceUsed.setText("");
            return;
        }
        run(() -> StorageUsage.bytesIn(context.paths().profile(id).root()),
                bytes -> spaceUsed.setText(StorageUsage.format(bytes)),
                message -> spaceUsed.setText("Unknown"));
    }

    /** The space used as shown, e.g. "12.4 MB" (tests). */
    public String spaceUsedText() {
        return spaceUsed.getText();
    }

    // ---- About -----------------------------------------------------------------------------------------

    private Node about() {
        Label name = new Label("LearnSphere");
        name.getStyleClass().addAll("text-title", "about-name");
        Label version = new Label("Version " + AppInfo.version());
        version.getStyleClass().add("text-caption");
        Label line = new Label("A study companion that works offline. Your PDFs, questions and progress stay on "
                + "this computer.");
        line.getStyleClass().add("settings-row-detail");
        line.setWrapText(true);
        VBox text = new VBox(4, name, version, line);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button licences = new Button("Licences");
        licences.getStyleClass().add("small");
        licences.setOnAction(event -> LicencesDialog.show(page()));
        HBox about = new HBox(24, text, licences);
        about.setAlignment(Pos.CENTER_LEFT);
        about.getStyleClass().add("settings-row");
        return section(Section.ABOUT, null, about);
    }

    // ---- Shared ----------------------------------------------------------------------------------------

    private <T> void saveSetting(SettingKey<T> key, T value) {
        run(() -> {
            workspace().settings().set(key, value);
            return null;
        }, ignored -> { }, message -> Toasts.error(page(), message));
    }

    private void savePreferences(UnaryOperator<AppPreferences> change) {
        run(() -> context.preferences().update(change), ignored -> { }, message -> Toasts.error(page(), message));
    }

    private static Node section(Section section, String subtitle, Node... rows) {
        Label heading = new Label(section.title());
        heading.getStyleClass().addAll("text-title", "settings-section-title");
        VBox head = new VBox(4, heading);
        if (subtitle != null) {
            Label sub = new Label(subtitle);
            sub.getStyleClass().add("settings-section-subtitle");
            sub.setWrapText(true);
            head.getChildren().add(sub);
        }
        Card card = new Card(head);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "settings-section");
        card.setId("settings-" + section.name().toLowerCase(Locale.ROOT));
        for (Node row : rows) {
            Region rule = new Region();
            rule.getStyleClass().add("hairline");
            card.getChildren().addAll(rule, row);
        }
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
        HBox row = new HBox(32, text, control);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("settings-row");
        if (control instanceof Region region) {
            region.setMinWidth(Region.USE_PREF_SIZE);
        }
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
