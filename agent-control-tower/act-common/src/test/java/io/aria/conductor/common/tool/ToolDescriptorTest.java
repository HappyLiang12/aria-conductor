package io.aria.conductor.common.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolDescriptorTest {

    @Test
    void descriptorCarriesTheReviewedPolicy() {
        ToolDescriptor descriptor = new ToolDescriptor(
                "get_run", "Read one run", ToolPolicy.WORKER_READ, true);

        assertThat(descriptor.policy()).isEqualTo(ToolPolicy.WORKER_READ);
        assertThat(descriptor.autoApproveRead()).isTrue();
    }

    @Test
    void nameAndPolicyAreRequired() {
        assertThatThrownBy(() -> new ToolDescriptor(null, "x", ToolPolicy.WORKER_READ, false))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolDescriptor("x", "x", null, false))
                .isInstanceOf(NullPointerException.class);
    }
}
