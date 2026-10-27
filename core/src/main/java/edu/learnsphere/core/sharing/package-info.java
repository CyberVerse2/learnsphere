/**
 * Sharing courses, progress and feedback as files: the {@code .learnsphere} course package (manifest with
 * per-entry SHA-256, optional AES-256-GCM protection), the {@code .lsprogress} learner progress file with
 * class-insight aggregation, and the {@code .lsfeedback} file of learner flags.
 *
 * <p>{@code SharingServiceImpl} is the facade the interface calls; the classes here do the work:
 * {@link edu.learnsphere.core.sharing.CourseExporter} and {@link edu.learnsphere.core.sharing.CourseImporter}
 * for packages, {@link edu.learnsphere.core.sharing.ProgressSharing} and
 * {@link edu.learnsphere.core.sharing.FeedbackSharing} for the two learner files. Every failure reaches the
 * caller as a {@link edu.learnsphere.core.error.LearnSphereException} carrying one of the
 * {@link edu.learnsphere.core.sharing.SharingMessages}.
 */
package edu.learnsphere.core.sharing;
