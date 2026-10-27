package edu.learnsphere.core.service;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.model.Concept;
import java.util.List;

/**
 * AI drafting of concepts and learning items. Long work runs as jobs (follow
 * {@link edu.learnsphere.core.events.JobProgress}); new drafts are announced with
 * {@link edu.learnsphere.core.events.ItemsChanged}. Drafts always land as {@code draft} with a support
 * verdict and need the creator's approval. Methods taking an id throw {@link NotFoundException} when it
 * does not exist.
 */
public interface GenerationService {

    /** Concepts of a course, or of one module when {@code moduleId} is not null. */
    List<Concept> concepts(String courseId, String moduleId);

    /**
     * Extracts the key concepts of a module from its ready PDFs, replacing AI-extracted concepts that no
     * item uses.
     *
     * @throws InvalidInputException when the module has no ready PDF
     * @throws UnavailableException  when the study assistant cannot run
     */
    JobUpdate extractConcepts(String moduleId);

    /** Adds a concept by hand. @throws InvalidInputException when the name is blank or taken in the module */
    Concept addConcept(String courseId, String moduleId, String name, String description);

    Concept updateConcept(String conceptId, String name, String description);

    /** Deletes a concept; items lose the tag. */
    void deleteConcept(String conceptId);

    /**
     * Generates draft items as a job: passages per concept, drafts, support checks, near-duplicate removal.
     *
     * @throws InvalidInputException when the scope has no ready PDF
     */
    JobUpdate generate(GenerationRequest request);

    /** Replaces a draft item with a new draft from the same passage, as a job. */
    JobUpdate regenerate(String itemId);
}
