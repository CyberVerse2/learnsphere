plugins {
    application
}

description = "Model evaluation harness (not shipped): runs the real import, search and AI pipeline on generated course " +
        "PDFs and scores ingestion, retrieval, answers, generation, grading and resource use."

dependencies {
    implementation(project(":core"))
    implementation(project(":pdf"))
    implementation(project(":search"))
    implementation(project(":ai"))
    // Writes the sample course PDFs and the scanned copy.
    implementation(libs.pdfbox)
    // Captures the chat client's per-request statistics (see ChatLog) and writes the run log.
    implementation(libs.logback.classic)

    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass = "edu.learnsphere.testbench.Testbench"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
}

/** The runtime folder: -Plearnsphere.runtime, -Dlearnsphere.runtime, LEARNSPHERE_RUNTIME, else <repo>/runtime. */
val runtimeDir: String = providers.gradleProperty("learnsphere.runtime")
    .orElse(providers.systemProperty("learnsphere.runtime"))
    .orElse(providers.environmentVariable("LEARNSPHERE_RUNTIME"))
    .getOrElse(rootProject.layout.projectDirectory.dir("runtime").asFile.absolutePath)

/** Chat models to evaluate, as codes (chat-4b, chat-9b); every available one when not set. */
val models: String = providers.gradleProperty("learnsphere.testbench.models")
    .orElse(providers.systemProperty("learnsphere.testbench.models"))
    .getOrElse("")

tasks.named<JavaExec>("run") {
    description = "Evaluates the AI pipeline on generated course material and writes build/report.md."
    val work = layout.buildDirectory.dir("testbench")
    val report = layout.buildDirectory.file("report.md")
    outputs.file(report)
    // An evaluation measures this computer as it is now; never reuse an earlier report.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider { listOf(report.get().asFile.absolutePath) })
    systemProperty("learnsphere.runtime", runtimeDir)
    systemProperty("learnsphere.testbench.work", work.get().asFile.absolutePath)
    systemProperty("learnsphere.testbench.models", models)
    systemProperty("pdfbox.fontcache", work.get().dir("pdfbox-fontcache").asFile.absolutePath)
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    val fontCache = layout.buildDirectory.dir("pdfbox-fontcache").get().asFile
    systemProperty("pdfbox.fontcache", fontCache.absolutePath)
    doFirst { fontCache.mkdirs() }
}
