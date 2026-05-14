package com.pods.agent.service;

import com.pods.agent.domain.AgentTool;
import com.pods.agent.domain.DecisionTable;
import com.pods.agent.dmn.EvaluationResult;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    void readPaginatesByLineAndReportsRange() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-read-paged.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 100; i++) content.append("line-").append(i).append('\n');
        Files.writeString(temp, content.toString());

        AgentTool readTool = AgentTool.builder()
                .id("200")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String pathArg = temp.toString().replace("\\", "\\\\");
        var midResult = service.execute(readTool, "{\"path\":\"" + pathArg + "\",\"offset\":50,\"limit\":20}");
        assertTrue(midResult.success());
        String midBody = midResult.body();
        assertTrue(midBody.contains("50\tline-50"), "first paginated line should be 50, was:\n" + midBody);
        assertTrue(midBody.contains("69\tline-69"), "last paginated line should be 69, was:\n" + midBody);
        assertFalse(midBody.contains("70\tline-70"), "line 70 must not appear in offset=50,limit=20 page");
        assertTrue(midBody.contains("[lines 50-69 of 100"), "footer must report range and total");
        assertTrue(midBody.contains("\"offset\":70"), "footer must hint next offset when more remains");
    }

    @Test
    void readWithoutNextOffsetHintAtEof() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-read-eof.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "a\nb\nc\n");

        AgentTool readTool = AgentTool.builder()
                .id("201")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String pathArg = temp.toString().replace("\\", "\\\\");
        var result = service.execute(readTool, "{\"path\":\"" + pathArg + "\",\"offset\":1,\"limit\":10}");
        assertTrue(result.success());
        String body = result.body();
        assertTrue(body.contains("[lines 1-3 of 3]"), "footer should not include next-offset hint at EOF, was:\n" + body);
        assertFalse(body.contains("call again"), "no 'call again' hint when fully read");
    }

    @Test
    void readOffsetPastEofReturnsEmptyBodyWithFooter() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-read-past-eof.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "only\none\n");

        AgentTool readTool = AgentTool.builder()
                .id("202")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String pathArg = temp.toString().replace("\\", "\\\\");
        var result = service.execute(readTool, "{\"path\":\"" + pathArg + "\",\"offset\":500,\"limit\":10}");
        assertTrue(result.success(), "reading past EOF should still succeed");
        String body = result.body();
        assertFalse(body.contains("only"), "no content should appear when offset is past EOF");
        assertTrue(body.contains("of 2"), "footer must still report total line count, was:\n" + body);
    }

    @Test
    void readWithoutPaginationReturnsFullSmallFile() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-read-small.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        Files.writeString(temp, "hello world\nsecond line\n");

        AgentTool readTool = AgentTool.builder()
                .id("203")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String pathArg = temp.toString().replace("\\", "\\\\");
        var result = service.execute(readTool, "{\"path\":\"" + pathArg + "\"}");
        assertTrue(result.success());
        assertTrue(result.body().equals("hello world\nsecond line\n"),
                "small-file legacy read must return raw content unchanged, was:\n" + result.body());
    }

    @Test
    void readWithoutPaginationAddsTruncationFooterWhenOversized() throws Exception {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        Path temp = Path.of("target/tool-execution-read-oversized.txt").toAbsolutePath();
        Files.createDirectories(temp.getParent());
        StringBuilder big = new StringBuilder(70_000);
        // 70 KB of content, with newlines so totalLines is meaningful
        for (int i = 0; big.length() < 70_000; i++) big.append("filler-line-").append(i).append('\n');
        Files.writeString(temp, big.toString());

        AgentTool readTool = AgentTool.builder()
                .id("204")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();

        String pathArg = temp.toString().replace("\\", "\\\\");
        var result = service.execute(readTool, "{\"path\":\"" + pathArg + "\"}");
        assertTrue(result.success());
        String body = result.body();
        assertTrue(body.contains("[truncated at 65536 chars of "), "must include truncation marker, was tail:\n"
                + body.substring(Math.max(0, body.length() - 200)));
        assertTrue(body.contains("\"offset\""), "footer must reference offset/limit for follow-up paging");
    }

    @Test
    void readReturnsErrorForMissingFile() {
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper());
        AgentTool readTool = AgentTool.builder()
                .id("205")
                .name("read")
                .executionKind("filesystem")
                .enabled(true)
                .build();
        var result = service.execute(readTool, "{\"path\":\"target/does-not-exist-" + System.nanoTime() + ".txt\"}");
        assertFalse(result.success());
        assertTrue(result.error() != null && result.error().contains("File not found"),
                "error must report file-not-found, was: " + result.error());
    }

    @Test
    void dtListReturnsCatalog() throws Exception {
        DecisionTableService dtService = mock(DecisionTableService.class);
        DecisionTable a = DecisionTable.builder().name("User Test").description("u").hitPolicy("FIRST").updatedAt(1L).build();
        DecisionTable b = DecisionTable.builder().name("Pricing").description("p").hitPolicy("FIRST").updatedAt(2L).build();
        when(dtService.list()).thenReturn(List.of(a, b));

        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);
        AgentTool tool = AgentTool.builder().id("d1").name("dtList").executionKind("integration").enabled(true).build();

        var result = service.execute(tool, "{}");
        assertTrue(result.success(), "dtList should succeed: " + result.error());
        String body = result.body();
        assertTrue(body.contains("\"total\":2"), "must report total, was:\n" + body);
        assertTrue(body.contains("\"User Test\"") && body.contains("\"Pricing\""), "both names present, was:\n" + body);
    }

    @Test
    void dtSearchReturnsRankedResults() throws Exception {
        DecisionTableService dtService = mock(DecisionTableService.class);
        when(dtService.search("user", 8)).thenReturn(List.of(
                new java.util.LinkedHashMap<>(Map.of("name", "User Test", "score", 14))));

        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);
        AgentTool tool = AgentTool.builder().id("d2").name("dtSearch").executionKind("integration").enabled(true).build();

        var result = service.execute(tool, "{\"query\":\"user\"}");
        assertTrue(result.success(), "dtSearch should succeed: " + result.error());
        String body = result.body();
        assertTrue(body.contains("\"User Test\""), "expected hit present, was:\n" + body);
        assertTrue(body.contains("\"score\":14"), "expected score present, was:\n" + body);
        assertTrue(body.contains("\"count\":1"), "count must reflect hits, was:\n" + body);
    }

    @Test
    void dtSearchRequiresQuery() {
        DecisionTableService dtService = mock(DecisionTableService.class);
        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);
        AgentTool tool = AgentTool.builder().id("d3").name("dtSearch").executionKind("integration").enabled(true).build();

        var result = service.execute(tool, "{}");
        assertFalse(result.success(), "missing query must fail");
        assertTrue(result.error() != null && result.error().contains("query is required"),
                "error must mention query, was: " + result.error());
    }

    @Test
    void dtMetadataReturnsDescribe() throws Exception {
        DecisionTableService dtService = mock(DecisionTableService.class);
        when(dtService.describe("User Test", false)).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "name", "User Test",
                "requiredInputs", List.of("season"),
                "ruleCount", 3)));

        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);
        AgentTool tool = AgentTool.builder().id("d4").name("dtMetadata").executionKind("integration").enabled(true).build();

        var result = service.execute(tool, "{\"name\":\"User Test\"}");
        assertTrue(result.success(), "dtMetadata should succeed: " + result.error());
        String body = result.body();
        assertTrue(body.contains("\"User Test\"") && body.contains("\"season\"") && body.contains("\"ruleCount\":3"),
                "metadata fields must be serialized, was:\n" + body);
    }

    @Test
    void dtMetadataReportsUnknownTable() {
        DecisionTableService dtService = mock(DecisionTableService.class);
        when(dtService.describe("Ghost", false))
                .thenThrow(new IllegalArgumentException("Decision table not found: Ghost"));

        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);
        AgentTool tool = AgentTool.builder().id("d5").name("dtMetadata").executionKind("integration").enabled(true).build();

        var result = service.execute(tool, "{\"name\":\"Ghost\"}");
        assertFalse(result.success(), "unknown table must fail");
        assertTrue(result.error() != null && result.error().contains("not found"),
                "error must mention not found, was: " + result.error());
    }

    @Test
    void dtEvaluateAcceptsLegacyAlias() throws Exception {
        DecisionTableService dtService = mock(DecisionTableService.class);
        when(dtService.requiredInputNames("User Test")).thenReturn(List.of());
        when(dtService.evaluate(org.mockito.ArgumentMatchers.eq("User Test"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new EvaluationResult(true, List.of(), Map.of("discount", true)));

        ToolExecutionService service = new ToolExecutionService(new ObjectMapper(), null, null, null, null, dtService);

        AgentTool legacyTool = AgentTool.builder().id("d6a").name("decisionTableEvaluate").executionKind("integration").enabled(true).build();
        var legacyResult = service.execute(legacyTool, "{\"tableName\":\"User Test\",\"inputs\":{\"season\":\"Sunny\"}}");
        assertTrue(legacyResult.success(), "legacy alias must still work: " + legacyResult.error());
        assertTrue(legacyResult.body().contains("\"matched\":true"));

        AgentTool newTool = AgentTool.builder().id("d6b").name("dtEvaluate").executionKind("integration").enabled(true).build();
        var newResult = service.execute(newTool, "{\"tableName\":\"User Test\",\"inputs\":{\"season\":\"Sunny\"}}");
        assertTrue(newResult.success(), "new name must work: " + newResult.error());
        assertTrue(newResult.body().contains("\"discount\":true"));
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
