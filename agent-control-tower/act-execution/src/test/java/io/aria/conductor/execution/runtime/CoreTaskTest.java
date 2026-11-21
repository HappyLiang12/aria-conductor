package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoreTaskTest {

    @Test
    void theThreeArgumentConstructorCarriesNoAttachments() {
        CoreTask task = new CoreTask("sys", List.of(), "user");

        assertThat(task.attachments()).isEmpty();
    }

    @Test
    void attachmentsAreDefensivelyCopied() {
        List<CoreAttachment> source = new ArrayList<>();
        source.add(new CoreAttachment("image/png", new byte[]{1}, "a.png"));

        CoreTask task = new CoreTask("sys", List.of(), "user", source);
        source.clear();

        assertThat(task.attachments()).hasSize(1);
        assertThatThrownBy(() -> task.attachments()
                .add(new CoreAttachment("image/png", new byte[]{2}, "b.png")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
