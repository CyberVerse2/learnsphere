package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.FormDialog;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ProfileSummary;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.stage.Window;

/**
 * What can be done to a profile, each as a small form over the window: edit its name and colour; add,
 * change or remove its password; back it up to one file; restore a backup as another profile; delete it
 * after typing its name. Changes to the open profile show at once in the sidebar and the window title.
 * The profile choice and Settings share these forms. FX thread.
 *
 * <p>A profile with a password that is not the open one asks for that password before it is edited, backed
 * up or deleted, so nobody without it can rename, copy or remove it. The notices these actions leave go to
 * the window rather than the page they started on, which may have been replaced by the time the work ends.
 */
public final class ProfileActions {

    /** Shown instead of the delete form for the profile that is open. */
    public static final UserMessage PROFILE_OPEN = UserMessage.of("This profile is open. Close it and try again.");
    /** Shown when the new password and its confirmation differ. */
    public static final UserMessage PASSWORDS_DIFFER = UserMessage.of("The passwords don't match. Type them again.");
    /** What backups are called in the system's file dialogs. */
    public static final String BACKUP_KIND = "LearnSphere backups";

    private final AppContext context;
    private final Supplier<SystemFiles> files;

    public ProfileActions(AppContext context, Supplier<SystemFiles> files) {
        this.context = Objects.requireNonNull(context, "context");
        this.files = Objects.requireNonNull(files, "files");
    }

    /** Edits the name and avatar colour; {@code after} gets the saved profile. */
    public FormDialog.Handle edit(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        TextField name = new TextField(profile.displayName());
        name.getStyleClass().add("profile-name-field");
        AvatarPicker colours = new AvatarPicker(profile.avatarHue());
        colours.setName(profile.displayName());
        name.textProperty().addListener((obs, old, text) -> colours.setName(text));
        PasswordField password = passwordFieldFor(profile);
        List<Node> fields = new ArrayList<>(List.of(FormDialog.field("Name", name),
                FormDialog.field("Avatar colour", colours)));
        if (password != null) {
            fields.add(FormDialog.field("This profile's password", password));
        }
        return FormDialog.of("Edit profile")
                .content(fields.toArray(Node[]::new))
                .confirm("Save changes")
                .confirmEnabled(whenever(() -> !name.getText().isBlank() && filledOrAbsent(password), name, password))
                .action(() -> {
                    String chosen = name.getText().strip();
                    int hue = colours.getHue();
                    char[] secret = password == null ? null : take(password);
                    return () -> {
                        requirePassword(profile, secret);
                        ProfileSummary saved = profile;
                        if (!chosen.equals(profile.displayName())) {
                            saved = context.profiles().rename(profile.id(), chosen);
                        }
                        if (hue != profile.avatarHue()) {
                            saved = context.profiles().setAvatarHue(profile.id(), hue);
                        }
                        context.refreshProfile();
                        return saved;
                    };
                }, saved -> {
                    Toasts.success(window, "Changes saved");
                    after.accept(saved);
                })
                .show(window);
    }

    /** Adds a password to a profile without one. */
    public FormDialog.Handle addPassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        PasswordField password = new PasswordField();
        PasswordField again = new PasswordField();
        return FormDialog.of("Add a password")
                .message("You'll enter it each time you open this profile. Keep it somewhere safe: "
                        + "a forgotten password can't be recovered.")
                .content(FormDialog.field("Password", password), FormDialog.field("Type it again", again))
                .confirm("Add password")
                .confirmEnabled(filled(password, again))
                .validate(() -> matches(password, again) ? null : PASSWORDS_DIFFER)
                .action(() -> {
                    char[] chosen = password.getText().toCharArray();
                    return () -> {
                        ProfileSummary saved = context.profiles().setPassword(profile.id(), null, chosen);
                        context.refreshProfile();
                        return saved;
                    };
                }, saved -> {
                    Toasts.success(window, "Password added");
                    after.accept(saved);
                })
                .show(window);
    }

    /** Changes the password of a protected profile (the current one first). */
    public FormDialog.Handle changePassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        PasswordField current = new PasswordField();
        PasswordField password = new PasswordField();
        PasswordField again = new PasswordField();
        return FormDialog.of("Change password")
                .content(FormDialog.field("Current password", current), FormDialog.field("New password", password),
                        FormDialog.field("Type the new password again", again))
                .confirm("Change password")
                .confirmEnabled(filled(current, password, again))
                .validate(() -> matches(password, again) ? null : PASSWORDS_DIFFER)
                .action(() -> {
                    char[] old = take(current);
                    char[] chosen = password.getText().toCharArray();
                    return () -> {
                        ProfileSummary saved = context.profiles().setPassword(profile.id(), old, chosen);
                        context.refreshProfile();
                        return saved;
                    };
                }, saved -> {
                    Toasts.success(window, "Password changed");
                    after.accept(saved);
                })
                .show(window);
    }

    /** Removes the password of a protected profile, after the current one. */
    public FormDialog.Handle removePassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        PasswordField current = new PasswordField();
        return FormDialog.of("Remove the password?")
                .message("Anyone who uses this computer can then open this profile.")
                .content(FormDialog.field("Current password", current))
                .confirm("Remove password")
                .confirmEnabled(filled(current))
                .action(() -> {
                    char[] old = take(current);
                    return () -> {
                        ProfileSummary saved = context.profiles().clearPassword(profile.id(), old);
                        context.refreshProfile();
                        return saved;
                    };
                }, saved -> {
                    Toasts.success(window, "Password removed");
                    after.accept(saved);
                })
                .show(window);
    }

    /**
     * Asks where to save a backup of {@code profile}, then saves it while a progress card shows. Works whether
     * or not the profile is open; a protected profile that isn't open asks for its password first.
     */
    public void backup(ProfileSummary profile, Node anchor) {
        Node window = window(anchor);
        PasswordField password = passwordFieldFor(profile);
        if (password == null) {
            chooseAndBackUp(profile, window);
            return;
        }
        FormDialog.of("Back up " + profile.displayName())
                .message("Enter this profile's password to save a copy of it.")
                .content(FormDialog.field("Password", password))
                .confirm("Continue")
                .confirmEnabled(filled(password))
                .action(() -> {
                    char[] secret = take(password);
                    return () -> {
                        requirePassword(profile, secret);
                        return true;
                    };
                }, checked -> chooseAndBackUp(profile, window))
                .show(window);
    }

    private void chooseAndBackUp(ProfileSummary profile, Node window) {
        Window owner = window.getScene() == null ? null : window.getScene().getWindow();
        String suggested = safeFileName(profile.displayName()) + " backup " + LocalDate.now(context.clock()) + ".zip";
        files.get().chooseSave(owner, "Back up profile", suggested, BACKUP_KIND, "zip").ifPresent(zip -> {
            FormDialog.Handle progress = FormDialog.progress(window, "Backing up " + profile.displayName(),
                    "Saving the courses, PDFs and progress in this profile. This can take a minute when there are many PDFs.");
            UiTasks.run(() -> {
                context.profiles().backup(profile.id(), zip);
                return zip;
            }, saved -> {
                progress.close();
                Toasts.success(window, "Backup saved");
            }, message -> {
                progress.close();
                Toasts.error(window, message);
            });
        });
    }

    /**
     * Asks for a backup and restores it as another profile while a progress card shows; {@code after} gets the
     * restored profile.
     */
    public void restore(Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        Window owner = window.getScene() == null ? null : window.getScene().getWindow();
        files.get().chooseOpen(owner, "Restore a profile", BACKUP_KIND, "zip").ifPresent(zip -> restore(zip, window, after));
    }

    /** Restores the backup {@code zip} as another profile while a progress card shows. */
    public void restore(Path zip, Node anchor, Consumer<ProfileSummary> after) {
        Node window = window(anchor);
        FormDialog.Handle progress = FormDialog.progress(window, "Restoring a profile",
                "Adding its courses, PDFs and progress to this computer.");
        UiTasks.run(() -> context.profiles().restore(zip), restored -> {
            progress.close();
            Toasts.success(window, "Profile restored");
            after.accept(restored);
        }, message -> {
            progress.close();
            Toasts.error(window, message);
        });
    }

    /**
     * Deletes {@code profile} once its name is typed (and its password, when it has one). The open profile can't
     * be deleted: the form says so and offers to close it instead, after which {@code closed} runs.
     */
    public FormDialog.Handle delete(ProfileSummary profile, Node anchor, Runnable deleted, Runnable closed) {
        Node window = window(anchor);
        if (profile.id().equals(context.openProfileId())) {
            return FormDialog.of("Delete " + profile.displayName() + "?")
                    .message(PROFILE_OPEN.text())
                    .confirm("Close profile")
                    .action(() -> () -> {
                        context.closeWorkspace();
                        return true;
                    }, ignored -> closed.run())
                    .show(window);
        }
        TextField typed = new TextField();
        typed.setPromptText(profile.displayName());
        typed.getStyleClass().add("delete-confirm-field");
        PasswordField password = passwordFieldFor(profile);
        BooleanBinding ready = whenever(() -> typed.getText().strip().equalsIgnoreCase(profile.displayName().strip())
                && filledOrAbsent(password), typed, password);
        List<Node> fields = new ArrayList<>(List.of(FormDialog.field("Type the profile's name to confirm", typed)));
        if (password != null) {
            fields.add(FormDialog.field("This profile's password", password));
        }
        return FormDialog.of("Delete " + profile.displayName() + "?")
                .message("Its courses, PDFs and progress are removed from this computer. This can't be undone.")
                .content(fields.toArray(Node[]::new))
                .confirm("Delete profile")
                .danger()
                .confirmEnabled(ready)
                .action(() -> {
                    char[] secret = password == null ? null : take(password);
                    return () -> {
                        requirePassword(profile, secret);
                        context.profiles().delete(profile.id());
                        return true;
                    };
                }, ignored -> {
                    Toasts.success(window, "Profile deleted");
                    deleted.run();
                })
                .show(window);
    }

    /**
     * The window's own layer for forms and notices: unlike the page that started an action, it is still there
     * when the action ends.
     */
    private static Node window(Node anchor) {
        return Overlays.find(anchor).host();
    }

    /** A field for the profile's password when one is needed to act on it; null when it isn't. */
    private PasswordField passwordFieldFor(ProfileSummary profile) {
        if (!profile.passwordProtected() || profile.id().equals(context.openProfileId())) {
            return null;
        }
        PasswordField password = new PasswordField();
        password.getStyleClass().add("profile-password-check");
        return password;
    }

    /**
     * Checks {@code password} (wiped afterwards) against the profile's; does nothing when it is null (no
     * password needed). Blocking.
     *
     * @throws InvalidInputException with {@link UserMessage#WRONG_PASSWORD} when it is wrong
     */
    private void requirePassword(ProfileSummary profile, char[] password) {
        if (password == null) {
            return;
        }
        boolean right;
        try {
            right = context.profiles().checkPassword(profile.id(), password.clone());
        } finally {
            Arrays.fill(password, '\0');
        }
        if (!right) {
            throw new InvalidInputException(UserMessage.WRONG_PASSWORD, "Wrong password for profile " + profile.id());
        }
    }

    /** {@code check}, worked out again whenever one of the (non-null) {@code fields} changes. */
    private static BooleanBinding whenever(BooleanSupplier check, TextInputControl... fields) {
        return Bindings.createBooleanBinding(check::getAsBoolean, Arrays.stream(fields).filter(Objects::nonNull)
                .map(TextInputControl::textProperty).toArray(Observable[]::new));
    }

    private static boolean filledOrAbsent(PasswordField password) {
        return password == null || !password.getText().isEmpty();
    }

    private static BooleanBinding filled(PasswordField... fields) {
        return Bindings.createBooleanBinding(() -> Arrays.stream(fields).noneMatch(f -> f.getText().isEmpty()),
                Arrays.stream(fields).map(PasswordField::textProperty).toArray(Observable[]::new));
    }

    private static boolean matches(PasswordField password, PasswordField again) {
        return password.getText().equals(again.getText());
    }

    /**
     * The current password as a fresh array (the service wipes it after use); the field empties, so a wrong one
     * is typed again from scratch.
     */
    private static char[] take(PasswordField current) {
        char[] value = current.getText().toCharArray();
        current.clear();
        return value;
    }

    /** {@code name} without characters file systems refuse. */
    static String safeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").strip().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? "LearnSphere" : cleaned.length() > 60 ? cleaned.substring(0, 60).strip() : cleaned;
    }
}
