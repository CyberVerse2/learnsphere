import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    base
}

/**
 * JavaFX ships native code per platform. The classifier is detected from the machine running the
 * build and can be overridden with -Pjavafx.platform=<mac-aarch64|mac|win|linux|linux-aarch64>.
 */
val javafxPlatform: String = providers.gradleProperty("javafx.platform").orNull ?: run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}
extra["javafxPlatform"] = javafxPlatform

/**
 * The folders a test JVM gets as system properties:
 *
 * - `learnsphere.home`: an app home, so tests never touch the real one (individual tests use their own temp
 *   folders).
 * - `pdfbox.fontcache`: where PDFBox keeps its list of the computer's fonts (by default in the user's home): the
 *   module's build folder, so tests never write outside the project and build the list only once.
 *
 * The paths are @Internal: they differ between checkouts, and as inputs they would give every checkout its own
 * build cache key, so no checkout could reuse another's test results.
 */
class TestFolders(
    @get:Internal val appHome: File,
    @get:Internal val pdfFontCache: Provider<Directory>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf(
        "-Dlearnsphere.home=${appHome.absolutePath}",
        "-Dpdfbox.fontcache=${pdfFontCache.get().asFile.absolutePath}",
    )
}

allprojects {
    group = "edu.learnsphere"
    version = "0.1.0"
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        // Mockito attaches itself as a Java agent; loading it explicitly avoids the dynamic-agent warning.
        val mockitoAgent = configurations.create("mockitoAgent") { isTransitive = false }

        dependencies {
            "implementation"(rootProject.libs.slf4j.api)
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.assertj.core)
            "testImplementation"(rootProject.libs.mockito.core)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
            "testRuntimeOnly"(rootProject.libs.logback.classic)
            "mockitoAgent"(rootProject.libs.mockito.core)
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release = 25
            options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing,-this-escape,-classfile", "-parameters"))
        }

        tasks.withType<Javadoc>().configureEach {
            options.encoding = "UTF-8"
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            maxHeapSize = "1g"
            val fontCache = layout.buildDirectory.dir("pdfbox-fontcache")
            jvmArgumentProviders.add(TestFolders(temporaryDir.resolve("home"), fontCache))
            doFirst { fontCache.get().asFile.mkdirs() }
            jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xshare:off")
            val agentJar = files(mockitoAgent)
            jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-javaagent:${agentJar.singleFile}") })
            testLogging {
                events(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                exceptionFormat = TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
        }

        tasks.withType<AbstractArchiveTask>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            dirPermissions { unix("rwxr-xr-x") }
            filePermissions { unix("rw-r--r--") }
        }
    }
}
