package com.pods.agent.vendorRationalization;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Plants the default tunable config on first boot. Idempotent — once the
 * singleton row exists, this seeder never touches it again, so business
 * edits made via the Settings admin page survive restarts.
 */
@Slf4j
@Component
public class VendorRationalizationConfigSeeder {

    private final VendorRationalizationConfigRepository repo;
    private final ObjectMapper objectMapper;

    public VendorRationalizationConfigSeeder(VendorRationalizationConfigRepository repo,
                                             ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void seed() {
        try {
            if (repo.find().isPresent()) {
                log.info("[VRConfigSeeder] config row already exists — leaving it alone");
                return;
            }
            String payload = objectMapper.writeValueAsString(
                    VendorRationalizationConfigDefaults.defaultConfig());
            repo.insertIfAbsent(payload);
            log.info("[VRConfigSeeder] inserted default vendor-rationalization config");
        } catch (Exception e) {
            log.warn("[VRConfigSeeder] failed to seed default config: {}", e.getMessage());
        }
    }
}
