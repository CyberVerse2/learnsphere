package edu.learnsphere.app.dev;

import edu.learnsphere.core.model.ClassInsight;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.Workspace;
import java.time.Duration;
import java.util.List;

/**
 * A class's progress sent back to the creator of a demo course: six learners, and for each approved question
 * how many of them answered it and how often they were right. The demo starts without it; the screenshot tour
 * adds it to show what a creator sees once their class has studied ({@link #seed(Workspace, String)}).
 */
final class DemoClassProgress {

    /**
     * One question's results across the class.
     *
     * @param learners learners who answered it
     * @param attempts answers given
     * @param accuracy share answered correctly, 0–1
     */
    record ItemResult(int learners, int attempts, double accuracy) {
    }

    /** The learners whose progress was imported. */
    static final List<String> LEARNERS = List.of("Ben Hughes", "Chen Wei", "Dara Novak", "Elif Yilmaz",
            "Farid Haddad", "Grace Mensah");

    /** Class accuracy of the approved questions, in course order. */
    private static final double[] ACCURACY = {0.64, 0.86, 0.91, 0.47, 0.78, 0.36, 0.69, 0.82};

    private DemoClassProgress() {
    }

    /** Adds the class's progress to {@code courseId} when {@code workspace} is the demo; else does nothing. */
    static void seed(Workspace workspace, String courseId) {
        if (workspace.progress() instanceof DemoProgress demo) {
            seed(demo.store(), courseId);
        }
    }

    static void seed(DemoStore store, String courseId) {
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            for (int i = 0; i < LEARNERS.size(); i++) {
                long at = now - Duration.ofHours(5L * (i + 1)).toMillis();
                ClassInsight insight = new ClassInsight(DemoStore.newId(), courseId, LEARNERS.get(i), "{}", at, at);
                store.insights.put(insight.id(), insight);
            }
            List<LearningItem> approved = store.itemsOf(courseId).stream()
                    .filter(item -> item.status() == ItemStatus.APPROVED).toList();
            for (int i = 0; i < approved.size(); i++) {
                int learners = LEARNERS.size() - (i % 3 == 2 ? 1 : 0);
                store.classResults.put(approved.get(i).id(),
                        new ItemResult(learners, learners * (2 + i % 3), ACCURACY[i % ACCURACY.length]));
            }
        }
    }
}
