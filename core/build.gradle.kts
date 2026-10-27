plugins {
    `java-library`
    `java-test-fixtures`
}

description = "Domain model, database, service and SPI contracts. Depends on no other LearnSphere module."

dependencies {
    api(libs.bundles.jdbi)
    api(libs.bundles.jackson)
    implementation(libs.sqlite.jdbc)
}

// Test fixtures (edu.learnsphere.core.testing: TestDb, MutableClock, logback-test.xml) are shared with the
// other modules' tests through testImplementation(testFixtures(project(":core"))).

