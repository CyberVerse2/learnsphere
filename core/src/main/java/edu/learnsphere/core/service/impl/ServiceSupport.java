package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobUpdate;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Checks and messages shared by the course, document and item services. */
final class ServiceSupport {

    /** Shown when someone tries to change a course that was shared with them. */
    static final UserMessage SHARED_COURSE =
            UserMessage.of("This course was shared with you, so it can't be changed here.");

    /** How long a deletion waits for the background work it cancelled to stop. */
    static final Duration CANCEL_WAIT = Duration.ofSeconds(5);

    private ServiceSupport() {
    }

    /** {@code value} stripped of surrounding white space; never null. */
    static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * {@code value} stripped, or an {@link InvalidInputException} with {@code message} when it is blank or
     * longer than {@code maxLength}.
     */
    static String requireText(String value, int maxLength, UserMessage blank, UserMessage tooLong, String what) {
        String text = clean(value);
        if (text.isEmpty()) {
            throw new InvalidInputException(blank, "Blank " + what);
        }
        if (text.length() > maxLength) {
            throw new InvalidInputException(tooLong, what + " longer than " + maxLength + " characters");
        }
        return text;
    }

    /** The course, when it was created in this profile; otherwise a {@link ConflictException}. */
    static Course requireEditable(Course course) {
        if (!course.isCreatedHere()) {
            throw new ConflictException(SHARED_COURSE, "Course " + course.id() + " was imported");
        }
        return course;
    }

    /** The course, when it exists and was created in this profile. */
    static Course requireEditableCourse(Repositories repos, String courseId) {
        return requireEditable(repos.courses().get(courseId));
    }

    /**
     * The module, when it belongs to {@code courseId}; null for a null id.
     *
     * @throws NotFoundException when it does not exist or belongs to another course
     */
    static CourseModule requireModuleOf(Repositories repos, String courseId, String moduleId) {
        if (moduleId == null) {
            return null;
        }
        CourseModule module = repos.modules().get(moduleId);
        if (!module.courseId().equals(courseId)) {
            throw NotFoundException.of(UserMessage.MODULE_NOT_FOUND, "module of course " + courseId, moduleId);
        }
        return module;
    }

    /**
     * Cancels the active jobs that match {@code which} and waits up to {@link #CANCEL_WAIT} for the running
     * ones to stop, so their handlers do not write into rows that are about to be deleted.
     *
     * @return the ids of the jobs that were cancelled
     */
    static List<String> cancelJobs(JobQueue jobs, Repositories repos, Predicate<Job> which) {
        List<String> ids = repos.jobs().listActive().stream().filter(which).map(Job::id).toList();
        ids.forEach(jobs::cancel);
        awaitInactive(jobs, ids, CANCEL_WAIT);
        return ids;
    }

    /** Cancels the active jobs working on any of {@code subjectIds}; see {@link #cancelJobs}. */
    static List<String> cancelJobsFor(JobQueue jobs, Repositories repos, Collection<String> subjectIds) {
        Set<String> subjects = Set.copyOf(subjectIds);
        return cancelJobs(jobs, repos, job -> job.subjectId() != null && subjects.contains(job.subjectId()));
    }

    private static void awaitInactive(JobQueue jobs, Collection<String> ids, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (String id : ids) {
            while (jobs.find(id).map(JobUpdate::isActive).orElse(false) && System.nanoTime() < deadline) {
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
