/**
 * The main window and navigation: {@link edu.learnsphere.app.ui.MainWindow} (sidebar plus content area),
 * {@link edu.learnsphere.app.ui.Navigator} and {@link edu.learnsphere.app.ui.Route}, and the threading
 * helpers {@link edu.learnsphere.app.ui.Fx} and {@link edu.learnsphere.app.ui.UiExecutors}.
 *
 * <p>Each screen lives in {@code edu.learnsphere.app.ui.<feature>} as a {@code View} (a {@code Region}
 * built in code) plus a {@code ViewModel} (JavaFX properties; calls services on
 * {@link edu.learnsphere.app.ui.UiExecutors#background()} and applies results with
 * {@link edu.learnsphere.app.ui.Fx#run(Runnable)}), and is registered with the navigator in
 * {@link edu.learnsphere.app.ui.MainWindow}.
 */
package edu.learnsphere.app.ui;
