package com.changelens.mcp;

import com.changelens.core.Analyzer;
import com.changelens.core.history.HistoryIndex;
import com.changelens.core.history.HistoryIndex.Hit;
import com.changelens.core.history.MiniLmEmbedder;
import com.changelens.core.history.SemanticHistorySearch;
import com.changelens.core.history.SemanticHistorySearch.Mode;
import com.changelens.core.history.TextEmbedder;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
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
    private static final int MAX_STORED_HISTORIES = 5;
    /** How far back the history tools read. The answers say how far that was, so a client never mistakes a window for the whole past. */
    private static final int HISTORY_COMMITS = 2000;

    private final Workspace workspace;
    private final JsonMapper json = JsonMapper.builder().build();
    private final McpJsonMapper mcpJson = McpJsonDefaults.getMapper();
    private final AtomicInteger counter = new AtomicInteger();
    /** Recent analyses by id, least recently used dropped first, so a long session cannot grow without limit. */
    private final Map<String, Run> analyses = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Run> eldest) {
            return size() > MAX_STORED_ANALYSES;
        }
    };
    /** History indexes by repository and the exact commit they were built from, so a new commit is never answered from a stale index. */
    private final Map<String, HistoryIndex> histories = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HistoryIndex> eldest) {
            return size() > MAX_STORED_HISTORIES;
        }
    };

    /** One kept analysis: the facts, and the repository and the exact commit the change started from (history is read as of then). */
    private record Run(ChangeFacts facts, String repo, String baseSha, String headSha) {
    }

    private final Supplier<TextEmbedder> embedderSupplier;
    private TextEmbedder embedder;
    private boolean embedderLoaded;
    private final Map<String, SemanticHistorySearch> searches = new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, SemanticHistorySearch> eldest) {
            return size() > MAX_STORED_HISTORIES;
        }
    };

    public ChangeLensMcpServer(java.nio.file.Path workspaceRoot) {
        this(workspaceRoot, MiniLmEmbedder::tryCreate);
    }

    /** @param embedderSupplier loads the embedding model the first time meaning is needed; may return null when it is not available */
    public ChangeLensMcpServer(java.nio.file.Path workspaceRoot, Supplier<TextEmbedder> embedderSupplier) {
        this.workspace = new Workspace(workspaceRoot);
        this.embedderSupplier = embedderSupplier;
    }

    /** The embedding model, loaded once on first use; null when this build does not include it. */
    private synchronized TextEmbedder embedder() {
        if (!embedderLoaded) {
            embedder = embedderSupplier.get();
            embedderLoaded = true;
        }
        return embedder;
    }

    private synchronized SemanticHistorySearch searchOf(String key, HistoryIndex index) {
        return searches.computeIfAbsent(key, k -> new SemanticHistorySearch(index, embedder()));
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

                tool("search_history",
                        "Search the commit history of a repository with a question or keywords, for example 'when did we add progress "
                                + "tracking for model pulls' or 'null handling in greet'. Returns real commits (sha, date, subject, files) ranked "
                                + "by relevance; nothing is generated. Only the newest " + HISTORY_COMMITS + " commits are searched, and the answer says how far back that reaches.",
                        schema("""
                                "repo": {"type": "string", "description": "Repository folder name inside the workspace"},
                                "query": {"type": "string", "description": "A question or some words, e.g. 'why was the retry removed'"},
                                "limit": {"type": "integer", "description": "How many commits to return, 1 to 20 (default 5)"},
                                "mode": {"type": "string", "description": "keyword, semantic (by meaning) or hybrid (default). Meaning-based modes need the semantic build; without it the search falls back to keywords and says so"},
                                "revision": {"type": "string", "description": "Optional: search the history as of this revision (default HEAD)"}""",
                                "\"repo\", \"query\""),
                        this::searchHistory),

                tool("change_history",
                        "For the files an analysed change touches: how many earlier commits touched each file, how many of those messages look like "
                                + "fixes or reverts, and the most recent ones. Use it to tell a reviewer that a file is a repeat trouble spot. "
                                + "History is read as of the commit the change started from, so the change itself is not counted.",
                        schema("\"analysisId\": {\"type\": \"string\"}", "\"analysisId\""),
                        this::changeHistory),

                tool("verify_claims",
                        "Check statements before you make them. Each claim names a method id and/or a commit sha, and may include a fact such as "
                                + "'now throws IllegalStateException'. Returns, per claim, whether the method is part of this analysis, whether the "
                                + "engine produced that fact for it, and whether the commit exists in the repository's history. "
                                + "Do not state a claim as fact if it comes back false.",
                        schema("""
                                "analysisId": {"type": "string"},
                                "claims": {"type": "array", "items": {"type": "object", "properties": {
                                    "method": {"type": "string"}, "fact": {"type": "string"}, "commit": {"type": "string"}}}}""",
                                "\"analysisId\", \"claims\""),
                        this::verify));
    }

    private Object analyze(Map<String, Object> args) {
        var path = workspace.resolve(str(args, "repo"));
        String base = workspace.revision("base", str(args, "base"));
        String head = workspace.revision("head", str(args, "head"));
        Analyzer.Result result;
        String baseSha;
        String headSha;
        try {
            baseSha = HistoryIndex.resolve(path, base);
            headSha = HistoryIndex.resolve(path, head);
            result = Analyzer.analyze(path, str(args, "repo"), base, head);
        } catch (IOException e) {
            throw new ToolError("Could not read those revisions from '" + str(args, "repo") + "': " + e.getMessage());
        }
        ChangeFacts facts = new ChangeFacts(result.changes(), result.impact());
        String id = "a" + counter.incrementAndGet();
        synchronized (analyses) {
            analyses.put(id, new Run(facts, str(args, "repo"), baseSha, headSha));
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
        Run run = run(args);
        ChangeFacts facts = run.facts();
        Object raw = args.get("claims");
        if (!(raw instanceof List<?> claims) || claims.isEmpty()) {
            throw new ToolError("'claims' must be a non-empty list of {method?, fact?, commit?} objects.");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object c : claims.stream().limit(MAX_ITEMS).toList()) {
            if (!(c instanceof Map<?, ?> m) || !(m.get("method") instanceof String || m.get("commit") instanceof String)) {
                throw new ToolError("Every claim needs a 'method' and/or a 'commit' string.");
            }
            Map<String, Object> one = new LinkedHashMap<>();
            if (m.get("method") instanceof String method) {
                one.put("method", method);
                one.put("methodKnown", facts.isKnown(method));
                if (m.get("fact") instanceof String fact) {
                    one.put("fact", fact);
                    one.put("factTrue", facts.hasFact(method, fact));
                }
            }
            if (m.get("commit") instanceof String commit) {
                one.put("commit", commit);
                one.put("commitKnown", history(run.repo(), run.headSha()).hasCommit(commit));
            }
            results.add(one);
        }
        return Map.of("results", results);
    }

    private ChangeFacts facts(Map<String, Object> args) {
        return run(args).facts();
    }

    private Run run(Map<String, Object> args) {
        String id = str(args, "analysisId");
        Run r;
        synchronized (analyses) {
            r = id == null ? null : analyses.get(id);
        }
        if (r == null) {
            throw new ToolError("Unknown analysisId '" + id + "'. Call analyze_change first (only the last " + MAX_STORED_ANALYSES + " are kept).");
        }
        return r;
    }

    /** The history of a repository as of one commit, built once and reused until that commit stops being the one asked for. */
    private HistoryIndex history(String repo, String sha) {
        String key = repo + "@" + sha;
        synchronized (histories) {
            HistoryIndex cached = histories.get(key);
            if (cached != null) {
                return cached;
            }
        }
        HistoryIndex built;
        try {
            built = HistoryIndex.build(workspace.resolve(repo), sha, HISTORY_COMMITS);
        } catch (IOException e) {
            throw new ToolError("Could not read the history of '" + repo + "': " + e.getMessage());
        }
        synchronized (histories) {
            histories.put(key, built);
        }
        return built;
    }

    private Object searchHistory(Map<String, Object> args) {
        var path = workspace.resolve(str(args, "repo"));
        String query = str(args, "query");
        if (query == null) {
            throw new ToolError("'query' must be a question or some words to look for in the commit history.");
        }
        int limit = args.get("limit") instanceof Number n ? Math.max(1, Math.min(n.intValue(), 20)) : 5;
        String rev = str(args, "revision") == null ? "HEAD" : workspace.revision("revision", str(args, "revision"));
        String sha;
        try {
            sha = HistoryIndex.resolve(path, rev);
        } catch (IOException e) {
            throw new ToolError("Could not read revision '" + rev + "' of '" + str(args, "repo") + "': " + e.getMessage());
        }
        HistoryIndex index = history(str(args, "repo"), sha);
        Mode requested = parseMode(str(args, "mode"));
        Mode used = requested;
        String note = null;
        if (requested != Mode.KEYWORD && embedder() == null) {
            used = Mode.KEYWORD;
            note = "Meaning-based search needs the embedding model, which this build does not include "
                    + "(use changelens-mcp-semantic.jar). Searched by keywords only.";
        }
        List<Hit> found = used == Mode.KEYWORD ? index.search(query, limit)
                : searchOf(str(args, "repo") + "@" + sha, index).search(query, limit, used);
        List<Map<String, Object>> results = found.stream().map(h -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("commit", h.entry().sha());
            one.put("date", h.entry().when().toString().substring(0, 10));
            one.put("subject", h.entry().subject());
            one.put("files", h.entry().files().stream().limit(8).toList());
            one.put("filesTotal", h.entry().files().size() + (h.entry().filesTruncated() ? "+" : ""));
            one.put("matchedWords", h.matchedTerms());
            one.put("score", Math.round(h.score() * 100) / 100.0);
            return one;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", used.name().toLowerCase(Locale.ROOT));
        if (note != null) {
            body.put("note", note);
        }
        body.put("results", results);
        return historyAnswer(index, sha, body);
    }

    private static Mode parseMode(String mode) {
        if (mode == null) {
            return Mode.HYBRID;
        }
        try {
            return Mode.valueOf(mode.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ToolError("'mode' must be keyword, semantic or hybrid.");
        }
    }

    /** For each file a change touches: how often it changed before, and the earlier commits whose message talks about a fix or revert. */
    private Object changeHistory(Map<String, Object> args) {
        Run run = run(args);
        HistoryIndex index = history(run.repo(), run.baseSha());
        Map<String, List<String>> methodsByFile = new LinkedHashMap<>();
        run.facts().changedMethods(null, null).stream().filter(m -> !m.nature().equals("COSMETIC") && !m.nature().equals("LOCAL_RENAME"))
                .forEach(m -> methodsByFile.computeIfAbsent(m.file(), f -> new ArrayList<>()).add(m.id()));
        List<Map<String, Object>> files = new ArrayList<>();
        methodsByFile.forEach((file, methods) -> {
            List<HistoryIndex.Entry> past = index.historyOfFile(file, Integer.MAX_VALUE);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("file", file);
            one.put("changedMethods", methods);
            one.put("earlierCommitsTouchingFile", past.size());
            one.put("earlierCommitsLookingLikeFixes", past.stream().filter(HistoryIndex.Entry::looksLikeFix).count());
            one.put("mostRecent", past.stream().limit(5).map(e -> Map.of("commit", e.sha(), "date", e.when().toString().substring(0, 10),
                    "subject", e.subject(), "looksLikeFix", e.looksLikeFix())).toList());
            files.add(one);
        });
        files.sort((x, y) -> Long.compare((Long) y.get("earlierCommitsLookingLikeFixes"), (Long) x.get("earlierCommitsLookingLikeFixes")));
        return historyAnswer(index, run.baseSha(), Map.of("files", files, "note",
                "'looksLikeFix' is a keyword check on the commit message (fix, bug, revert, regression, crash); it is a hint, not a verdict."));
    }

    private Map<String, Object> historyAnswer(HistoryIndex index, String sha, Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>(body);
        out.put("history", Map.of("asOfCommit", sha, "commitsIndexed", index.size(),
                "oldestIndexed", index.oldest() == null ? "" : index.oldest().toString().substring(0, 10),
                "window", "the newest " + HISTORY_COMMITS + " non-merge commits; older history is not searched"));
        return out;
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
