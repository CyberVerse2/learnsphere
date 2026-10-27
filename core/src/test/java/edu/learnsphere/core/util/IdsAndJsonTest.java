package edu.learnsphere.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.model.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdsAndJsonTest {

    @Test
    void createsValidLowercaseIds() {
        String id = Ids.newId();
        assertThat(Ids.isValid(id)).isTrue();
        assertThat(id).isEqualTo(id.toLowerCase());
        assertThat(Ids.isValid("../etc")).isFalse();
        assertThat(Ids.isValid(null)).isFalse();
        assertThatThrownBy(() -> Ids.requireValid("nope")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsRecordsAndSkipsNulls() {
        Citation citation = new Citation(1, "c", "d", "Title", 3, 4, "Snippet");
        String json = Json.write(List.of(citation));
        assertThat(Json.read(json, new com.fasterxml.jackson.core.type.TypeReference<List<Citation>>() { }))
                .containsExactly(citation);
        assertThat(Json.write(new Holder(null, 2))).isEqualTo("{\"b\":2}");
        assertThat(Json.read("{\"b\":2,\"unknown\":true}", Holder.class)).isEqualTo(new Holder(null, 2));
    }

    record Holder(String a, int b) {
    }
}
