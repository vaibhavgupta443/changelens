package com.changelens.mcp;

import com.changelens.core.Analyzer;
import com.changelens.core.query.ChangeFacts;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * ChangeLens as an MCP server. An AI assistant (or any MCP client) asks questions about what a pull request or commit range
 * changes, and every answer is computed from the code by the same engine the CLI and the REST API use. The tools never ask a model
 * anything and never return a guess: methods are identified by ids that {@code verify_claims} can check afterwards.
 *
 * <p>Tools only read. They open git repositories inside one configured workspace folder, never run or build the code, and never
 * write anything.
 */
public final class ChangeLensMcpServer {

    static final String VERSION = "0.2.0";
    private static final int MAX_STORED_ANALYSES = 50;
    private static final int MAX_ITEMS = 200;

    private final Workspace workspace;
    private final JsonMapper json = JsonMapper.builder().build();
    private final McpJsonMapper mcpJson = McpJsonDefaults.getMapper();
    private final AtomicInteger counter = new AtomicInteger();
    /** Recent analyses by id, least recently used dropped first, so a long session cannot grow without limit. */
    private final Map<String, ChangeFacts> analyses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ChangeFacts> eldest) {
            return size() > MAX_STORED_ANALYSES;
        }
    };

    public ChangeLensMcpServer(java.nio.file.Path workspaceRoot) {
        this.workspace = new Workspace(workspaceRoot);
    }

    /** Starts serving MCP over the given streams. The client owns the lifetime: the server ends when the input ends. */
    public McpSyncServer start(InputStream in, OutputStream out) {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(mcpJson, in, out);
        return McpServer.sync(transport)
                .serverInfo("changelens", VERSION)
                .instructions("ChangeLens explains what a code change does, using static analysis of the code, not a model. "
                        + "Call analyze_change first, then ask about its analysisId. Method ids in answers are citations: "
                        + "check any statement you are about to make with verify_claims before you state it as fact.")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(tools())
                .build();
    }

    private List<SyncToolSpecification> tools() {
        return List.of(
                tool("list_repositories",
                        "List the git repositories (folder names) that analyze_change can read.",
                        schema("", ""),
                        a -> Map.of("workspace", workspace.root().toString(), "repositories", workspace.repositories())),

                tool("analyze_change",
                        "Compare two revisions of a repository and keep the result. Returns an analysisId and an overview "
                                + "(how many methods have new or changed logic, how many endpoints are affected, how many changed methods no test reaches). "
                                + "Java and Spring only. Takes seconds on a large repository.",
                        schema("""
                                "repo": {"type": "string", "description": "Repository folder name inside the workspace (see list_repositories)"},
                                "base": {"type": "string", "description": "Older revision: branch, tag or commit, e.g. origin/main"},
                                "head": {"type": "string", "description": "Newer revision, e.g. HEAD or a branch"}""", "\"repo\", \"base\", \"head\""),
                        this::analyze),

                tool("get_overview",
                        "Counts for an analysis: methods added, removed, changed in behaviour, only reformatted or renamed, endpoints affected, "
                                + "changed methods without a test, and the share of calls the engine could resolve (how complete the call tracing is).",
                        schema("\"analysisId\": {\"type\": \"string\"}", "\"analysisId\""),
                        a -> facts(a).overview()),

                tool("list_changes",
                        "The changed methods of an analysis, most important first (new or changed logic before renames and formatting). "
                                + "Each has an id to cite, its file and lines, and the complete list of facts the engine found: new or removed branches, "
                                + "calls, exceptions, field writes, annotations. Filter with nature and type.",
                        schema("""
                                "analysisId": {"type": "string"},
                                "nature": {"type": "string", "description": "Optional: BEHAVIOR, NEW, SIGNATURE, DELETED, RELOCATED, LOCAL_RENAME or COSMETIC"},
                                "type": {"type": "string", "description": "Optional: only classes whose name contains this text"}""", "\"analysisId\""),
                        a -> page(facts(a).changedMethods(str(a, "nature"), str(a, "type")))),

                tool("affected_endpoints",
                        "Existing features (HTTP endpoints, message listeners, scheduled jobs) whose call path reaches changed code, "
                                + "with the path. Static analysis: reflection and runtime configuration cannot be followed, so an endpoint can be affected without being listed.",
                        schema("\"analysisId\": {\"type\": \"string\"}", "\"analysisId\""),
                        a -> page(facts(a).affectedEntryPoints())),

                tool("untested_changes",
                        "Changed methods that no test reaches. The check is static; a test that reaches the method by reflection is not seen.",
                        schema("\"analysisId\": {\"type\": \"string\"}", "\"analysisId\""),
                        a -> page(facts(a).untestedChanges())),

                tool("blast_radius",
                        "For one changed method: the unchanged code that calls it and the tests that cover it. "
                                + "The method can be a full id or part of one such as OrderService#cancel.",
                        schema("""
                                "analysisId": {"type": "string"},
                                "method": {"type": "string", "description": "A method id from list_changes, or part of one"}""", "\"analysisId\", \"method\""),
                        this::blast),

                tool("verify_claims",
                        "Check statements before you make them. Each claim names a method id and may include a fact such as "
                                + "'now throws IllegalStateException'. Returns, per claim, whether the method is part of this analysis and whether "
                                + "the engine produced that fact for it. Do not state a claim as fact if it comes back false.",
                        schema("""
                                "analysisId": {"type": "string"},
                                "claims": {"type": "array", "items": {"type": "object", "properties": {
                                    "method": {"type": "string"}, "fact": {"type": "string"}}, "required": ["method"]}}""", "\"analysisId\", \"claims\""),
                        this::verify));
    }

    private Object analyze(Map<String, Object> args) {
        var path = workspace.resolve(str(args, "repo"));
        String base = workspace.revision("base", str(args, "base"));
        String head = workspace.revision("head", str(args, "head"));
        Analyzer.Result result;
        try {
            result = Analyzer.analyze(path, str(args, "repo"), base, head);
        } catch (IOException e) {
            throw new ToolError("Could not read those revisions from '" + str(args, "repo") + "': " + e.getMessage());
        }
        ChangeFacts facts = new ChangeFacts(result.changes(), result.impact());
        String id = "a" + counter.incrementAndGet();
        synchronized (analyses) {
            analyses.put(id, facts);
        }
        return Map.of("analysisId", id, "overview", facts.overview(), "tookMs", result.totalMs());
    }

    private Object blast(Map<String, Object> args) {
        ChangeFacts facts = facts(args);
        String ref = str(args, "method");
        List<String> matches = facts.resolve(ref).stream().filter(id -> facts.blastRadius(id).isPresent()).toList();
        if (matches.isEmpty()) {
            throw new ToolError("No changed method matching '" + ref + "' has a blast radius in this analysis. Use list_changes for ids.");
        }
        if (matches.size() > 1) {
            throw new ToolError("'" + ref + "' matches " + matches.size() + " methods; use one of: " + String.join(", ", matches.stream().limit(10).toList()));
        }
        return facts.blastRadius(matches.get(0)).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Object verify(Map<String, Object> args) {
        ChangeFacts facts = facts(args);
        Object raw = args.get("claims");
        if (!(raw instanceof List<?> claims) || claims.isEmpty()) {
            throw new ToolError("'claims' must be a non-empty list of {method, fact?} objects.");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object c : claims.stream().limit(MAX_ITEMS).toList()) {
            if (!(c instanceof Map<?, ?> m) || !(m.get("method") instanceof String method)) {
                throw new ToolError("Every claim needs a 'method' string.");
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("method", method);
            one.put("methodKnown", facts.isKnown(method));
            if (m.get("fact") instanceof String fact) {
                one.put("fact", fact);
                one.put("factTrue", facts.hasFact(method, fact));
            }
            results.add(one);
        }
        return Map.of("results", results);
    }

    private ChangeFacts facts(Map<String, Object> args) {
        String id = str(args, "analysisId");
        ChangeFacts f;
        synchronized (analyses) {
            f = id == null ? null : analyses.get(id);
        }
        if (f == null) {
            throw new ToolError("Unknown analysisId '" + id + "'. Call analyze_change first (only the last " + MAX_STORED_ANALYSES + " are kept).");
        }
        return f;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static <T> Map<String, Object> page(List<T> all) {
        List<T> shown = all.size() > MAX_ITEMS ? all.subList(0, MAX_ITEMS) : all;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", all.size());
        out.put("returned", shown.size());
        out.put("truncated", shown.size() < all.size());
        out.put("items", shown);
        return out;
    }

    private static String schema(String properties, String required) {
        return "{\"type\": \"object\", \"properties\": {" + properties + "}" + (required.isEmpty() ? "" : ", \"required\": [" + required + "]") + "}";
    }

    private SyncToolSpecification tool(String name, String description, String inputSchema, Function<Map<String, Object>, Object> body) {
        Tool tool = Tool.builder().name(name).description(description).inputSchema(mcpJson, inputSchema)
                .annotations(new ToolAnnotations(null, true, false, true, false, null)).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            try {
                Object result = body.apply(request.arguments());
                return CallToolResult.builder().addTextContent(json.writeValueAsString(result)).isError(false).build();
            } catch (ToolError | IllegalArgumentException e) {
                return CallToolResult.builder().addTextContent(e.getMessage()).isError(true).build();
            } catch (RuntimeException e) {
                return CallToolResult.builder().addTextContent("ChangeLens failed on this request: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage())).isError(true).build();
            }
        }).build();
    }
}
