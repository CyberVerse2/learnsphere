plugins {
    `java-library`
}

description = "Lucene index with hybrid keyword and vector search."

dependencies {
    api(project(":core"))
    implementation(libs.bundles.lucene)

    testImplementation(testFixtures(project(":core")))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--add-modules=jdk.incubator.vector")
}
