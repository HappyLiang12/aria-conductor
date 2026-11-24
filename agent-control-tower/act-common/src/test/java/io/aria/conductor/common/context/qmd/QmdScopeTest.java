package io.aria.conductor.common.context.qmd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QmdScopeTest {

    @Test
    void canonicalProjectNamesAreRecovered() {
        assertThat(QmdScope.projectOf("/Users/happy/Documents/project/aria-conductor"))
                .isEqualTo("project:aria-conductor");
        assertThat(QmdScope.projectOf("/Users/happy/aria-conductor-a3c9ff"))
                .isEqualTo("project:aria-conductor");
        assertThat(QmdScope.projectOf("D:/repo/eqm_root")).isEqualTo("project:eqm-root");
        assertThat(QmdScope.projectOf("C:/work/harness-poc")).isEqualTo("project:harness-poc");
    }

    @Test
    void blankWorkspaceIsNull() {
        assertThat(QmdScope.projectOf(null)).isNull();
        assertThat(QmdScope.projectOf("  ")).isNull();
    }
}
