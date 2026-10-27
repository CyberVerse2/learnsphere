package edu.learnsphere.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

class UserMessageTest {

    @Test
    void keepsShortSentencesAsWritten() {
        assertThat(UserMessage.of("  This PDF can't be opened.  ").text()).isEqualTo("This PDF can't be opened.");
        assertThatThrownBy(() -> UserMessage.of("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shortensLongTextAtAWordBoundaryInsteadOfFailing() {
        String longTitle = "Advanced Topics in Molecular and Cellular Biology for Graduate Students ".repeat(4);
        UserMessage message = UserMessage.of("“" + longTitle.strip() + "” couldn't be imported. Try again.");

        assertThat(message.text()).hasSizeLessThanOrEqualTo(UserMessage.MAX_LENGTH).endsWith("…");
        assertThat(message.text().charAt(message.text().length() - 2)).isNotEqualTo(' ');
        assertThat(UserMessage.of("x".repeat(500)).text()).hasSize(UserMessage.MAX_LENGTH);
    }

    @Test
    void everyStandardMessageFitsUnshortened() throws IllegalAccessException {
        for (Field field : UserMessage.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == UserMessage.class) {
                UserMessage message = (UserMessage) field.get(null);
                assertThat(message.text()).as(field.getName()).doesNotEndWith("…");
            }
        }
    }
}
