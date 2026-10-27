import java.nio.file.Files

plugins {
    application
}

description = "The LearnSphere JavaFX desktop application."

val javafxPlatform = rootProject.extra["javafxPlatform"] as String

dependencies {
    implementation(project(":core"))
    implementation(project(":pdf"))
    implementation(project(":search"))
    implementation(project(":ai"))

    listOf(libs.javafx.base, libs.javafx.graphics, libs.javafx.controls, libs.javafx.swing, libs.javafx.media)
        .forEach { implementation(variantOf(it) { classifier(javafxPlatform) }) }
    implementation(libs.atlantafx.base)
    implementation(libs.bundles.ikonli)
    implementation(libs.commonmark)
    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":core")))
    testImplementation(testFixtures(project(":pdf")))
    testImplementation(libs.testfx.junit5)
    testImplementation(libs.hamcrest)
}

// Every JavaFX module must come from the same release; libraries may ask for other versions.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.openjfx") useVersion(libs.versions.javafx.get())
    }
}

// The version the About section shows.
tasks.processResources {
    val shownVersion = project.version.toString()
    inputs.property("version", shownVersion)
    filesMatching("edu/learnsphere/app/app.properties") {
        expand(mapOf("version" to shownVersion))
    }
}

// JVM options of every way the app runs: run, runDemo, screenshots and the packaged app (packaging.gradle.kts).
val appJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector",
    "-Dfile.encoding=UTF-8",
)
extra["appJvmArgs"] = appJvmArgs

application {
    // A launcher that does not extend Application lets JavaFX start from the class path.
    mainClass = "edu.learnsphere.app.Main"
    applicationName = "LearnSphere"
    applicationDefaultJvmArgs = appJvmArgs
}

tasks.named<JavaExec>("run") {
    description = "Runs LearnSphere against the real study assistant runtime."
}

tasks.register<JavaExec>("runDemo") {
    description = "Runs LearnSphere with demo data and the simulated study assistant in a temporary home folder."
    group = ApplicationPlugin.APPLICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    jvmArgs(appJvmArgs)
    systemProperty("learnsphere.demo", "true")
    doFirst {
        val home = Files.createTempDirectory("learnsphere-demo")
        (this as JavaExec).systemProperty("learnsphere.home", home.toString())
        logger.lifecycle("Demo home: $home")
    }
}

tasks.register<JavaExec>("screenshots") {
    description = "Renders every screen in light and dark to app/build/screenshots."
    group = ApplicationPlugin.APPLICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "edu.learnsphere.app.dev.ScreenshotTour"
    jvmArgs(appJvmArgs)
    // Renders off screen: image writing needs no desktop AWT.
    jvmArgs("-Djava.awt.headless=true")
    systemProperty("learnsphere.demo", "true")
    // -Pshots=settings,profiles renders only the shots whose names start with those words.
    systemProperty("learnsphere.shots", providers.gradleProperty("shots").getOrElse(""))
    val outputDir = layout.buildDirectory.dir("screenshots")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) })
    doFirst {
        val home = Files.createTempDirectory("learnsphere-screens")
        (this as JavaExec).systemProperty("learnsphere.home", home.toString())
    }
}

tasks.register<JavaExec>("realRun") {
    description = "Opens a new profile against the real study assistant off screen, waits until it is ready, " +
        "quits and checks no assistant process is left (timings in the output, PNGs in build/real-run)."
    group = ApplicationPlugin.APPLICATION_GROUP
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "edu.learnsphere.app.dev.RealRun"
    jvmArgs(appJvmArgs)
    jvmArgs("-Djava.awt.headless=true")
    // -Pruntime=/path/to/runtime; the project's own runtime folder otherwise.
    systemProperty("learnsphere.runtime",
        providers.gradleProperty("runtime").getOrElse(rootProject.layout.projectDirectory.dir("runtime").asFile.absolutePath))
    val outputDir = layout.buildDirectory.dir("real-run")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) })
    doFirst {
        val home = Files.createTempDirectory("learnsphere-real-run")
        (this as JavaExec).systemProperty("learnsphere.home", home.toString())
        logger.lifecycle("Real run home: $home")
    }
}

tasks.test {
    // UI tests run without a display: JavaFX's headless platform, software rendering and the Glass robot.
    systemProperty("glass.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("testfx.robot", "glass")
    jvmArgs("--add-modules=jdk.incubator.vector")
}

// Installers (jpackage, signing, notarization) live in their own script.
apply(from = "packaging.gradle.kts")
