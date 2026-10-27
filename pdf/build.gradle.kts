plugins {
    `java-library`
}

description = "PDF inspection, text extraction, chunking, page rendering and passage location."

dependencies {
    api(project(":core"))
    implementation(libs.pdfbox)
    // Decoders for scanned (JBIG2) and JPEG 2000 page images; without them those pages render blank.
    runtimeOnly(libs.pdfbox.jbig2)
    runtimeOnly(libs.jai.imageio.jpeg2000)

    testImplementation(testFixtures(project(":core")))
}
