package com.changelens.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.server.McpSyncServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real MCP conversation, as a client such as Claude Desktop or Cursor would hold it: JSON-RPC lines in, JSON-RPC lines out, against
 * a real git repository. No part of the protocol is mocked.
 */
class ChangeLensMcpServerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpSyncServer server;
    private PrintWriter toServer;
    private final BlockingQueue<JsonNode> fromServer = new LinkedBlockingQueue<>();
    private final List<JsonNode> unmatched = new ArrayList<>();
    private int nextId = 1;
    private Path workspace;

    @BeforeEach
    void start() throws Exception {
        workspace = createWorkspaceWithRepo();
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer, 1 << 16);
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream clientFromServer = new PipedInputStream(serverOut, 1 << 16);
        toServer = new PrintWriter(clientToServer, true, StandardCharsets.UTF_8);
        server = new ChangeLensMcpServer(workspace).start(serverIn, serverOut);

        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(clientFromServer, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    fromServer.add(JSON.readTree(line));
                }
            } catch (IOException e) {
                // the pipe closes when the test ends
            }
        }, "mcp-test-reader");
        reader.setDaemon(true);
        reader.start();

        JsonNode init = request("initialize", """
                {"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"test","version":"1"}}""");
        assertThat(init.path("result").path("serverInfo").path("name").asString()).isEqualTo("changelens");
        toServer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void announcesItselfAndItsEightReadOnlyTools() {
        JsonNode tools = request("tools/list", "{}").path("result").path("tools");

        List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.path("name").asString()));
        assertThat(names).containsExactlyInAnyOrder("list_repositories", "analyze_change", "get_overview", "list_changes",
                "affected_endpoints", "untested_changes", "blast_radius", "verify_claims");
        tools.forEach(t -> assertThat(t.path("annotations").path("readOnlyHint").asBoolean()).as(t.path("name").asString()).isTrue());
    }

    @Test
    void analysesARealRepositoryAndAnswersFromTheEngine() {
        JsonNode analysis = call("analyze_change", "{\"repo\":\"demo\",\"base\":\"HEAD~1\",\"head\":\"HEAD\"}");
        String id = analysis.path("analysisId").asString();

        assertThat(id).isNotBlank();
        assertThat(analysis.path("overview").path("methodsBehaviorChanged").asInt()).isEqualTo(1);

        JsonNode changes = call("list_changes", "{\"analysisId\":\"" + id + "\",\"nature\":\"BEHAVIOR\"}");
        assertThat(changes.path("total").asInt()).isEqualTo(1);
        JsonNode greet = changes.path("items").get(0);
        assertThat(greet.path("id").asString()).contains("Greeter#greet");
        assertThat(greet.path("file").asString()).endsWith("Greeter.java");
        List<String> kinds = new ArrayList<>();
        greet.path("facts").forEach(f -> kinds.add(f.path("kind").asString()));
        assertThat(kinds).contains("branch-added");

        assertThat(call("get_overview", "{\"analysisId\":\"" + id + "\"}").path("methodsBehaviorChanged").asInt()).isEqualTo(1);
        assertThat(call("affected_endpoints", "{\"analysisId\":\"" + id + "\"}").path("total").asInt()).isZero();
        assertThat(call("untested_changes", "{\"analysisId\":\"" + id + "\"}").path("total").asInt()).isEqualTo(1);
    }

    @Test
    void verifyClaimsAcceptsRealFactsAndRejectsInventedOnes() {
        String id = call("analyze_change", "{\"repo\":\"demo\",\"base\":\"HEAD~1\",\"head\":\"HEAD\"}").path("analysisId").asString();
        String method = call("list_changes", "{\"analysisId\":\"" + id + "\"}").path("items").get(0).path("id").asString();
        String realFact = call("list_changes", "{\"analysisId\":\"" + id + "\"}").path("items").get(0).path("facts").get(0).path("text").asString();

        JsonNode verdict = call("verify_claims", JSON.writeValueAsString(java.util.Map.of("analysisId", id, "claims", List.of(
                java.util.Map.of("method", method, "fact", realFact),
                java.util.Map.of("method", method, "fact", "now calls PaymentGateway.charge"),
                java.util.Map.of("method", "demo.Nothing#here()")))));

        JsonNode results = verdict.path("results");
        assertThat(results.get(0).path("methodKnown").asBoolean()).isTrue();
        assertThat(results.get(0).path("factTrue").asBoolean()).isTrue();
        assertThat(results.get(1).path("methodKnown").asBoolean()).isTrue();
        assertThat(results.get(1).path("factTrue").asBoolean()).isFalse();
        assertThat(results.get(2).path("methodKnown").asBoolean()).isFalse();
    }

    @Test
    void listsTheRepositoriesItMayRead() {
        JsonNode repos = call("list_repositories", "{}");

        assertThat(repos.path("repositories").get(0).asString()).isEqualTo("demo");
    }

    @Test
    void refusesPathsOptionLookalikesUnknownRepositoriesAndUnknownAnalyses() {
        assertThat(callError("analyze_change", "{\"repo\":\"../outside\",\"base\":\"HEAD~1\",\"head\":\"HEAD\"}")).contains("folder name");
        assertThat(callError("analyze_change", "{\"repo\":\"demo\",\"base\":\"--output=/tmp/x\",\"head\":\"HEAD\"}")).contains("not a valid git revision");
        assertThat(callError("analyze_change", "{\"repo\":\"missing\",\"base\":\"HEAD~1\",\"head\":\"HEAD\"}")).contains("No git repository");
        assertThat(callError("analyze_change", "{\"repo\":\"demo\",\"base\":\"no-such-branch\",\"head\":\"HEAD\"}")).contains("Could not read");
        assertThat(callError("list_changes", "{\"analysisId\":\"a999\"}")).contains("Unknown analysisId");
        // arguments are validated against the tool's JSON schema before the tool runs
        assertThat(callError("list_changes", "{}")).contains("required property 'analysisId'");
    }

    @Test
    void badFiltersAreReportedToTheCallerSoItCanRetry() {
        String id = call("analyze_change", "{\"repo\":\"demo\",\"base\":\"HEAD~1\",\"head\":\"HEAD\"}").path("analysisId").asString();

        assertThat(callError("list_changes", "{\"analysisId\":\"" + id + "\",\"nature\":\"SCARY\"}")).contains("BEHAVIOR");
        assertThat(callError("blast_radius", "{\"analysisId\":\"" + id + "\",\"method\":\"nothingLikeThis\"}")).contains("list_changes");
        assertThat(callError("verify_claims", "{\"analysisId\":\"" + id + "\",\"claims\":[]}")).contains("non-empty");
    }

    // --- tiny JSON-RPC client ---

    private JsonNode request(String method, String paramsJson) {
        int id = nextId++;
        toServer.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + paramsJson + "}");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                JsonNode message = fromServer.poll(1, TimeUnit.SECONDS);
                if (message == null) {
                    continue;
                }
                if (message.path("id").asInt(-1) == id) {
                    return message;
                }
                unmatched.add(message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new AssertionError("no response to " + method + "; unmatched messages: " + unmatched);
    }

    private JsonNode callRaw(String tool, String argumentsJson) {
        return request("tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}").path("result");
    }

    /** The tool's JSON answer, asserting the call succeeded. */
    private JsonNode call(String tool, String argumentsJson) {
        JsonNode result = callRaw(tool, argumentsJson);
        String text = result.path("content").get(0).path("text").asString();
        assertThat(result.path("isError").asBoolean()).as(tool + " -> " + text).isFalse();
        return JSON.readTree(text);
    }

    /** The error message of a tool call that must have failed. */
    private String callError(String tool, String argumentsJson) {
        JsonNode result = callRaw(tool, argumentsJson);
        assertThat(result.path("isError").asBoolean()).as(tool + " " + argumentsJson).isTrue();
        return result.path("content").get(0).path("text").asString();
    }

    private static Path createWorkspaceWithRepo() throws Exception {
        Path workspace = Files.createTempDirectory("changelens-mcp-workspace");
        Path repo = Files.createDirectory(workspace.resolve("demo"));
        try (Git git = Git.init().setDirectory(repo.toFile()).call()) {
            PersonIdent who = new PersonIdent("Test", "test@example.com");
            Path file = repo.resolve("src/main/java/demo/Greeter.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "package demo;\npublic class Greeter {\n    public String greet(String name) {\n        return \"hello \" + name;\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("first").setAuthor(who).setCommitter(who).call();
            Files.writeString(file, "package demo;\npublic class Greeter {\n    public String greet(String name) {\n        if (name == null) {\n            return \"hello stranger\";\n        }\n        return \"hello \" + name;\n    }\n}\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("second").setAuthor(who).setCommitter(who).call();
        }
        return workspace;
    }
}
