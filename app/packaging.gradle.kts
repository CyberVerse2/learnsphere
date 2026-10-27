// Installers for LearnSphere, applied from app/build.gradle.kts.
//
//   ./gradlew :app:packageMac       signed, notarized and stapled .dmg (macOS only)
//   ./gradlew :app:packageWindows   .msi (Windows only)
//
// Both run jpackage from the Java 25 toolchain on the jars of installDist, with the same JVM options as
// ./gradlew :app:run, and copy the runtime/ folder (llama.cpp build and models) into the app when it exists.
// Output goes to app/build/package.
//
// Mac signing needs the Developer ID Application identity in the login keychain, named with
// -Plearnsphere.signingIdentity="Developer ID Application: …", and notarization needs the
// "learnsphere-notary" keychain profile (xcrun notarytool store-credentials learnsphere-notary).
//
// Task actions only capture local values (never this script), so the tasks work with the configuration cache.

import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

@Suppress("UNCHECKED_CAST")
val sharedJvmArgs = extra["appJvmArgs"] as List<String>
val osName = System.getProperty("os.name").lowercase()

val jpackagePath: Provider<String> = extensions.getByType<JavaToolchainService>()
    .launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { launcher ->
        val exe = if (osName.contains("win")) "bin/jpackage.exe" else "bin/jpackage"
        launcher.metadata.installationPath.file(exe).asFile.absolutePath
    }

// jpackage on macOS refuses versions starting with 0.
val packageVersion: String = providers.gradleProperty("learnsphere.packageVersion").getOrElse("1.0.0")
val packageDirFile: File = layout.buildDirectory.dir("package").get().asFile
val runtimeFolder: File = rootProject.layout.projectDirectory.dir("runtime").asFile
val commonArgs: List<String> = buildList {
    addAll(listOf("--name", "LearnSphere", "--app-version", packageVersion))
    addAll(listOf("--input", layout.buildDirectory.dir("install/LearnSphere/lib").get().asFile.absolutePath))
    addAll(listOf("--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(), "--main-class", "edu.learnsphere.app.Main"))
    // Java SE plus the Vector API the search index uses.
    addAll(listOf("--add-modules", "java.se,jdk.incubator.vector,jdk.unsupported,jdk.zipfs,jdk.localedata,jdk.charsets,jdk.accessibility"))
    addAll(listOf("--dest", packageDirFile.absolutePath))
    sharedJvmArgs.forEach { addAll(listOf("--java-options", it)) }
    if (runtimeFolder.isDirectory) addAll(listOf("--app-content", runtimeFolder.absolutePath))
}

val jpackageMac = tasks.register<Exec>("jpackageMac") {
    description = "Builds the signed LearnSphere.dmg with jpackage."
    group = "distribution"
    dependsOn("installDist")
    val mac = osName.contains("mac")
    val out = packageDirFile
    val tool = jpackagePath
    val args = commonArgs + listOf(
        "--type", "dmg",
        "--mac-package-identifier", "edu.learnsphere.app",
        "--mac-entitlements", rootProject.layout.projectDirectory.file("packaging/macos/entitlements.plist").asFile.absolutePath,
    )
    val identity = providers.gradleProperty("learnsphere.signingIdentity")
    onlyIf("running on macOS") { mac }
    doFirst {
        val name = identity.orNull
            ?: throw GradleException("Set -Plearnsphere.signingIdentity=\"Developer ID Application: …\" to sign the app.")
        out.deleteRecursively()
        commandLine(listOf(tool.get()) + args + listOf("--mac-sign", "--mac-signing-key-user-name", name))
    }
}

tasks.register<Exec>("packageMac") {
    description = "Builds, signs, notarizes and staples LearnSphere.dmg into app/build/package."
    group = "distribution"
    dependsOn(jpackageMac)
    val mac = osName.contains("mac")
    val dmg = File(packageDirFile, "LearnSphere-$packageVersion.dmg").absolutePath
    onlyIf("running on macOS") { mac }
    commandLine("sh", "-c", "xcrun notarytool submit \"\$1\" --keychain-profile \"\$2\" --wait && xcrun stapler staple \"\$1\"",
        "notarize", dmg, "learnsphere-notary")
}

tasks.register<Exec>("packageWindows") {
    description = "Builds LearnSphere.msi into app/build/package."
    group = "distribution"
    dependsOn("installDist")
    val windows = osName.contains("win")
    val out = packageDirFile
    val tool = jpackagePath
    val args = commonArgs + listOf("--type", "msi", "--win-menu", "--win-shortcut", "--win-dir-chooser", "--win-per-user-install")
    onlyIf("running on Windows") { windows }
    doFirst {
        out.deleteRecursively()
        commandLine(listOf(tool.get()) + args)
    }
}
