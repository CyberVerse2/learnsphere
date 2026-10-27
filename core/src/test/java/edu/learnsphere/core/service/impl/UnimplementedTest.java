package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.service.SharingService;
import org.junit.jupiter.api.Test;

class UnimplementedTest {

    @Test
    void everyCallNamesTheServiceAndMethod() {
        SharingService sharing = Unimplemented.service(SharingService.class);

        assertThatThrownBy(() -> sharing.inspect(null)).isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("SharingService.inspect");
        assertThat(sharing.toString()).isEqualTo("Unimplemented[SharingService]");
        assertThat(sharing).isEqualTo(sharing).isNotEqualTo(Unimplemented.service(SharingService.class));
    }
}
