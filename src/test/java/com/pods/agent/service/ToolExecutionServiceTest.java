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

    /**
     * Regression for the alignment death-spiral: the model has been
     * observed issuing placeholder {@code edit(old_text, new_text)} calls
     * where {@code old_text == new_text} (or otherwise net out to
     * identical bytes) to satisfy a "you must edit before replying"
     * retry instruction. Returning success on such a write made the
     * per-attempt audit lie ("edit: 1 successful calls" alongside
     * "draft unchanged"), confusing the model on subsequent retries.
     * The tool must refuse the call so the no-op detector sees the truth.
     */
    @Test
    void editRefusesNoOpWhenOldAndNewProduceIdenticalContent() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-noop-edit-test.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "hello world");

        AgentTool editTool = AgentTool.builder()
                .id("100")
                .name("edit")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String json = "{\"path\":\"" + temp.toString().replace("\\", "\\\\") + "\","
                + "\"old_text\":\"hello\",\"new_text\":\"hello\"}";
        var result = service.execute(editTool, json);

        assertFalse(result.success(), "no-op edit must report failure, not success");
        assertTrue(result.error() != null && result.error().contains("no-op"),
                "error must explain it's a no-op, was: " + result.error());
        assertTrue(Files.readString(temp).equals("hello world"),
                "file must be untouched after a refused no-op edit");
    }

    @Test
    void editRefusesFullRewriteWhenContentMatchesExisting() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-noop-rewrite-test.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "alpha\nbeta\n");

        AgentTool editTool = AgentTool.builder()
                .id("101")
                .name("edit")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String json = "{\"path\":\"" + temp.toString().replace("\\", "\\\\") + "\","
                + "\"content\":\"alpha\\nbeta\\n\"}";
        var result = service.execute(editTool, json);

        assertFalse(result.success(), "full-file rewrite that matches existing bytes must report failure");
        assertTrue(result.error() != null && result.error().contains("no-op"),
                "error must mention no-op, was: " + result.error());
    }

    @Test
    void applyPatchRefusesWhenHunksNetOutToIdenticalContent() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-noop-patch-test.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "alpha\nbeta\ngamma\n");

        AgentTool patchTool = AgentTool.builder()
                .id("102")
                .name("apply_patch")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        // ORIGINAL == UPDATED → applying the patch must produce a body
        // byte-identical to the input. The guard refuses it.
        String patchContent = "<<<<<<< ORIGINAL\nbeta\n=======\nbeta\n>>>>>>> UPDATED";
        String escapedPatch = patchContent.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String json = "{\"path\":\"" + temp.toString().replace("\\", "\\\\") + "\","
                + "\"content\":\"" + escapedPatch + "\"}";
        var result = service.execute(patchTool, json);

        assertFalse(result.success(), "apply_patch with net-zero hunks must report failure");
        assertTrue(result.error() != null && result.error().contains("no-op"),
                "error must mention no-op, was: " + result.error());
        assertTrue(Files.readString(temp).equals("alpha\nbeta\ngamma\n"),
                "file must remain unchanged after a refused no-op patch");
    }

    @Test
    void mcpIntegrationTreatsIsErrorPayloadAsFailure() {
        McpClientService mcpClientService = mock(McpClientService.class);
        when(mcpClientService.callTool("srv-1", "code_widget_create", "{\"query\":\"test\"}"))
                .thenReturn("{\"content\":[{\"type\":\"text\",\"text\":\"Not found: Unknown tool: 'code_widget_create'\"}],\"isError\":true}");
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), mcpClientService, null, null, null, null);
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
