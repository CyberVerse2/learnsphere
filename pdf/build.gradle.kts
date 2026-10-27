plugins {
    `java-library`
    `java-test-fixtures`
}

description = "PDF inspection, text extraction, chunking, page rendering and passage location."

dependencies {
    api(project(":core"))
    implementation(libs.pdfbox)
    // Decoders for scanned (JBIG2) and JPEG 2000 page images; without them those pages render blank.
    runtimeOnly(libs.pdfbox.jbig2)
    runtimeOnly(libs.jai.imageio.jpeg2000)

    // Test fixtures (edu.learnsphere.pdf.testing): TestPdf writes realistic PDFs for tests in any module,
    // through testImplementation(testFixtures(project(":pdf"))).
    testFixturesImplementation(libs.pdfbox)

    testImplementation(testFixtures(project(":core")))
}
