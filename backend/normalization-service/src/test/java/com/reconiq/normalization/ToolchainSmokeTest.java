package com.reconiq.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolchainSmokeTest {

    @Test
    void junitRuns() {
        assertThat(1 + 1).isEqualTo(2);
    }

    @Test
    void runsOnJava17OrNewer() {
        System.out.println("Running on Java " + System.getProperty("java.version"));
        assertThat(Runtime.version().feature()).isGreaterThanOrEqualTo(17);
    }
}