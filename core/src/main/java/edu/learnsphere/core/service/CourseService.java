package edu.learnsphere.core.service;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Visibility;
import java.util.List;

/**
 * Courses, their modules, versions and enrolment. Changes publish
 * {@link edu.learnsphere.core.events.CourseChanged}. Methods taking an id throw {@link NotFoundException}
 * when it does not exist.
 */
public interface CourseService {

    /** Course cards matching {@code filter}, most recently opened first. */
    List<CourseSummary> list(CourseFilter filter);

    /** The card of one course. */
    CourseSummary summary(String courseId);

    /** Everything the course page shows. */
    CourseDetail get(String courseId);

    /**
     * Creates a private draft course authored in this profile.
     *
     * @throws InvalidInputException when the title is blank
     */
    Course create(CourseDraft draft);

    /**
     * Replaces the title, subtitle, description and author.
     *
     * @throws InvalidInputException when the title is blank
     * @throws ConflictException     when the course was imported (only its creator can edit it)
     */
    Course update(String courseId, CourseUpdate update);

    /** Sets the cover hue (0–359). */
    void setCoverHue(String courseId, int hue);

    /** Sets whether and how the course can be shared. */
    void setVisibility(String courseId, Visibility visibility);

    /** Hides the course from the library by default; progress is kept. */
    void archive(String courseId);

    /** Brings an archived course back. */
    void unarchive(String courseId);

    /** Deletes the course with everything in it, including progress and imported PDFs no other course uses. */
    void delete(String courseId);

    /**
     * Publishes a new version containing every approved item.
     *
     * @param notes what changed, may be empty
     * @throws InvalidInputException when the course has no approved items
     * @throws ConflictException     when the course was imported
     */
    CourseVersion publish(String courseId, String notes);

    /** Published versions, newest first. */
    List<CourseVersion> versions(String courseId);

    /** Modules in order. */
    List<CourseModule> modules(String courseId);

    /** Adds a module at the end. @throws InvalidInputException when the title is blank */
    CourseModule addModule(String courseId, String title);

    /** @throws InvalidInputException when the title is blank */
    CourseModule renameModule(String moduleId, String title);

    CourseModule setModuleSummary(String moduleId, String summary);

    /** Puts modules in the given order (ids not listed keep their relative order at the end). */
    void reorderModules(String courseId, List<String> orderedModuleIds);

    /** Deletes a module; its PDFs, concepts and items stay in the course without a module. */
    void deleteModule(String moduleId);

    /** Starts studying a course (or resumes after leaving). */
    void enrol(String courseId);

    /** Stops studying a course; progress is kept. */
    void leave(String courseId);

    /** Records that the learner opened the course (ordering of "Continue" and the sidebar). */
    void markOpened(String courseId);
}
