package edu.learnsphere.testbench.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.pdf.PdfModule;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.search.LuceneSearchIndexFactory;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialTest {

    @TempDir
    static Path dir;

    static Material material;
    static Capabilities pdf;

    @BeforeAll
    static void generate() throws Exception {
        material = Material.generate(dir, List.of(4, 6, 9));
        pdf = PdfModule.install(Capabilities.builder()).searchIndexFactory(new LuceneSearchIndexFactory())
                .ai(FakeAi.create().capabilities()).build();
    }

    @Test
    void chaptersHaveTheirAuthoredPages() throws Exception {
        assertThat(material.chapter(Material.OS).pages()).hasSize(20);
        assertThat(material.chapter(Material.RESEARCH_METHODS).pages()).hasSize(10);
        PdfInspection os = pdf.pdfInspector().inspect(material.files().get(Material.OS));
        PdfInspection rm = pdf.pdfInspector().inspect(material.files().get(Material.RESEARCH_METHODS));
        assertThat(os.pageCount()).isEqualTo(20);
        assertThat(rm.pageCount()).isEqualTo(10);
        assertThat(os.pages()).allMatch(PdfInspection.PageInfo::hasTextLayer);
    }

    @Test
    void figuresAreImagesLargeEnoughToBeDescribed() throws Exception {
        PdfInspection os = pdf.pdfInspector().inspect(material.files().get(Material.OS));
        assertThat(os.pages().get(3).imageCoverage()).isGreaterThanOrEqualTo(0.10);
        assertThat(os.pages().get(16).imageCoverage()).isGreaterThanOrEqualTo(0.10);
        assertThat(os.pages().get(0).imageCoverage()).isZero();
    }

    @Test
    void scannedCopyHasNoTextLayer() throws Exception {
        PdfInspection scanned = pdf.pdfInspector().inspect(material.scanned());
        assertThat(scanned.pageCount()).isEqualTo(3);
        assertThat(scanned.pages()).allSatisfy(page -> {
            assertThat(page.looksScanned(25)).isTrue();
            assertThat(page.imageCoverage()).isGreaterThan(0.9);
        });
        assertThat(material.scannedReference(1)).isEqualTo(material.chapter(Material.RESEARCH_METHODS).page(4).referenceText());
    }

    @Test
    void extractedTextDropsRunningHeadersAndPageNumbers() throws Exception {
        List<PageText> pages = pdf.textExtractor().extractAll(material.files().get(Material.OS), CancellationToken.NONE);
        assertThat(pages).hasSize(20);
        assertThat(pages.get(4).text()).contains("fork()").doesNotContain("Lecture Notes, Chapter 3");
        assertThat(pages.get(12).text()).contains("Comparison of CPU scheduling algorithms").contains("Convoy effect");
    }

    @Test
    void overflowingPageNamesThePage() {
        String tooLong = "@header H\n@title T\n" + "word ".repeat(2000);
        Chapter chapter = ChapterParser.parse("long", tooLong);
        assertThatThrownBy(() -> ChapterPdf.write(chapter, dir.resolve("long.pdf")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Page 1 of long overflows");
    }
}
