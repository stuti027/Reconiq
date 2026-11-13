package com.reconiq.ingestion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@EnableScheduling
public class ScheduledPollingService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPollingService.class);

    // Runs every 30 seconds to fetch un-synced transactions from remote gateways/banks
    @Scheduled(fixedRate = 30000)
    public void pollExternalSources() {
        log.info("Executing scheduled polling for pending transactions from external sources...");
        // Logic for polling batch endpoints of simulated payment gateways/banks
    }
}