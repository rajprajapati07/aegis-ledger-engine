package com.aegis.ledger.config;

import com.aegis.ledger.core.HybridLogicalClock;
import com.aegis.ledger.store.LedgerStore;
import com.aegis.ledger.store.PostingEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Wires the framework-free ledger core into Spring. The core classes take a
 * DataSource and nothing else, so the same code runs in tests without a
 * Spring context.
 */
@Configuration
public class LedgerConfig {

    @Bean
    public HybridLogicalClock hybridLogicalClock(
            @Value("${aegis.clock.max-offset-ms:500}") long maxOffsetMs) {
        return HybridLogicalClock.system(maxOffsetMs);
    }

    @Bean
    public LedgerStore ledgerStore(DataSource dataSource) {
        return new LedgerStore(dataSource);
    }

    @Bean
    public PostingEngine postingEngine(
            DataSource dataSource,
            HybridLogicalClock clock,
            @Value("${aegis.idempotency.ttl-hours:168}") long ttlHours,
            @Value("${aegis.posting.lock-timeout-ms:2000}") long lockTimeoutMs) {
        return new PostingEngine(dataSource, clock, Duration.ofHours(ttlHours), Duration.ofMillis(lockTimeoutMs));
    }
}
