package com.reconiq.resolution.port.memory;

import com.reconiq.resolution.port.CanonicalAmountPort;
import com.reconiq.resolution.port.CasePersistencePort;
import com.reconiq.resolution.port.ResolutionHistoryPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class InMemoryPortConfiguration {

    @Bean
    @ConditionalOnMissingBean(CasePersistencePort.class)
    CasePersistencePort casePersistencePort() {
        return new InMemoryCasePersistenceAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(ResolutionHistoryPort.class)
    ResolutionHistoryPort resolutionHistoryPort() {
        return new InMemoryResolutionHistoryAdapter();
    }

    @Bean
    @ConditionalOnMissingBean(CanonicalAmountPort.class)
    CanonicalAmountPort canonicalAmountPort() {
        return new InMemoryCanonicalAmountAdapter();
    }
}
