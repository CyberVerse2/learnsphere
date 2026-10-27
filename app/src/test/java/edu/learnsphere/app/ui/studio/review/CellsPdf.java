package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A twelve-page cell biology PDF, one topic per page, written like real course notes. Five topics repeat their
 * key word so the study assistant's fakes find them as the module's key concepts and draft distinct questions
 * about each (the filler prose of {@code RealWorkspaces.textPdf} makes near-identical drafts).
 */
final class CellsPdf {

    /** One page: its heading and paragraph. */
    record Topic(String heading, String text) {
    }

    static final List<Topic> TOPICS = List.of(
            new Topic("The plasma membrane",
                    "A membrane of phospholipids surrounds every living thing made of protoplasm. The membrane is a "
                            + "bilayer: water-loving heads face outwards while fatty tails hide inside the membrane. "
                            + "Proteins embedded in the membrane carry substances across and receive signals. Because the "
                            + "membrane lets some molecules through and blocks others, biologists call the membrane "
                            + "selectively permeable."),
            new Topic("The nucleus",
                    "The nucleus stores the genetic instructions inside a double envelope pierced by pores. Messenger "
                            + "molecules leave the nucleus through these pores carrying recipes for proteins. A dense "
                            + "region inside the nucleus, the nucleolus, assembles ribosome parts. Most eukaryotic types "
                            + "keep one nucleus, although muscle fibres hold many and red blood corpuscles lose the "
                            + "nucleus as they mature."),
            new Topic("Mitochondria",
                    "Mitochondria release energy from glucose through aerobic respiration and store it as ATP. The "
                            + "inner surface of mitochondria folds into cristae, which give respiratory enzymes more room. "
                            + "Mitochondria carry their own small circular genome inherited from the mother. Tissues that "
                            + "work hard, such as heart muscle, are packed with mitochondria because they burn so much "
                            + "fuel."),
            new Topic("Ribosomes",
                    "Ribosomes translate messenger instructions into chains of amino acids. Some ribosomes float freely "
                            + "in the cytoplasm while other ribosomes stud the rough reticulum. Ribosomes are built from two "
                            + "subunits of protein and ribosomal nucleic acid. Bacteria have smaller ribosomes, which is why "
                            + "some antibiotics can block bacterial ribosomes without harming ours."),
            new Topic("Endoplasmic reticulum",
                    "Folded sheets of reticulum run through the cytoplasm of eukaryotes. Rough regions modify new "
                            + "proteins, while smooth regions make lipids and detoxify drugs. Vesicles bud from its edges and "
                            + "travel onwards for sorting. Liver tissue has an especially large smooth reticulum because it "
                            + "breaks down so many poisons."),
            new Topic("The Golgi apparatus",
                    "The Golgi apparatus receives vesicles, sorts their contents and attaches sugar tags. Finished "
                            + "products leave the Golgi in new vesicles addressed to the surface or to lysosomes. Secretory "
                            + "tissues such as the pancreas have especially large Golgi stacks. Each Golgi stack has a "
                            + "receiving face and a shipping face, so cargo always moves through the Golgi in one "
                            + "direction."),
            new Topic("Lysosomes",
                    "Lysosomes contain digestive enzymes that break down worn-out organelles and captured bacteria. "
                            + "Their acidic interior keeps these enzymes active, and a lipid wall keeps them away from the "
                            + "cytoplasm. Faulty lysosomes cause storage diseases in which waste builds up over years."),
            new Topic("The cytoskeleton",
                    "Microtubules, actin filaments and intermediate filaments together form the cytoskeleton. These "
                            + "fibres give tissues their shape and act as tracks for motor proteins. During division, "
                            + "microtubules build the spindle that pulls the copied genetic material apart."),
            new Topic("Chromosomes",
                    "Chromosomes are long molecules of DNA wound around proteins called histones. Humans carry "
                            + "forty-six chromosomes arranged in twenty-three pairs. Before division the chromosomes are "
                            + "copied, and each copy stays joined to its twin at the centromere. Chromosomes condense into "
                            + "visible rods only while they are being separated."),
            new Topic("Mitosis",
                    "Mitosis separates copied genetic material into two identical nuclei in four stages. Prophase "
                            + "condenses the strands, metaphase lines them up and anaphase pulls the sister chromatids "
                            + "apart. Telophase rebuilds an envelope around each set before the cytoplasm divides."),
            new Topic("Meiosis",
                    "Meiosis halves the number of strands to make sperm and egg gametes. Crossing over between paired "
                            + "strands shuffles alleles, so every gamete is genetically different. Two rounds of division "
                            + "produce four gametes from one parent."),
            new Topic("Diffusion and osmosis",
                    "Diffusion moves particles from where they are concentrated to where they are scarce, without using "
                            + "energy. Osmosis is the diffusion of water across a partially permeable barrier. Plant tissues "
                            + "swell with water until their walls push back."));

    private CellsPdf() {
    }

    /** Writes the PDF to {@code dir/name}. */
    static Path write(Path dir, String name) {
        TestPdf.Builder pdf = TestPdf.builder().pageNumbers();
        for (int i = 0; i < TOPICS.size(); i++) {
            if (i > 0) {
                pdf.pageBreak();
            }
            pdf.heading(TOPICS.get(i).heading()).paragraph(TOPICS.get(i).text());
        }
        try {
            return pdf.write(dir.resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
