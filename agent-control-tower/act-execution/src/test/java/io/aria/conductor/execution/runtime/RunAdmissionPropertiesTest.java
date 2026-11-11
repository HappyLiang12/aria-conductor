package io.aria.conductor.execution.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunAdmissionPropertiesTest {

    @Test
    void defaultsAreSixWorkersPlusThreeAriaSlots() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        assertThat(properties.getMaxActive()).isEqualTo(6);
        assertThat(properties.getAriaReserved()).isEqualTo(3);
    }

    @Test
    void zeroMeansUnlimited() {
        RunAdmissionProperties properties = new RunAdmissionProperties();
        properties.setMaxActive(0);
        properties.setAriaReserved(0);
        assertThat(properties.getMaxActive()).isZero();
        assertThat(properties.getAriaReserved()).isZero();
    }
}
