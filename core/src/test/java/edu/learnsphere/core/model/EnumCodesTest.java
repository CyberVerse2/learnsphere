package edu.learnsphere.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Keeps the Java enum codes and the schema's CHECK lists in step. */
class EnumCodesTest {

    private static final List<Map.Entry<String, Class<? extends CodedEnum>>> COLUMNS = List.of(
            Map.entry("origin IN", CourseOrigin.class),
            Map.entry("visibility IN", Visibility.class),
            Map.entry("status IN", CourseStatus.class),
            Map.entry("status IN", EnrolmentStatus.class),
            Map.entry("status IN", DocumentStatus.class),
            Map.entry("error_code IS NULL OR error_code IN", DocumentError.class),
            Map.entry("source IN", PageSource.class),
            Map.entry("type IN", ItemType.class),
            Map.entry("origin IN", ItemOrigin.class),
            Map.entry("status IN", ItemStatus.class),
            Map.entry("support IN", SupportStatus.class),
            Map.entry("state IN", ReviewPhase.class),
            Map.entry("kind IN", SessionKind.class),
            Map.entry("graded_by IN", GradedBy.class),
            Map.entry("role IN", ChatRole.class),
            Map.entry("status IN", MessageStatus.class),
            Map.entry("kind IN", JobKind.class),
            Map.entry("status IN", JobStatus.class),
            Map.entry("reason IN", FlagReason.class),
            Map.entry("source IN", FlagSource.class),
            Map.entry("entity_type IN", EntityType.class));

    @Test
    void codesAreLowercaseNamesAndRoundTrip() {
        for (Class<? extends CodedEnum> type : COLUMNS.stream().map(Map.Entry::getValue).distinct().toList()) {
            for (CodedEnum value : type.getEnumConstants()) {
                assertThat(value.code()).isEqualTo(((Enum<?>) value).name().toLowerCase());
                assertThat(fromCode(type, value.code())).isSameAs(value);
            }
        }
        assertThatThrownBy(() -> CodedEnum.fromCode(ItemType.class, "essay")).isInstanceOf(IllegalArgumentException.class);
        assertThat(CodedEnum.fromNullableCode(ItemType.class, null)).isNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fromCode(Class<? extends CodedEnum> type, String code) {
        return CodedEnum.fromCode((Class) type, code);
    }

    @Test
    void schemaCheckListsMatchEnumCodes() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V1__initial.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (Map.Entry<String, Class<? extends CodedEnum>> entry : COLUMNS) {
            Set<String> expected = new LinkedHashSet<>();
            Arrays.stream(entry.getValue().getEnumConstants()).forEach(v -> expected.add(v.code()));
            Pattern check = Pattern.compile("CHECK \\(" + Pattern.quote(entry.getKey()) + " \\(([^)]*)\\)\\)");
            Matcher m = check.matcher(sql);
            boolean found = false;
            while (m.find()) {
                Set<String> listed = new LinkedHashSet<>();
                for (String part : m.group(1).split(",")) {
                    listed.add(part.strip().replace("'", ""));
                }
                if (listed.equals(expected)) {
                    found = true;
                }
            }
            assertThat(found).as("CHECK for %s lists %s", entry.getKey(), expected).isTrue();
        }
    }

    @Test
    void intCodedEnumsRoundTrip() {
        for (Rating rating : Rating.values()) {
            assertThat(Rating.fromValue(rating.value())).isSameAs(rating);
        }
        assertThat(Rating.AGAIN.isPass()).isFalse();
        assertThat(Rating.GOOD.isPass()).isTrue();
    }
}
