package edu.learnsphere.app.theme;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the app's fonts and assigns a font face to every text role.
 *
 * <p>The design uses Inter for interface text and Newsreader for display, titles, reading text and big
 * numbers. Static TTF instances of both (SIL Open Font License) are loaded from
 * {@code /edu/learnsphere/app/theme/fonts/} (file names in {@link Face}). When a file is missing, the face
 * falls back to the first installed font in its fallback list.
 *
 * <p>Faces are resolved to an exact font, not just a family: JavaFX's family-plus-weight lookup picks the
 * wrong face for some installed families (for example a thin or bold face for "normal"), so each candidate
 * is checked by the font name JavaFX actually returns. The result reaches the UI through
 * {@link #stylesheet()}, a generated stylesheet that sets family, weight and style for each face's
 * selectors. {@code learnsphere.css} therefore sets sizes and colours but never font families or weights;
 * give a node one of the face style classes ({@code .medium}, {@code .semibold}, {@code .bold},
 * {@code .serif}, {@code .serif-medium}, {@code .serif-italic}) or a text role class to change its face.
 */
public final class Typography {

    /** A font that may be installed on this computer: family, weight and style, and the face it must resolve to. */
    record Candidate(String family, FontWeight weight, FontPosture posture, String expectedName) {
    }

    /** A face resolved to something JavaFX renders exactly. */
    public record Resolved(String family, FontWeight weight, FontPosture posture, String fontName, boolean bundled) {
    }

    /** The faces of the type system, each with its bundled file, CSS selectors and installed fallbacks. */
    public enum Face {
        SANS_REGULAR("Inter-Regular.ttf", List.of(".root"),
                c("Segoe UI", FontWeight.NORMAL, "Segoe UI"),
                c("System", FontWeight.NORMAL, "System Regular")),
        SANS_MEDIUM("Inter-Medium.ttf",
                List.of(".medium", ".text-caption", ".text-label", ".button", ".chip", ".nav-item", ".hyperlink"),
                c("Segoe UI Semibold", FontWeight.NORMAL, "Segoe UI Semibold"),
                c("System", FontWeight.NORMAL, "System Regular")),
        SANS_SEMIBOLD("Inter-SemiBold.ttf",
                List.of(".semibold", ".text-heading", ".text-overline", ".profile-chip-name", ".avatar-initials"),
                c("Segoe UI Semibold", FontWeight.NORMAL, "Segoe UI Semibold"),
                c("System", FontWeight.BOLD, "System Bold")),
        SANS_BOLD("Inter-Bold.ttf", List.of(".bold"),
                c("Segoe UI", FontWeight.BOLD, "Segoe UI Bold"),
                c("System", FontWeight.BOLD, "System Bold")),
        SERIF_REGULAR("Newsreader-Regular.ttf", List.of(".serif", ".text-reading"),
                c("PT Serif", FontWeight.NORMAL, "PT Serif"),
                c("Georgia", FontWeight.NORMAL, "Georgia"),
                c("Cambria", FontWeight.NORMAL, "Cambria"),
                c("Times New Roman", FontWeight.NORMAL, "Times New Roman")),
        SERIF_ITALIC("Newsreader-Italic.ttf", List.of(".serif-italic"),
                new Candidate("PT Serif", FontWeight.NORMAL, FontPosture.ITALIC, "PT Serif Italic"),
                new Candidate("Georgia", FontWeight.NORMAL, FontPosture.ITALIC, "Georgia Italic"),
                new Candidate("Cambria", FontWeight.NORMAL, FontPosture.ITALIC, "Cambria Italic")),
        SERIF_MEDIUM("Newsreader-Medium.ttf",
                List.of(".serif-medium", ".text-display", ".text-title", ".text-numeral", ".sidebar-brand-name"),
                c("PT Serif", FontWeight.NORMAL, "PT Serif"),
                c("Georgia", FontWeight.NORMAL, "Georgia"),
                c("Cambria", FontWeight.NORMAL, "Cambria"),
                c("Times New Roman", FontWeight.NORMAL, "Times New Roman"));

        private final String file;
        private final List<String> selectors;
        private final List<Candidate> fallbacks;

        Face(String file, List<String> selectors, Candidate... fallbacks) {
            this.file = file;
            this.selectors = selectors;
            this.fallbacks = List.of(fallbacks);
        }

        /** The bundled font file under {@code fonts/}. */
        public String file() {
            return file;
        }

        /** The selectors that use this face. */
        public List<String> selectors() {
            return selectors;
        }

        private static Candidate c(String family, FontWeight weight, String expectedName) {
            return new Candidate(family, weight, FontPosture.REGULAR, expectedName);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Typography.class);
    private static final double PROBE_SIZE = 14;

    private static Map<Face, Resolved> faces;
    private static String stylesheet;

    private Typography() {
    }

    /** Loads bundled fonts and resolves every face. Idempotent; call on the FX thread before building UI. */
    public static synchronized void load() {
        if (faces != null) {
            return;
        }
        Map<Face, Resolved> resolved = new EnumMap<>(Face.class);
        for (Face face : Face.values()) {
            Resolved r = loadBundled(face).or(() -> installed(face)).orElseGet(() ->
                    new Resolved("System", FontWeight.NORMAL, FontPosture.REGULAR, "System Regular", false));
            resolved.put(face, r);
        }
        faces = resolved;
        stylesheet = dataUrl(css(resolved));
        LOG.info("Fonts: {}", resolved);
        List<String> missing = resolved.entrySet().stream().filter(e -> !e.getValue().bundled())
                .map(e -> e.getKey().file()).toList();
        if (!missing.isEmpty()) {
            LOG.warn("Bundled fonts missing from {}fonts/, using installed fallbacks: {}",
                    Typography.class.getPackageName().replace('.', '/') + "/", missing);
        }
    }

    /** The resolved font for {@code face}. */
    public static Resolved face(Face face) {
        load();
        return faces.get(face);
    }

    /** True when every face uses its bundled font. */
    public static boolean allBundled() {
        load();
        return faces.values().stream().allMatch(Resolved::bundled);
    }

    /** A font for code that draws text itself (canvas charts). */
    public static Font font(Face face, double size) {
        Resolved r = face(face);
        return Font.font(r.family(), r.weight(), r.posture(), size);
    }

    /** The generated face stylesheet as a {@code data:} URL. */
    public static String stylesheet() {
        load();
        return stylesheet;
    }

    private static Optional<Resolved> loadBundled(Face face) {
        URL url = Typography.class.getResource("fonts/" + face.file());
        if (url == null) {
            return Optional.empty();
        }
        try (InputStream in = url.openStream()) {
            Font font = Font.loadFont(in, PROBE_SIZE);
            if (font == null) {
                return Optional.empty();
            }
            FontPosture posture = font.getStyle().toLowerCase(Locale.ROOT).contains("italic")
                    ? FontPosture.ITALIC : FontPosture.REGULAR;
            for (FontWeight weight : FontWeight.values()) {
                if (Font.font(font.getFamily(), weight, posture, PROBE_SIZE).getName().equals(font.getName())) {
                    return Optional.of(new Resolved(font.getFamily(), weight, posture, font.getName(), true));
                }
            }
            LOG.warn("Bundled font {} loaded but cannot be selected by family", face.file());
        } catch (IOException e) {
            LOG.warn("Could not load font {}", face.file(), e);
        }
        return Optional.empty();
    }

    private static Optional<Resolved> installed(Face face) {
        for (Candidate c : face.fallbacks) {
            Font font = Font.font(c.family(), c.weight(), c.posture(), PROBE_SIZE);
            if (font.getName().equalsIgnoreCase(c.expectedName())) {
                return Optional.of(new Resolved(c.family(), c.weight(), c.posture(), font.getName(), false));
            }
        }
        return Optional.empty();
    }

    static String css(Map<Face, Resolved> resolved) {
        StringBuilder css = new StringBuilder();
        for (Face face : Face.values()) {
            Resolved r = resolved.get(face);
            css.append(String.join(", ", face.selectors()))
                    .append(" { -fx-font-family: \"").append(r.family().replace("\"", "")).append("\";")
                    .append(" -fx-font-weight: ").append(r.weight().getWeight()).append(';')
                    .append(" -fx-font-style: ").append(r.posture() == FontPosture.ITALIC ? "italic" : "normal")
                    .append("; }\n");
        }
        return css.toString();
    }

    private static String dataUrl(String css) {
        return "data:text/css;base64," + Base64.getEncoder().encodeToString(css.getBytes(StandardCharsets.UTF_8));
    }
}
