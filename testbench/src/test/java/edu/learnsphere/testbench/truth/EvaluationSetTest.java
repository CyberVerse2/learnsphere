package edu.learnsphere.testbench.truth;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.pdf.PdfModule;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.search.LuceneSearchIndexFactory;
import edu.learnsphere.testbench.material.Material;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationSetTest {

    private final EvaluationSet set = EvaluationSet.load();

    @Test
    void hasTheAgreedSize() {
        assertThat(set.covered()).hasSize(30);
        assertThat(set.uncovered()).hasSize(10);
        assertThat(set.shortAnswers()).hasSize(5);
        assertThat(set.scanned()).hasSize(3);
        assertThat(set.responseCount()).isEqualTo(15);
    }

    @Test
    void everyShortAnswerHasACorrectAPartialAndAWrongResponse() {
        assertThat(set.shortAnswers()).allSatisfy(question -> assertThat(question.responses())
                .extracting(EvaluationSet.Response::expected)
                .containsExactlyInAnyOrder(GradeResult.Verdict.CORRECT, GradeResult.Verdict.PARTIAL,
                        GradeResult.Verdict.INCORRECT));
    }

    @Test
    void groundTruthMatchesTheGeneratedMaterial(@TempDir Path dir) throws Exception {
        Material material = Material.generate(dir, set.scan().pages());
        Capabilities capabilities = PdfModule.install(Capabilities.builder())
                .searchIndexFactory(new LuceneSearchIndexFactory()).ai(FakeAi.create().capabilities()).build();
        List<String> problems = set.validate(material, capabilities.textExtractor());
        assertThat(problems).isEmpty();
    }
}
