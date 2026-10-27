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

    testImplementation(libs.testfx.junit5)
}

// Every JavaFX module must come from the same release; libraries may ask for other versions.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.openjfx") useVersion(libs.versions.javafx.get())
    }
}

val appJvmArgs = listOf(
    "--enable-native-access=ALL-UNNAMED",
    "--add-modules=jdk.incubator.vector",
    "-Dfile.encoding=UTF-8",
)

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
    val outputDir = layout.buildDirectory.dir("screenshots")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) })
    doFirst {
        val home = Files.createTempDirectory("learnsphere-screens")
        (this as JavaExec).systemProperty("learnsphere.home", home.toString())
    }
}
