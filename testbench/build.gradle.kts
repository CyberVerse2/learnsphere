plugins {
    application
}

description = "Model evaluation harness (not shipped): scores retrieval, citations, refusals and speed."

dependencies {
    implementation(project(":core"))
    implementation(project(":pdf"))
    implementation(project(":search"))
    implementation(project(":ai"))
    runtimeOnly(libs.logback.classic)

    testImplementation(testFixtures(project(":core")))
}

application {
    mainClass = "edu.learnsphere.testbench.Testbench"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
}

tasks.named<JavaExec>("run") {
    val report = layout.buildDirectory.file("report.md")
    outputs.file(report)
    argumentProviders.add(CommandLineArgumentProvider { listOf(report.get().asFile.absolutePath) })
    systemProperty("learnsphere.runtime", rootProject.layout.projectDirectory.dir("runtime").asFile.absolutePath)
}
