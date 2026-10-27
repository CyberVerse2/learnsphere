plugins {
    `java-library`
}

description = "Domain model, database, service and SPI contracts. Depends on no other LearnSphere module."

dependencies {
    api(libs.bundles.jdbi)
    api(libs.bundles.jackson)
    implementation(libs.sqlite.jdbc)
}
