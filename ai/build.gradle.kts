plugins {
    `java-library`
}

description = "llama-server lifecycle, model clients, prompts and deterministic fakes."

dependencies {
    api(project(":core"))
}

// Real-model tests: ./gradlew :ai:integrationTest (skipped when the runtime folder is missing).
val integrationTest: SourceSet = sourceSets.create("integrationTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[integrationTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[integrationTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val runtimeDir: File = providers.gradleProperty("learnsphere.runtime")
    .orElse(providers.environmentVariable("LEARNSPHERE_RUNTIME"))
    .map { file(it) }
    .getOrElse(rootProject.layout.projectDirectory.dir("runtime").asFile)

tasks.register<Test>("integrationTest") {
    description = "Runs tests against the real llama-server and model files in the runtime folder."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
    val runtimePath = runtimeDir.absolutePath
    systemProperty("learnsphere.runtime", runtimePath)
    inputs.property("runtimePath", runtimePath)
    onlyIf("the runtime folder exists") { File(runtimePath).isDirectory }
    maxHeapSize = "2g"
}
