package com.reconiq.resolution;

import com.reconiq.resolution.port.CanonicalAmountPort;
import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ResolutionServiceApplicationTests {

    @Autowired
    private CasePersistencePort casePersistencePort;

    @Autowired
    private ResolutionHistoryPort resolutionHistoryPort;

    @Autowired
    private CanonicalAmountPort canonicalAmountPort;

    @Test
    void contextLoadsWithInMemoryPortFallbacks() {
        assertThat(casePersistencePort).isNotNull();
        assertThat(resolutionHistoryPort).isNotNull();
        assertThat(canonicalAmountPort).isNotNull();
    }
}
