package edu.learnsphere.ai.runtime;

import java.util.Locale;
import java.util.Optional;

/**
 * The operating system and processor combinations llama.cpp builds are bundled for. Each names the folder
 * under {@code runtime/llama/} that holds its build and the server executable's file name.
 */
public enum Platform {

    /** macOS on Apple Silicon (the shipped Mac build). */
    MACOS_ARM64("macos-arm64", "llama-server"),
    /** Windows on x64 (the shipped Windows build). */
    WINDOWS_X64("windows-x64", "llama-server.exe"),
    /** Linux on x64 (development only). */
    LINUX_X64("linux-x64", "llama-server"),
    /** Linux on ARM64 (development only). */
    LINUX_ARM64("linux-arm64", "llama-server");

    private final String folder;
    private final String serverExecutable;

    Platform(String folder, String serverExecutable) {
        this.folder = folder;
        this.serverExecutable = serverExecutable;
    }

    /** The folder under {@code runtime/llama/} holding this platform's llama.cpp build. */
    public String folder() {
        return folder;
    }

    /** File name of the server executable. */
    public String serverExecutable() {
        return serverExecutable;
    }

    /** The platform of the running JVM, or empty when no llama.cpp build exists for it. */
    public static Optional<Platform> current() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * The platform for an {@code os.name}/{@code os.arch} pair, or empty when none fits (for example
     * Windows on ARM or an Intel Mac).
     */
    public static Optional<Platform> detect(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64");
        if (os.contains("mac") || os.contains("darwin")) {
            return arm64 ? Optional.of(MACOS_ARM64) : Optional.empty();
        }
        if (os.contains("win")) {
            return x64 ? Optional.of(WINDOWS_X64) : Optional.empty();
        }
        if (os.contains("linux")) {
            return x64 ? Optional.of(LINUX_X64) : arm64 ? Optional.of(LINUX_ARM64) : Optional.empty();
        }
        return Optional.empty();
    }
}
