package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionServiceTest {

    @Test
    void executesFilesystemReadAndWrite() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-test.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "hello");

        AgentTool readTool = AgentTool.builder()
                .id("1")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();
        var readResult = service.execute(readTool, "{\"path\":\"" + temp.toString().replace("\\", "\\\\") + "\"}");
        assertTrue(readResult.success());
        assertTrue(readResult.body().contains("hello"));

        AgentTool writeTool = AgentTool.builder()
                .id("2")
                .name("write")
                .executionKind("filesystem")
                .enabled(true)
                .build();
        var writeResult = service.execute(writeTool, "{\"path\":\"" + temp.toString().replace("\\", "\\\\") + "\",\"content\":\"updated\"}");
        assertTrue(writeResult.success());
        assertTrue(Files.readString(temp).contains("updated"));
    }

    @Test
    void blocksUnsupportedMethodInHttpProxy() {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        AgentTool httpTool = AgentTool.builder()
                .id("3")
                .name("http-tool")
                .executionKind("http_proxy")
                .enabled(true)
                .method("TRACE")
                .endpoint("https://example.com")
                .build();
        var result = service.execute(httpTool, "{}");
        assertFalse(result.success());
    }

    @Test
    void websearchAcceptsLegacyQueryField() {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        AgentTool websearchTool = AgentTool.builder()
                .id("4")
                .name("websearch")
                .executionKind("web")
                .enabled(true)
                .build();

        var result = service.execute(websearchTool, "{\"query\":\"who is the ipl 2025 winner?\"}");
        if (result.success()) {
            assertTrue(result.body().contains("\"query\":\"who is the ipl 2025 winner?\""));
            assertTrue(result.body().contains("\"provider\":\"exa_mcp\""));
        } else {
            assertTrue(result.error() != null && result.error().contains("exa_"));
        }
    }

    @Test
    void mcpIntegrationTreatsIsErrorPayloadAsFailure() {
        McpClientService mcpClientService = mock(McpClientService.class);
        when(mcpClientService.callTool("srv-1", "code_widget_create", "{\"query\":\"test\"}"))
                .thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"Not found: Unknown tool: 'code_widget_create'\"}],\"isError\":true}");
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), mcpClientService, null, null);
        AgentTool tool = AgentTool.builder()
                .id("5")
                .name("mcp_code_widget_create")
                .executionKind("integration")
                .enabled(true)
                .requestSchema("{\"mcpServerId\":\"srv-1\",\"mcpToolName\":\"code_widget_create\"}")
                .build();

        var result = service.execute(tool, "{\"query\":\"test\"}");

        assertFalse(result.success());
        assertTrue(result.error() != null && result.error().contains("MCP integration failed"));
    }
}
