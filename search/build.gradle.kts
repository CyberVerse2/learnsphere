plugins {
    `java-library`
}

description = "Lucene index with hybrid keyword and vector search."

dependencies {
    api(project(":core"))
    implementation(libs.bundles.lucene)

    testImplementation(testFixtures(project(":core")))
    // FakeEmbedder gives the tests realistic, deterministic vectors.
    testImplementation(project(":ai"))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}

tasks.test {
    useJUnitPlatform {
        excludeTags("benchmark")
    }
}

// Indexing and search latency over 50,000 passages: ./gradlew :search:benchmark
// Writes about 300 MB to a temporary folder (deleted afterwards) and prints a summary; the same summary is
// saved to build/reports/search-benchmark.md.
tasks.register<Test>("benchmark") {
    description = "Measures rebuild, open and hybrid search latency over 50,000 passages."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
    systemProperty("learnsphere.benchmark.report", layout.buildDirectory.file("reports/search-benchmark.md").get().asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
