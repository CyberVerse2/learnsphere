/**
 * The Studio, where creators turn PDFs into courses: {@link edu.learnsphere.app.ui.studio.StudioPage} lists the
 * courses this profile is writing and, for one course, shows its tabs (details, modules, PDFs, review queue,
 * scanned pages, publish and share). Each tab is a {@code StudioSection} that loads its own data, follows its
 * own events and is disposed when another tab replaces it.
 *
 * <p>File dialogs go through {@link edu.learnsphere.app.ui.studio.StudioFiles}, so tests and the screenshot tour
 * never wait on one. {@link edu.learnsphere.app.ui.studio.NewCourseDialog} creates a course from any screen.
 * The Studio's own styles are in {@code studio.css} next to these classes and use only the theme's tokens.
 */
package edu.learnsphere.app.ui.studio;
