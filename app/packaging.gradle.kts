// Installers for LearnSphere, applied from app/build.gradle.kts. packaging/README.md has the release steps.
//
//   ./gradlew :app:packageMac       LearnSphere-<version>.dmg, signed; notarized and stapled with -Plearnsphere.notarize=true
//   ./gradlew :app:packageWindows   LearnSphere-<version>.msi (Windows, with the WiX Toolset on PATH)
//
// Both build a Java 25 runtime with jlink, run jpackage on the jars of installDist with the JVM options of
// ./gradlew :app:run, and put the runtime folder (docs/ARCHITECTURE.md, "Runtime files") next to the jars as
// <app dir>/runtime, with only this platform's llama-server and its libraries. Output goes to app/build/package;
// intermediate files go to app/build/package-work.
//
// Properties, all optional:
//   -Plearnsphere.runtimeDir=<folder>      llama.cpp builds and models (default: runtime/ at the repository root)
//   -PbundleModels=false                   leave the models out, for quick local builds
//   -Plearnsphere.packageVersion=<x.y.z>   installer version (default 1.0.0; jpackage on macOS refuses 0.x)
//   -Plearnsphere.signingIdentity=<id>     name, team ID or SHA-1 of the "Developer ID Application" identity;
//                                          found automatically when the keychain has exactly one; "-" signs ad hoc
//   -Plearnsphere.notarize=true            notarize the .dmg and staple the ticket
//   -Plearnsphere.notaryProfile=<name>     notarytool keychain profile (default learnsphere-notary)
//
// The task classes below only hold serializable values, so the tasks work with the configuration cache.
// Packaging always starts from scratch: these tasks do not track state, which also keeps Gradle from
// fingerprinting gigabytes of model files.

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

// ---------------------------------------------------------------------------------------------------------
// Task types
// ---------------------------------------------------------------------------------------------------------

/** A platform an installer is built for, and which native libraries inside jars belong to it. */
enum class NativePlatform(val id: String, private val extensions: Set<String>, private val arches: Set<String>) {
    MACOS_ARM64("macos-arm64", setOf("dylib", "jnilib"), setOf("aarch64", "arm64")),
    WINDOWS_X64("windows-x64", setOf("dll"), setOf("x86_64", "amd64", "x64"));

    /** Whether the native library at [path] inside a jar is for this platform. */
    fun keeps(path: String): Boolean {
        val lower = path.lowercase()
        val extension = NATIVE_FILE.find(lower)?.groupValues?.get(1) ?: return false
        if (extension !in extensions) return false
        // Folders named after a processor (org/sqlite/native/Mac/x86_64/...) must name this one.
        return lower.split('/').dropLast(1).filter { it in KNOWN_ARCHES }.all { it in arches }
    }

    companion object {
        private val NATIVE_FILE = Regex("""\.(dylib|jnilib|dll|so)(\.\d+)*$""")
        private val KNOWN_ARCHES = setOf(
            "x86", "x86_64", "amd64", "x64", "i386", "i686", "aarch64", "arm64", "arm", "armv6", "armv7",
            "armhf", "ppc64", "ppc64le", "riscv64", "s390x", "loongarch64", "mips64",
        )

        fun isNative(path: String): Boolean = NATIVE_FILE.containsMatchIn(path.lowercase())

        fun of(id: String): NativePlatform = entries.firstOrNull { it.id == id }
            ?: throw GradleException("Unknown platform '$id'; expected one of ${entries.map { it.id }}.")
    }
}

/**
 * Copies the application jars for one platform. Native libraries for other systems and processors are
 * removed from the jars (sqlite-jdbc ships twenty). On macOS the libraries kept are thinned to arm64 and
 * signed in place with the hardened runtime and a secure timestamp: the notary service also checks code
 * inside jars, and JavaFX and SQLite extract these files and load them at run time.
 */
@DisableCachingByDefault(because = "Signing is not reproducible")
abstract class StageJars : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:Input
    abstract val platform: Property<String>

    /** A file holding the signing identity (SHA-1, or "-" for ad hoc); macOS only. */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val identityFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun stage() {
        val target = NativePlatform.of(platform.get())
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val identity = identityFile.orNull?.asFile?.readText()?.trim()
        val work = temporaryDir.also { it.deleteRecursively(); it.mkdirs() }
        jars.files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }.forEach { jar ->
            stageJar(jar, File(out, jar.name), target, identity, work)
        }
    }

    private fun stageJar(jar: File, dest: File, target: NativePlatform, identity: String?, work: File) {
        ZipFile(jar).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val natives = entries.filter { !it.isDirectory && NativePlatform.isNative(it.name) }
            if (natives.isEmpty()) {
                jar.copyTo(dest)
                return
            }
            if (entries.any { it.name.matches(Regex("""(?i)META-INF/[^/]+\.(SF|RSA|DSA|EC)""")) }) {
                throw GradleException("${jar.name} is a signed jar; changing its native libraries would break its signature.")
            }
            val removed = mutableSetOf<String>()
            val replaced = mutableMapOf<String, File>()
            for (entry in natives) {
                if (!target.keeps(entry.name)) {
                    removed += entry.name
                    continue
                }
                if (target == NativePlatform.MACOS_ARM64 && identity != null) {
                    val file = File(work, "${replaced.size}-${entry.name.substringAfterLast('/')}")
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    if (!thinToArm64(file)) {
                        removed += entry.name
                        continue
                    }
                    replaced[entry.name] = file
                }
            }
            if (replaced.isNotEmpty()) codesign(identity!!, replaced.values.toList())
            val keptFiles = entries.filter { !it.isDirectory && it.name !in removed }.map { it.name }
            ZipOutputStream(dest.outputStream().buffered()).use { out ->
                for (entry in entries) {
                    if (entry.name in removed) continue
                    // Leave out folders that only held removed libraries.
                    if (entry.isDirectory && keptFiles.none { it.startsWith(entry.name) }) continue
                    out.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    if (!entry.isDirectory) {
                        val source = replaced[entry.name]
                        (source?.inputStream() ?: zip.getInputStream(entry)).use { it.copyTo(out) }
                    }
                    out.closeEntry()
                }
            }
            val signed = if (replaced.isEmpty()) "" else ", signed ${replaced.size}"
            logger.lifecycle("${jar.name}: kept ${natives.size - removed.size} native libraries$signed, removed ${removed.size}")
        }
    }

    /** Thins a universal binary to arm64; false when it has no arm64 code at all. */
    private fun thinToArm64(file: File): Boolean {
        val archs = run("lipo", "-archs", file.absolutePath).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if ("arm64" !in archs) return false
        if (archs.size > 1) {
            val thin = File(file.parentFile, file.name + ".thin")
            run("lipo", file.absolutePath, "-thin", "arm64", "-output", thin.absolutePath)
            Files.move(thin.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    private fun codesign(identity: String, files: List<File>) {
        val timestamp = if (identity == "-") "--timestamp=none" else "--timestamp"
        run("codesign", "--force", "--options", "runtime", timestamp, "--sign", identity, *files.map { it.absolutePath }.toTypedArray())
    }

    private fun run(vararg command: String): String {
        val output = java.io.ByteArrayOutputStream()
        execOps.exec {
            commandLine(*command)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8).trim()
    }
}

/**
 * Copies what the installed app needs from the runtime folder: llama-server for one platform with its
 * libraries and licences (not the other llama.cpp tools), and the models unless [includeModels] is false.
 * Symbolic links stay links. Model files are hard-linked where the file system allows it, so staging
 * gigabytes takes neither time nor space. Model files larger than [maxFileSize] are split with
 * llama-gguf-split into parts named <name>-00001-of-0000N.gguf; llama-server loads a split model from
 * its first part.
 */
@DisableCachingByDefault(because = "Copies large local files")
abstract class StageRuntime : DefaultTask() {
    @get:Internal
    abstract val runtimeDir: DirectoryProperty

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val includeModels: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val maxFileSize: Property<Long>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun stage() {
        val root = runtimeDir.get().asFile
        val platformId = platform.get()
        val llama = File(root, "llama/$platformId")
        if (!llama.isDirectory) {
            throw GradleException(
                "No llama.cpp build at $llama. Point -Plearnsphere.runtimeDir at a runtime folder " +
                    "(packaging/README.md, \"The runtime folder\"), or fill one with the fetch-runtime script.",
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()

        val serverName = if (platformId.startsWith("windows")) "llama-server.exe" else "llama-server"
        var servers = 0
        var splitTool: Path? = null
        Files.walk(llama.toPath()).use { paths ->
            for (src in paths.sorted().toList()) {
                if (Files.isDirectory(src, LinkOption.NOFOLLOW_LINKS)) continue
                val name = src.fileName.toString()
                if (name.lowercase().removeSuffix(".exe") == "llama-gguf-split") splitTool = src
                if (!keepLlamaFile(name)) continue
                val dst = out.toPath().resolve("llama/$platformId").resolve(llama.toPath().relativize(src).toString())
                Files.createDirectories(dst.parent)
                if (Files.isSymbolicLink(src)) {
                    Files.createSymbolicLink(dst, Files.readSymbolicLink(src))
                } else {
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
                }
                if (name == serverName) servers++
            }
        }
        if (servers != 1) throw GradleException("Expected one $serverName under $llama, found $servers.")

        if (!includeModels.get()) {
            logger.lifecycle("Leaving the models out (bundleModels=false).")
            return
        }
        val models = File(root, "models")
        if (!models.isDirectory) throw GradleException("No models folder at $models.")
        val limit = maxFileSize.orNull
        Files.walk(models.toPath()).use { paths ->
            for (src in paths.filter { Files.isRegularFile(it) }.sorted().toList()) {
                val name = src.fileName.toString()
                if (name.startsWith(".") || name.endsWith(".part")) continue
                val dst = out.toPath().resolve("models").resolve(models.toPath().relativize(src).toString())
                Files.createDirectories(dst.parent)
                if (limit != null && Files.size(src) > limit && name.endsWith(".gguf")) {
                    val tool = splitTool ?: throw GradleException("$name is too large for the installer and no llama-gguf-split was found under $llama.")
                    split(tool, src, dst)
                } else {
                    linkOrCopy(src, dst)
                }
            }
        }
    }

    /** llama-server, the libraries it loads, and licence files; not the other command-line tools. */
    private fun keepLlamaFile(name: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("license")) return true
        if (lower == "llama-server" || lower == "llama-server.exe") return true
        if (!Regex("""\.(dylib|dll|so)(\.\d+)*$""").containsMatchIn(lower)) return false
        // libllama-cli-impl.dylib, llama-bench-impl.dll, ...: code of the other tools.
        val tool = Regex("""^(lib)?llama-(.+)-impl\.""").find(lower)?.groupValues?.get(2)
        return tool == null || tool == "server"
    }

    private fun linkOrCopy(src: Path, dst: Path) {
        try {
            Files.createLink(dst, src)
        } catch (e: Exception) {
            Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun split(tool: Path, src: Path, dst: Path) {
        val limit = maxFileSize.get()
        val prefix = dst.resolveSibling(dst.fileName.toString().removeSuffix(".gguf"))
        // A part can exceed the requested size by up to one tensor, so ask for half the limit.
        val partSize = "${limit / 2 / 1_000_000}M"
        logger.lifecycle("Splitting ${src.fileName} into parts of about $partSize")
        execOps.exec { commandLine(tool.toString(), "--split", "--split-max-size", partSize, src.toString(), prefix.toString()) }
        val partName = Regex(Regex.escape(prefix.fileName.toString()) + """-\d{5}-of-\d{5}\.gguf""")
        val parts = Files.list(dst.parent).use { files -> files.filter { partName.matches(it.fileName.toString()) }.sorted().toList() }
        if (parts.size < 2 || parts.any { Files.size(it) > limit }) {
            throw GradleException("Splitting ${src.fileName} did not produce parts under $limit bytes: $parts")
        }
    }
}

/**
 * Gathers the third-party notices shipped in <app dir>/legal: packaging/legal, the fonts' licences, and
 * the licence and notice files inside each library jar (libraries/<jar name>/).
 */
@DisableCachingByDefault(because = "Copying files is cheaper than caching them")
abstract class CollectNotices : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val legalDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val fontLicences: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun collect() {
        val out = outputDir.get().asFile
        fs.sync {
            from(legalDir)
            into(out)
        }
        fs.copy {
            from(fontLicences)
            into(File(out, "fonts"))
        }
        val licence = Regex("""(?i)^META-INF/(.*/)?(LICEN[CS]E|NOTICE|COPYING)[^/]*$""")
        for (jar in jars.files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }) {
            ZipFile(jar).use { zip ->
                val files = zip.entries().asSequence().filter { !it.isDirectory && licence.matches(it.name) }.toList()
                if (files.isEmpty()) return@use
                val dir = File(out, "libraries/${jar.name.removeSuffix(".jar")}").also { it.mkdirs() }
                for (entry in files) {
                    var target = File(dir, entry.name.substringAfterLast('/'))
                    var n = 2
                    while (target.exists()) target = File(dir, "${entry.name.substringAfterLast('/')}-${n++}")
                    zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}

/**
 * Builds LearnSphere.app: jpackage makes the app image, the staged runtime files go into
 * Contents/app/runtime (cloned on APFS, so the models take no extra space), packaging/macos/info-plist.sh
 * adjusts Info.plist, then packaging/macos/sign-app.sh signs everything inside it.
 */
@DisableCachingByDefault(because = "Signing is not reproducible")
abstract class MacApp : DefaultTask() {
    @get:Input
    abstract val jpackage: Property<String>

    @get:Input
    abstract val jpackageArgs: ListProperty<String>

    @get:Internal
    abstract val runtimeFiles: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val identityFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val infoPlistScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val entitlements: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        execOps.exec { commandLine(listOf(jpackage.get(), "--type", "app-image", "--dest", out.absolutePath) + jpackageArgs.get()) }
        val app = File(out, "LearnSphere.app")
        val runtime = File(app, "Contents/app/runtime")
        val source = runtimeFiles.get().asFile.absolutePath
        val cloned = execOps.exec {
            commandLine("cp", "-c", "-R", source, runtime.absolutePath)
            isIgnoreExitValue = true
        }
        if (cloned.exitValue != 0) {
            // Not APFS, or another volume: copy instead of cloning.
            runtime.deleteRecursively()
            execOps.exec { commandLine("cp", "-R", source, runtime.absolutePath) }
        }
        execOps.exec { commandLine("sh", infoPlistScript.get().asFile.absolutePath, app.absolutePath) }
        val identity = identityFile.get().asFile.readText().trim()
        execOps.exec {
            commandLine("sh", signScript.get().asFile.absolutePath, identity, entitlements.get().asFile.absolutePath, app.absolutePath)
        }
    }
}

/**
 * Builds the .msi with jpackage and WiX. When the payload is too large for one cabinet inside the .msi
 * (the models), packaging/windows/main.wxs keeps the cabinets as files next to it; they are copied from
 * jpackage's work folder to the output, and the .msi installs only with them beside it.
 */
@DisableCachingByDefault(because = "Builds an installer from scratch")
abstract class WindowsMsi : DefaultTask() {
    @get:Input
    abstract val jpackage: Property<String>

    @get:Input
    abstract val jpackageArgs: ListProperty<String>

    @get:Input
    abstract val externalCabinets: Property<Boolean>

    @get:Internal
    abstract val jpackageTemp: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        val temp = jpackageTemp.get().asFile
        out.deleteRecursively()
        temp.deleteRecursively()
        execOps.exec {
            commandLine(listOf(jpackage.get(), "--type", "msi", "--dest", out.absolutePath, "--temp", temp.absolutePath) + jpackageArgs.get())
        }
        if (externalCabinets.get()) {
            val cabinets = temp.walkTopDown().filter { it.isFile && it.extension.equals("cab", ignoreCase = true) }.toList()
            if (cabinets.isEmpty()) throw GradleException("jpackage left no cabinet files in $temp; the .msi cannot install without them.")
            cabinets.forEach { it.copyTo(File(out, it.name), overwrite = true) }
        }
        temp.deleteRecursively()
        out.listFiles()?.sortedBy { it.name }?.forEach { logger.lifecycle("${it.name}  ${it.length() / (1024 * 1024)} MB") }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Configuration. Lambdas that run at execution time (onlyIf, doFirst, doLast) only read local values copied
// from these script-level ones: a reference to the script itself cannot go into the configuration cache.
// ---------------------------------------------------------------------------------------------------------

@Suppress("UNCHECKED_CAST")
val sharedJvmArgs = extra["appJvmArgs"] as List<String>
val osName = System.getProperty("os.name").lowercase()
val onMac = osName.contains("mac")
val onWindows = osName.contains("win")

val packageVersion: String = providers.gradleProperty("learnsphere.packageVersion").getOrElse("1.0.0")
val bundleModels: Boolean = providers.gradleProperty("bundleModels").map { it.toBoolean() }.getOrElse(true)
val notarize: Boolean = providers.gradleProperty("learnsphere.notarize").map { it.toBoolean() }.getOrElse(false)
val notaryProfile: String = providers.gradleProperty("learnsphere.notaryProfile").getOrElse("learnsphere-notary")
val requestedIdentity: String = providers.gradleProperty("learnsphere.signingIdentity").getOrElse("")
val runtimeFolder: File = providers.gradleProperty("learnsphere.runtimeDir")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("runtime"))

val packagingDir: File = rootProject.file("packaging")
val packageDir: File = layout.buildDirectory.dir("package").get().asFile
val workDir: File = layout.buildDirectory.dir("package-work").get().asFile

val toolchainHome: Provider<File> = extensions.getByType<JavaToolchainService>()
    .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { it.metadata.installationPath.asFile }
val exeSuffix = if (onWindows) ".exe" else ""
val jpackageTool: Provider<String> = toolchainHome.map { File(it, "bin/jpackage$exeSuffix").absolutePath }
val jlinkTool: Provider<String> = toolchainHome.map { File(it, "bin/jlink$exeSuffix").absolutePath }

// Java SE plus what the libraries need beyond it (jdeps --print-module-deps): the Vector API for Lucene,
// jdk.unsupported(.desktop) for JavaFX and its Swing bridge, JFR events, zip file systems, every locale
// and charset, and the accessibility bridge for screen readers.
val runtimeModules = listOf(
    "java.se", "jdk.incubator.vector", "jdk.unsupported", "jdk.unsupported.desktop", "jdk.jfr", "jdk.zipfs",
    "jdk.localedata", "jdk.charsets", "jdk.accessibility",
).joinToString(",")

val appJars: ConfigurableFileTree = fileTree(layout.buildDirectory.dir("install/LearnSphere/lib")) {
    include("*.jar")
    builtBy("installDist")
}
val mainJarName: String = tasks.named<Jar>("jar").get().archiveFileName.get()

/** jpackage options shared by both installers, without --type, --dest and the platform's own. */
fun commonJpackageArgs(input: File, jre: File): List<String> = buildList {
    addAll(listOf("--name", "LearnSphere", "--app-version", packageVersion, "--vendor", "LearnSphere"))
    addAll(listOf("--copyright", "© 2026 LearnSphere"))
    addAll(listOf("--description", "Study your own PDFs with answers that cite the page, quizzes and flashcards."))
    addAll(listOf("--input", input.absolutePath, "--main-jar", mainJarName, "--main-class", "edu.learnsphere.app.Main"))
    addAll(listOf("--runtime-image", jre.absolutePath))
    sharedJvmArgs.forEach { addAll(listOf("--java-options", it)) }
}

fun jlinkTask(name: String, label: String, out: File, enabled: Boolean) = tasks.register<Exec>(name) {
    description = "Builds the Java runtime bundled in the $label installer."
    group = "distribution"
    val tool = jlinkTool
    val modules = runtimeModules
    outputs.dir(out)
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on $label") { enabled }
    doFirst {
        out.deleteRecursively()
        commandLine(
            tool.get(), "--add-modules", modules, "--output", out.absolutePath,
            "--strip-debug", "--no-header-files", "--no-man-pages", "--strip-native-commands",
        )
    }
}

val collectNotices = tasks.register<CollectNotices>("collectNotices") {
    description = "Gathers the third-party licence notices shipped with the installers."
    group = "distribution"
    jars.from(appJars)
    legalDir.set(File(packagingDir, "legal"))
    fontLicences.from(fileTree("src/main/resources/edu/learnsphere/app/theme/fonts") { include("OFL-*.txt") })
    outputDir.set(File(workDir, "legal"))
}

// ---------------------------------------------------------------------------------------------------------
// macOS: signed .dmg
// ---------------------------------------------------------------------------------------------------------

val macWork = File(workDir, "macos")
val identityTxt = File(macWork, "identity.txt")
val dmgFile = File(packageDir, "LearnSphere-$packageVersion.dmg")

val macIdentity = tasks.register<Exec>("macSigningIdentity") {
    description = "Finds the Developer ID Application identity that signs the macOS app."
    group = "distribution"
    val mac = onMac
    outputs.file(identityTxt)
    doNotTrackState("The keychain can change between builds")
    onlyIf("running on macOS") { mac }
    commandLine("sh", File(packagingDir, "macos/identity.sh").absolutePath, identityTxt.absolutePath, requestedIdentity)
}

val macJars = tasks.register<StageJars>("stageMacJars") {
    description = "Copies the jars for macOS with other platforms' native libraries removed and the rest signed."
    group = "distribution"
    dependsOn(macIdentity)
    val mac = onMac
    jars.from(appJars)
    platform = NativePlatform.MACOS_ARM64.id
    identityFile.set(identityTxt)
    outputDir.set(File(macWork, "jars"))
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on macOS") { mac }
}

val macInput = tasks.register<Sync>("stageMacInput") {
    description = "Assembles the files jpackage puts next to the jars in the macOS app."
    group = "distribution"
    val mac = onMac
    from(macJars)
    into("legal") { from(collectNotices) }
    into(File(macWork, "input"))
    onlyIf("running on macOS") { mac }
}

val macRuntimeFiles = tasks.register<StageRuntime>("stageMacRuntime") {
    description = "Stages llama-server for macOS and the models from the runtime folder."
    group = "distribution"
    val mac = onMac
    runtimeDir.set(runtimeFolder)
    platform = NativePlatform.MACOS_ARM64.id
    includeModels = bundleModels
    outputDir.set(File(macWork, "runtime"))
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on macOS") { mac }
}

val macJre = jlinkTask("macRuntimeImage", "macOS", File(macWork, "jre"), onMac)

val macApp = tasks.register<MacApp>("macApp") {
    description = "Builds and signs LearnSphere.app."
    group = "distribution"
    dependsOn(macInput, macJre, macRuntimeFiles, macIdentity)
    val mac = onMac
    jpackage = jpackageTool
    jpackageArgs = commonJpackageArgs(File(macWork, "input"), File(macWork, "jre")) + listOf(
        "--icon", File(packagingDir, "macos/LearnSphere.icns").absolutePath,
        "--mac-package-identifier", "com.learnsphere.app",
        "--mac-package-name", "LearnSphere",
        "--mac-app-category", "education",
    )
    runtimeFiles.set(File(macWork, "runtime"))
    identityFile.set(identityTxt)
    infoPlistScript.set(File(packagingDir, "macos/info-plist.sh"))
    signScript.set(File(packagingDir, "macos/sign-app.sh"))
    entitlements.set(File(packagingDir, "macos/entitlements.plist"))
    outputDir.set(File(macWork, "app"))
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on macOS") { mac }
}

val macDmg = tasks.register<Exec>("macDmg") {
    description = "Builds and signs the LearnSphere disk image from the signed app."
    group = "distribution"
    dependsOn(macApp)
    val mac = onMac
    val app = File(macWork, "app/LearnSphere.app")
    val dmg = dmgFile
    val identity = identityTxt
    val script = File(packagingDir, "macos/make-dmg.sh")
    val volumeIcon = File(packagingDir, "macos/LearnSphere.icns")
    outputs.file(dmg)
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on macOS") { mac }
    doFirst {
        dmg.parentFile.deleteRecursively()
        dmg.parentFile.mkdirs()
        commandLine("sh", script.absolutePath, app.absolutePath, dmg.absolutePath, identity.readText().trim(), volumeIcon.absolutePath)
    }
}

val notarizeMac = tasks.register<Exec>("notarizeMac") {
    description = "Notarizes the disk image with Apple and staples the ticket to it."
    group = "distribution"
    dependsOn(macDmg)
    val mac = onMac
    val enabled = notarize
    onlyIf("running on macOS") { mac }
    onlyIf("-Plearnsphere.notarize=true") { enabled }
    commandLine("sh", File(packagingDir, "macos/notarize.sh").absolutePath, notaryProfile, dmgFile.absolutePath)
}

val verifyMac = tasks.register<Exec>("verifyMac") {
    description = "Checks the signatures of the disk image and the app, and Gatekeeper's verdict once notarized."
    group = "distribution"
    dependsOn(macDmg)
    mustRunAfter(notarizeMac)
    val mac = onMac
    onlyIf("running on macOS") { mac }
    commandLine("sh", File(packagingDir, "macos/verify.sh").absolutePath, dmgFile.absolutePath, notarize.toString())
}

tasks.register("packageMac") {
    description = "Builds LearnSphere.dmg into app/build/package: signed, and notarized with -Plearnsphere.notarize=true."
    group = "distribution"
    dependsOn(verifyMac)
    if (notarize) dependsOn(notarizeMac)
    val mac = onMac
    val notarized = notarize
    val dmg = dmgFile
    onlyIf("running on macOS") { mac }
    doLast {
        logger.lifecycle("Built $dmg")
        if (!notarized) logger.lifecycle("Signed, not notarized: release builds pass -Plearnsphere.notarize=true.")
    }
}

// ---------------------------------------------------------------------------------------------------------
// Windows: .msi. The staging tasks run on any system (with -Pjavafx.platform=win for the Windows jars), so
// they can be checked away from Windows; packageWindows itself needs Windows and WiX.
// ---------------------------------------------------------------------------------------------------------

val windowsWork = File(workDir, "windows")

val windowsJars = tasks.register<StageJars>("stageWindowsJars") {
    description = "Copies the jars for Windows with other platforms' native libraries removed."
    group = "distribution"
    jars.from(appJars)
    platform = NativePlatform.WINDOWS_X64.id
    outputDir.set(File(windowsWork, "jars"))
    doNotTrackState("Packaging always starts from scratch")
}

val windowsRuntimeFiles = tasks.register<StageRuntime>("stageWindowsRuntime") {
    description = "Stages llama-server for Windows and the models from the runtime folder."
    group = "distribution"
    runtimeDir.set(runtimeFolder)
    platform = NativePlatform.WINDOWS_X64.id
    includeModels = bundleModels
    // Windows Installer cannot hold a file of 2 GiB or more.
    maxFileSize = 2_000_000_000L
    outputDir.set(File(windowsWork, "runtime"))
    doNotTrackState("Packaging always starts from scratch")
}

val windowsInput = tasks.register<Sync>("stageWindowsInput") {
    description = "Assembles the files jpackage puts next to the jars in the Windows app."
    group = "distribution"
    from(windowsJars)
    into("legal") { from(collectNotices) }
    into("runtime") { from(windowsRuntimeFiles) }
    into(File(windowsWork, "input"))
    doNotTrackState("Packaging always starts from scratch")
}

val windowsResources = tasks.register<Sync>("stageWindowsResources") {
    description = "Prepares the WiX sources that replace jpackage's defaults."
    group = "distribution"
    val dir = File(windowsWork, "resources")
    val external = bundleModels
    from(File(packagingDir, "windows/main.wxs"))
    into(dir)
    doNotTrackState("Packaging always starts from scratch")
    doLast {
        // Embedded cabinets cannot hold the models (an .msi stays under 2 GB): keep them beside the .msi.
        val define = if (external) "  <?define LsExternalCabinets = \"yes\" ?>\n" else ""
        File(dir, "overrides.wxi").writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<Include xmlns=\"http://schemas.microsoft.com/wix/2006/wi\">\n$define</Include>\n",
        )
    }
}

val windowsJre = jlinkTask("windowsRuntimeImage", "Windows", File(windowsWork, "jre"), onWindows)

tasks.register<WindowsMsi>("packageWindows") {
    description = "Builds LearnSphere.msi into app/build/package (Windows, with the WiX Toolset on PATH)."
    group = "distribution"
    val windows = onWindows
    // Away from Windows the task is skipped, and so is everything it would stage.
    if (windows) dependsOn(windowsJre, windowsInput, windowsResources)
    jpackage = jpackageTool
    jpackageArgs = commonJpackageArgs(File(windowsWork, "input"), File(windowsWork, "jre")) + listOf(
        "--icon", File(packagingDir, "windows/LearnSphere.ico").absolutePath,
        "--resource-dir", File(windowsWork, "resources").absolutePath,
        "--win-per-user-install",
        "--win-dir-chooser",
        "--win-menu",
        "--win-menu-group", "LearnSphere",
        // The same for every release, so installing a new version replaces the old one.
        "--win-upgrade-uuid", "3d4126a7-37c1-46b9-9edd-9a9018821db0",
    )
    externalCabinets = bundleModels
    jpackageTemp.set(File(windowsWork, "jpackage"))
    outputDir.set(packageDir)
    doNotTrackState("Packaging always starts from scratch")
    onlyIf("running on Windows") { windows }
}
