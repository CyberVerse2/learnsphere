plugins {
    `java-library`
}

description = "PDF inspection, text extraction, chunking, page rendering and passage location."

dependencies {
    api(project(":core"))
    implementation(libs.pdfbox)
}
