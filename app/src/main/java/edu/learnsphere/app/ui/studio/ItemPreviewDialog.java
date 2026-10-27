package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.Workspace;
import java.time.Clock;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * A question as learners see it, with its answer, the passage it came from and what learners flagged about
 * it. Opened from a flag, so the creator can judge the flag before resolving it.
 */
final class ItemPreviewDialog {

    private ItemPreviewDialog() {
    }

    /** Loads the question {@code itemId} and shows it over the window of {@code anchor}. */
    static void show(Node anchor, Workspace workspace, Clock clock, String itemId) {
        StackPane body = new StackPane(new ActivityIndicator(24));
        body.setMinHeight(160);
        StudioDialog dialog = StudioDialog.of("Flagged question").content(body).wide().closeOnly("Close");
        dialog.show(anchor);
        UiTasks.run(() -> workspace.items().get(itemId), detail -> body.getChildren().setAll(content(detail, clock)),
                message -> {
                    Label error = new Label(message.text());
                    error.getStyleClass().add("text-muted");
                    error.setWrapText(true);
                    body.getChildren().setAll(error);
                });
    }

    private static Node content(ItemDetail detail, Clock clock) {
        LearningItem item = detail.item();
        FlowPane tags = new FlowPane(8, 8, new Chip(StudioPage.typeLabel(item.type()), Tone.NEUTRAL));
        tags.getChildren().add(statusChip(item.status()));
        Label prompt = new Label(item.prompt());
        prompt.getStyleClass().add("preview-item-prompt");
        prompt.setWrapText(true);
        prompt.setMnemonicParsing(false);
        VBox box = new VBox(14, tags, prompt);
        if (item.type() == ItemType.MCQ) {
            VBox options = new VBox(6);
            options.getChildren().add(option(item.answer(), true));
            item.distractors().forEach(d -> options.getChildren().add(option(d, false)));
            box.getChildren().add(options);
        } else {
            Label answer = new Label((item.type() == ItemType.FLASHCARD ? "Back: " : "Answer: ") + item.answer());
            answer.getStyleClass().add("preview-item-answer");
            answer.setWrapText(true);
            answer.setMnemonicParsing(false);
            box.getChildren().add(answer);
        }
        if (detail.source() != null) {
            Label where = new Label(detail.source().ref().documentTitle() + " · "
                    + Formats.pages(detail.source().ref().pageStart(), detail.source().ref().pageEnd()));
            where.getStyleClass().add("text-caption");
            Label passage = new Label(detail.source().text());
            passage.getStyleClass().addAll("text-reading", "review-passage");
            passage.setWrapText(true);
            VBox source = new VBox(6, where, passage);
            source.getStyleClass().add("review-source");
            box.getChildren().add(source);
        }
        if (!detail.flags().isEmpty()) {
            VBox flags = new VBox(8);
            for (Flag flag : detail.flags()) {
                Label reason = new Label(StudioFormats.reasonLabel(flag.reason())
                        + (flag.isOpen() ? "" : " · resolved") + " · " + Formats.ago(flag.createdAt(), clock));
                reason.getStyleClass().addAll("medium", "flag-prompt");
                VBox text = new VBox(2, reason);
                if (!flag.note().isBlank()) {
                    Label note = new Label("“" + flag.note() + "”");
                    note.getStyleClass().add("flag-note");
                    note.setWrapText(true);
                    note.setMnemonicParsing(false);
                    text.getChildren().add(note);
                }
                HBox row = new HBox(10, Icons.of(Feather.FLAG, 14), text);
                row.setAlignment(Pos.TOP_LEFT);
                flags.getChildren().add(row);
            }
            box.getChildren().add(flags);
        }
        return box;
    }

    private static Node statusChip(ItemStatus status) {
        return switch (status) {
            case APPROVED -> new Chip("Approved", Tone.SUCCESS);
            case DRAFT -> new Chip("Draft", Tone.WARNING);
            case REJECTED -> new Chip("Rejected", Tone.NEUTRAL);
            case RETIRED -> new Chip("Retired", Tone.NEUTRAL);
        };
    }

    private static Node option(String text, boolean correct) {
        Label label = new Label(text, Icons.of(correct ? Feather.CHECK_CIRCLE : Feather.CIRCLE, 14));
        label.getStyleClass().addAll("review-option", correct ? "correct" : "distractor");
        label.setWrapText(true);
        label.setMnemonicParsing(false);
        return label;
    }
}
