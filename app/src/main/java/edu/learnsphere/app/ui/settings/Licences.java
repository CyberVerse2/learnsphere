package edu.learnsphere.app.ui.settings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What LearnSphere is built with and ships, and under which licences: the bundled fonts, the software
 * libraries, and the study assistant's runtime and language models. The About section lists them in a
 * dialog; the font licences come with their full text.
 */
public final class Licences {

    /** A group of entries, e.g. "Fonts". */
    public record Group(String title, List<Entry> entries) {
    }

    /**
     * One thing that ships with the app.
     *
     * @param name     what it is called, with its version
     * @param use      what it does in the app, in a few words
     * @param licence  the licence's name
     * @param notice   the copyright line
     * @param fullText resource holding the licence's full text next to this class's fonts, or null
     */
    public record Entry(String name, String use, String licence, String notice, String fullText) {

        public Entry {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(use, "use");
            Objects.requireNonNull(licence, "licence");
            Objects.requireNonNull(notice, "notice");
        }

        /** The licence's full text, when it ships with the app. */
        public Optional<String> text() {
            if (fullText == null) {
                return Optional.empty();
            }
            try (InputStream in = Licences.class.getResourceAsStream(fullText)) {
                return in == null ? Optional.empty() : Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                return Optional.empty();
            }
        }
    }

    private static final String OFL = "SIL Open Font License 1.1";
    private static final String APACHE = "Apache License 2.0";
    private static final String GPL_CE = "GNU General Public License 2.0 with the Classpath Exception";

    /** Every group, in the order the dialog shows them. */
    public static final List<Group> ALL = List.of(
            new Group("Fonts", List.of(
                    new Entry("Inter 4.1", "Interface text", OFL, "Copyright 2016 The Inter Project Authors",
                            "/edu/learnsphere/app/theme/fonts/OFL-Inter.txt"),
                    new Entry("Newsreader", "Titles and reading text", OFL,
                            "Copyright 2020 The Newsreader Project Authors",
                            "/edu/learnsphere/app/theme/fonts/OFL-Newsreader.txt"))),
            new Group("Study assistant", List.of(
                    new Entry("llama.cpp", "Runs the study assistant on this computer", "MIT License",
                            "Copyright (c) 2023-2026 The ggml authors", null),
                    new Entry("Qwen3.5 4B and 9B", "Answers, practice questions and reading scanned pages", APACHE,
                            "Copyright (c) Alibaba Cloud, the Qwen team", null),
                    new Entry("Qwen3 Embedding 0.6B", "Finding the passages that answer a question", APACHE,
                            "Copyright (c) Alibaba Cloud, the Qwen team", null),
                    new Entry("Qwen3 Reranker 0.6B", "Choosing the passages that answer a question best", APACHE,
                            "Copyright (c) Alibaba Cloud, the Qwen team", null))),
            new Group("Software", List.of(
                    new Entry("OpenJDK 25", "The Java runtime", GPL_CE, "Copyright (c) Oracle and/or its affiliates", null),
                    new Entry("OpenJFX 27", "Windows and controls", GPL_CE, "Copyright (c) Oracle and/or its affiliates", null),
                    new Entry("AtlantaFX 3.0", "Control styles", "MIT License", "Copyright (c) 2022 mkpaz", null),
                    new Entry("Ikonli 12.4 with Feather icons", "Icons", APACHE + " (Feather icons: MIT License)",
                            "Copyright (c) Andres Almiray; Feather icons copyright (c) Cole Bemis", null),
                    new Entry("SQLite and SQLite JDBC 3.53", "Keeping your courses and progress",
                            "Public domain (SQLite) and " + APACHE + " (SQLite JDBC)",
                            "SQLite JDBC copyright (c) Taro L. Saito", null),
                    new Entry("Jdbi 3.55", "Reading and writing your data", APACHE, "Copyright (c) the Jdbi authors", null),
                    new Entry("Apache Lucene 10.5", "Searching your PDFs", APACHE,
                            "Copyright (c) The Apache Software Foundation", null),
                    new Entry("Apache PDFBox 3.0", "Reading PDFs", APACHE,
                            "Copyright (c) The Apache Software Foundation", null),
                    new Entry("JBIG2 ImageIO 3.0", "Reading scanned pages", APACHE,
                            "Copyright (c) The Apache Software Foundation", null),
                    new Entry("JAI ImageIO JPEG 2000 1.4", "Reading scanned pages",
                            "BSD 3-Clause License and the JJ2000 licence",
                            "Copyright (c) Sun Microsystems, and the JJ2000 partners", null),
                    new Entry("Jackson 2.22", "Saving settings and shared courses", APACHE,
                            "Copyright (c) FasterXML, LLC", null),
                    new Entry("commonmark-java 0.31", "Formatting answers", "BSD 2-Clause License",
                            "Copyright (c) Atlassian and others", null),
                    new Entry("SLF4J 2.0", "Keeping a diary of what the app does", "MIT License",
                            "Copyright (c) QOS.ch Sarl", null),
                    new Entry("Logback 1.6", "Keeping a diary of what the app does",
                            "Eclipse Public License 1.0 or GNU Lesser General Public License 2.1",
                            "Copyright (c) QOS.ch Sarl", null))));

    private Licences() {
    }
}
