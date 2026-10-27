package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.FormDialog;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.service.ProfileSummary;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.scene.Node;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Window;

/**
 * What can be done to a profile, each as a small form over the window: edit its name and colour; add,
 * change or remove its password; back it up to one file; restore a backup as another profile; delete it
 * after typing its name. Changes to the open profile show at once in the sidebar and the window title.
 * The profile choice and Settings share these forms. FX thread.
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
        TextField name = new TextField(profile.displayName());
        name.getStyleClass().add("profile-name-field");
        AvatarPicker colours = new AvatarPicker(profile.avatarHue());
        colours.setName(profile.displayName());
        name.textProperty().addListener((obs, old, text) -> colours.setName(text));
        return FormDialog.of("Edit profile")
                .content(FormDialog.field("Name", name), FormDialog.field("Avatar colour", colours))
                .confirm("Save changes")
                .confirmEnabled(Bindings.createBooleanBinding(() -> !name.getText().isBlank(), name.textProperty()))
                .action(() -> {
                    String chosen = name.getText().strip();
                    int hue = colours.getHue();
                    return () -> {
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
                    Toasts.success(anchor, "Changes saved");
                    after.accept(saved);
                })
                .show(anchor);
    }

    /** Adds a password to a profile without one. */
    public FormDialog.Handle addPassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
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
                    Toasts.success(anchor, "Password added");
                    after.accept(saved);
                })
                .show(anchor);
    }

    /** Changes the password of a protected profile (the current one first). */
    public FormDialog.Handle changePassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
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
                    Toasts.success(anchor, "Password changed");
                    after.accept(saved);
                })
                .show(anchor);
    }

    /** Removes the password of a protected profile, after the current one. */
    public FormDialog.Handle removePassword(ProfileSummary profile, Node anchor, Consumer<ProfileSummary> after) {
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
                    Toasts.success(anchor, "Password removed");
                    after.accept(saved);
                })
                .show(anchor);
    }

    /**
     * Asks where to save a backup of {@code profile}, then saves it while a progress card shows. Works whether
     * or not the profile is open.
     */
    public void backup(ProfileSummary profile, Node anchor) {
        Window owner = anchor.getScene() == null ? null : anchor.getScene().getWindow();
        String suggested = safeFileName(profile.displayName()) + " backup " + LocalDate.now(context.clock()) + ".zip";
        files.get().chooseSave(owner, "Back up profile", suggested, BACKUP_KIND, "zip").ifPresent(zip -> {
            FormDialog.Handle progress = FormDialog.progress(anchor, "Backing up " + profile.displayName(),
                    "Saving the courses, PDFs and progress in this profile. This can take a minute when there are many PDFs.");
            UiTasks.run(() -> {
                context.profiles().backup(profile.id(), zip);
                return zip;
            }, saved -> {
                progress.close();
                Toasts.success(anchor, "Backup saved");
            }, message -> {
                progress.close();
                Toasts.error(anchor, message);
            });
        });
    }

    /**
     * Asks for a backup and restores it as another profile while a progress card shows; {@code after} gets the
     * restored profile.
     */
    public void restore(Node anchor, Consumer<ProfileSummary> after) {
        Window owner = anchor.getScene() == null ? null : anchor.getScene().getWindow();
        files.get().chooseOpen(owner, "Restore a profile", BACKUP_KIND, "zip").ifPresent(zip -> restore(zip, anchor, after));
    }

    /** Restores the backup {@code zip} as another profile while a progress card shows. */
    public void restore(Path zip, Node anchor, Consumer<ProfileSummary> after) {
        FormDialog.Handle progress = FormDialog.progress(anchor, "Restoring a profile",
                "Adding its courses, PDFs and progress to this computer.");
        UiTasks.run(() -> context.profiles().restore(zip), restored -> {
            progress.close();
            Toasts.success(anchor, "Profile restored");
            after.accept(restored);
        }, message -> {
            progress.close();
            Toasts.error(anchor, message);
        });
    }

    /**
     * Deletes {@code profile} once its name is typed. The open profile can't be deleted: the form says so and
     * offers to close it instead, after which {@code closed} runs.
     */
    public FormDialog.Handle delete(ProfileSummary profile, Node anchor, Runnable deleted, Runnable closed) {
        if (profile.id().equals(context.openProfileId())) {
            return FormDialog.of("Delete " + profile.displayName() + "?")
                    .message(PROFILE_OPEN.text())
                    .confirm("Close profile")
                    .action(() -> () -> {
                        context.closeWorkspace();
                        return true;
                    }, ignored -> closed.run())
                    .show(anchor);
        }
        TextField typed = new TextField();
        typed.setPromptText(profile.displayName());
        typed.getStyleClass().add("delete-confirm-field");
        BooleanBinding nameTyped = Bindings.createBooleanBinding(
                () -> typed.getText().strip().equalsIgnoreCase(profile.displayName().strip()), typed.textProperty());
        return FormDialog.of("Delete " + profile.displayName() + "?")
                .message("Its courses, PDFs and progress are removed from this computer. This can't be undone.")
                .content(FormDialog.field("Type the profile's name to confirm", typed))
                .confirm("Delete profile")
                .danger()
                .confirmEnabled(nameTyped)
                .action(() -> () -> {
                    context.profiles().delete(profile.id());
                    return true;
                }, ignored -> {
                    Toasts.success(anchor, "Profile deleted");
                    deleted.run();
                })
                .show(anchor);
    }

    private static BooleanBinding filled(PasswordField... fields) {
        return Bindings.createBooleanBinding(() -> Arrays.stream(fields).noneMatch(f -> f.getText().isEmpty()),
                Arrays.stream(fields).map(PasswordField::textProperty).toArray(javafx.beans.Observable[]::new));
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
