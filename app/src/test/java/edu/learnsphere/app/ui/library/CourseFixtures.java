package edu.learnsphere.app.ui.library;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import java.util.ArrayList;
import java.util.List;

/** Courses made through the real services for the Home, Library and course page tests. */
public final class CourseFixtures {

    /** What {@link #flashcardCourse} made. */
    public record Built(Course course, List<CourseModule> modules, List<List<LearningItem>> cards) {

        public String id() {
            return course.id();
        }
    }

    private CourseFixtures() {
    }

    /**
     * A course made here with one module per entry of {@code cardsPerModule} ("Module 1", "Module 2", …), each
     * holding that many approved flashcards. The learner studies it when {@code enrol} is true.
     */
    public static Built flashcardCourse(Workspace ws, String title, boolean enrol, int... cardsPerModule) {
        Course course = ws.courses().create(new CourseDraft(title, "", "", "Ada Lovelace", 150));
        List<CourseModule> modules = new ArrayList<>();
        List<List<LearningItem>> cards = new ArrayList<>();
        int n = 0;
        for (int m = 0; m < cardsPerModule.length; m++) {
            CourseModule module = ws.courses().addModule(course.id(), "Module " + (m + 1));
            modules.add(module);
            List<LearningItem> inModule = new ArrayList<>();
            for (int i = 0; i < cardsPerModule[m]; i++) {
                n++;
                LearningItem item = ws.items().create(course.id(), module.id(),
                        ItemContent.flashcard("Front of card " + n, "Back of card " + n));
                ws.items().approve(item.id());
                inModule.add(item);
            }
            cards.add(inModule);
        }
        if (enrol) {
            ws.courses().enrol(course.id());
        }
        return new Built(ws.courses().get(course.id()).course(), modules, cards);
    }

    /** Runs {@code request} and rates every card it holds Good, as a learner turning through them would. */
    public static int study(Workspace ws, SessionRequest request) {
        StudySession session = ws.practice().start(request);
        for (SessionItem item : session.items()) {
            ws.practice().submit(session.id(), Response.flashcard(item.itemId(), Rating.GOOD, 9_000));
        }
        ws.practice().finish(session.id());
        return session.items().size();
    }
}
