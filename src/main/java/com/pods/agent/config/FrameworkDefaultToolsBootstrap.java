package com.pods.agent.config;

import com.pods.agent.service.FrameworkToolPackService;
import com.pods.agent.service.ToolRegistryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class FrameworkDefaultToolsBootstrap {

    private final FrameworkToolPackService frameworkToolPackService;
    private final ToolRegistryService toolRegistryService;

    public FrameworkDefaultToolsBootstrap(FrameworkToolPackService frameworkToolPackService,
                                          ToolRegistryService toolRegistryService) {
        this.frameworkToolPackService = frameworkToolPackService;
        this.toolRegistryService = toolRegistryService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void installDefaultsOnStartup() {
        var result = frameworkToolPackService.installDefaults();
        toolRegistryService.refresh();
        log.info("[FrameworkDefaultToolsBootstrap] Framework defaults ready: created={}, updated={}, total={}",
                result.created(),
                result.updated(),
                result.total());
    }
}
