package edu.learnsphere.ai.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a server executable and its arguments into the command line that starts it. The default runs the
 * executable directly; tests substitute a stand-in server.
 */
@FunctionalInterface
public interface LaunchCommand {

    /** Runs {@code executable} with {@code arguments}. */
    LaunchCommand DIRECT = (role, executable, arguments) -> {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(executable.toString());
        command.addAll(arguments);
        return command;
    };

    /** The full command line for {@code role}. */
    List<String> command(ServerRole role, Path executable, List<String> arguments);
}
