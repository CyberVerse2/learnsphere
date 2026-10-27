package edu.learnsphere.app.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The stylesheet defines every colour token from docs/DESIGN.md with its light and dark value. */
class ThemeTokensTest {

    /** Token, light value, dark value (docs/DESIGN.md, "Colour"). */
    private static final String[][] TOKENS = {
            {"-ls-bg", "#F6F3EE", "#141311"},
            {"-ls-surface", "#FFFFFF", "#1D1B19"},
            {"-ls-surface-sunken", "#EFEBE4", "#100F0E"},
            {"-ls-border", "#E4DED4", "#2E2B27"},
            {"-ls-text", "#1E1C19", "#EEEAE3"},
            {"-ls-text-muted", "#6E685E", "#A59F95"},
            {"-ls-text-faint", "#9C958A", "#6F6A62"},
            {"-ls-accent", "#2F55D4", "#7E97FF"},
            {"-ls-accent-soft", "#E6EBFB", "#1F2645"},
            {"-ls-success", "#2E7D5B", "#5DBB8E"},
            {"-ls-warning", "#B26B12", "#E0A458"},
            {"-ls-danger", "#B3413A", "#E5796F"},
            {"-ls-highlight", "#FFE9A8", "#5A4A1C"},
    };

    private static String css;

    @BeforeAll
    static void read() throws IOException {
        try (InputStream in = ThemeTokensTest.class.getResourceAsStream("learnsphere.css")) {
            assertThat(in).isNotNull();
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> block(String selector) {
        Matcher m = Pattern.compile(Pattern.quote(selector) + "\\s*\\{([^}]*)}").matcher(css);
        assertThat(m.find()).as("block %s", selector).isTrue();
        Map<String, String> values = new LinkedHashMap<>();
        Matcher decl = Pattern.compile("(-ls-[a-z-]+)\\s*:\\s*([^;]+);").matcher(m.group(1));
        while (decl.find()) {
            values.put(decl.group(1), decl.group(2).strip());
        }
        return values;
    }

    @Test
    void definesEveryTokenForLightAndDark() {
        Map<String, String> light = block(".root, .theme-light ");
        Map<String, String> dark = block(".root.dark, .theme-dark ");
        for (String[] token : TOKENS) {
            assertThat(light.get(token[0])).as("light %s", token[0]).isEqualToIgnoringCase(token[1]);
            assertThat(dark.get(token[0])).as("dark %s", token[0]).isEqualToIgnoringCase(token[2]);
        }
        assertThat(dark.keySet()).as("dark overrides every light token").containsAll(light.keySet());
    }

    @Test
    void leavesFontFacesToTypography() {
        assertThat(css).doesNotContain("-fx-font-family").doesNotContain("-fx-font-weight");
    }

    @Test
    void generatesOneRulePerFace() {
        Map<Typography.Face, Typography.Resolved> faces = new java.util.EnumMap<>(Typography.Face.class);
        for (Typography.Face face : Typography.Face.values()) {
            faces.put(face, new Typography.Resolved("Family " + face.ordinal(), javafx.scene.text.FontWeight.SEMI_BOLD,
                    javafx.scene.text.FontPosture.REGULAR, "Name", true));
        }
        String generated = Typography.css(faces);
        assertThat(generated.lines()).hasSize(Typography.Face.values().length);
        assertThat(generated).contains(".root { -fx-font-family: \"Family 0\"; -fx-font-weight: 600; -fx-font-style: normal; }");
        assertThat(generated).contains(".text-display");
    }
}
