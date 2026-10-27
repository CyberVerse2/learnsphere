plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Domain model, database, service and SPI contracts. Depends on no other LearnSphere module."

dependencies {
    api(libs.bundles.jdbi)
    api(libs.bundles.jackson)
    implementation(libs.sqlite.jdbc)

    // Tests of the AI pipelines run against the deterministic fakes in edu.learnsphere.ai.fake. Only the
    // test classpath sees the ai module, so core's main code still depends on no other module.
    testImplementation(project(":ai"))
}

// Test fixtures (edu.learnsphere.core.testing: TestDb, MutableClock, logback-test.xml) are shared with the
// other modules' tests through testImplementation(testFixtures(project(":core"))).

