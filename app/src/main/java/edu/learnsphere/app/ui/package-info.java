/**
 * The main window and navigation: {@link edu.learnsphere.app.ui.MainWindow} (sidebar plus content area),
 * {@link edu.learnsphere.app.ui.Navigator} with its {@link edu.learnsphere.app.ui.Destination}s and
 * {@link edu.learnsphere.app.ui.Route}s, and the screen contract: {@link edu.learnsphere.app.ui.View},
 * {@link edu.learnsphere.app.ui.UiTasks} for service calls, {@link edu.learnsphere.app.ui.Fx#subscribe} for
 * events and {@link edu.learnsphere.app.ui.Disposer} to release both.
 *
 * <p>Each screen lives in {@code edu.learnsphere.app.ui.<feature>} as a {@code View} built in code, reads the
 * open workspace from {@link edu.learnsphere.app.AppContext#workspaceProperty()}, and is registered with the
 * navigator in {@link edu.learnsphere.app.ui.MainWindow}.
 */
package edu.learnsphere.app.ui;
