package com.pods.agent.config;

import com.pods.agent.service.FrameworkToolPackService;
import com.pods.agent.service.ToolRegistryService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FrameworkDefaultToolsBootstrapTest {

    @Test
    void installsFrameworkDefaultsAndRefreshesRegistryOnStartup() {
        FrameworkToolPackService frameworkToolPackService = mock(FrameworkToolPackService.class);
        ToolRegistryService toolRegistryService = mock(ToolRegistryService.class);
        when(frameworkToolPackService.installDefaults()).thenReturn(new FrameworkToolPackService.InstallResult(3, 7, 10));

        FrameworkDefaultToolsBootstrap bootstrap = new FrameworkDefaultToolsBootstrap(frameworkToolPackService, toolRegistryService);
        bootstrap.installDefaultsOnStartup();

        verify(frameworkToolPackService).installDefaults();
        verify(toolRegistryService).refresh();
    }
}
