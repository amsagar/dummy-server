package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.repository.AgentToolRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolImportServiceTest {

    @Test
    void importsOpenApi3ToolsWithHostAndEndpoint() {
        AgentToolRepository repository = mock(AgentToolRepository.class);
        when(repository.save(any(AgentTool.class))).thenAnswer(i -> i.getArgument(0));
        ToolImportService service = new ToolImportService(new ObjectMapper(), repository);

        String spec = """
                {
                  "openapi": "3.0.1",
                  "servers": [{"url": "https://api.example.com/v1"}],
                  "paths": {
                    "/pets": {
                      "get": {
                        "operationId": "listPets",
                        "summary": "List pets",
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """;

        List<AgentTool> imported = service.importOpenApi("d1", spec, true);

        assertEquals(1, imported.size());
        assertEquals("https://api.example.com", imported.get(0).getHost());
        assertEquals("/v1/pets", imported.get(0).getEndpoint());
    }

    @Test
    void importsSwagger2ToolsWithHostBasePathAndSchemes() {
        AgentToolRepository repository = mock(AgentToolRepository.class);
        when(repository.save(any(AgentTool.class))).thenAnswer(i -> i.getArgument(0));
        ToolImportService service = new ToolImportService(new ObjectMapper(), repository);

        String spec = """
                {
                  "swagger": "2.0",
                  "host": "petstore.swagger.io",
                  "basePath": "/v2",
                  "schemes": ["https"],
                  "paths": {
                    "/store/inventory": {
                      "get": {
                        "operationId": "getInventory",
                        "responses": {"200": {"description": "ok"}}
                      }
                    }
                  }
                }
                """;

        List<AgentTool> imported = service.importOpenApi("d1", spec, true);

        assertEquals(1, imported.size());
        assertEquals("https://petstore.swagger.io", imported.get(0).getHost());
        assertEquals("/v2/store/inventory", imported.get(0).getEndpoint());
    }

    @Test
    void importsCurlWithQuotedUrlIntoHostAndEndpoint() {
        AgentToolRepository repository = mock(AgentToolRepository.class);
        when(repository.save(any(AgentTool.class))).thenAnswer(i -> i.getArgument(0));
        ToolImportService service = new ToolImportService(new ObjectMapper(), repository);

        service.importCurl(
                "d1",
                "curl --request GET --url 'https://api.example.com/v3/orders?status=open' -H 'accept: application/json'",
                "ordersByStatus",
                null,
                "{\"ok\":true}",
                true
        );

        ArgumentCaptor<AgentTool> captor = ArgumentCaptor.forClass(AgentTool.class);
        verify(repository, times(1)).save(captor.capture());
        AgentTool saved = captor.getValue();

        assertEquals("https://api.example.com", saved.getHost());
        assertEquals("/v3/orders?status=open", saved.getEndpoint());
    }
}
