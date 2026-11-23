package com.changelens.ask;

import com.changelens.core.history.HistoryIndex;
import com.changelens.core.history.HistoryIndex.Entry;
import com.changelens.core.history.HistoryIndex.Hit;
import com.changelens.core.query.ChangeFacts;
import com.changelens.core.query.ChangeFacts.EntryFact;
import com.changelens.core.query.ChangeFacts.MethodFact;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers a question about a change with a language model, and lets through only what the engine can confirm.
 *
 * <p>Three steps. <b>Retrieve</b>: the evidence is gathered without any model, from the analysis (changed methods with their facts,
 * affected endpoints, untested changes) and from the history (earlier commits on the touched files, commits relevant to the question).
 * <b>Generate</b>: the model is asked for claims as data, each citing method ids, facts and commit ids copied from the evidence.
 * <b>Verify</b>: every citation is checked against the engine, and a claim with a citation that does not check out is dropped and
 * reported. The reader therefore sees only claims whose ids exist, whose facts the engine produced and whose commits are in the history.
 *
 * <p>What this does <em>not</em> guarantee: that a verified claim answers the question well, or that its wording is a fair summary of the
 * facts it cites. It guarantees that nothing in the answer was invented.
 */
public final class GroundedAsker {

    public record Claim(String text, List<String> methods, List<String> facts, List<String> commits) {
    }

    public record Rejected(Claim claim, String reason) {
    }

    /**
     * @param verified     claims whose every citation was confirmed
     * @param rejected     claims dropped, each with the reason
     * @param unanswerable why the model says the evidence cannot answer the question; empty when it answered
     * @param problem      set when the model's reply could not be used at all; empty otherwise
     */
    public record Answer(String question, String model, List<Claim> verified, List<Rejected> rejected, String unanswerable, String problem,
                         int evidenceChars) {
    }

    static final int MAX_METHODS = 25;
    static final int MAX_FACTS_PER_METHOD = 8;
    static final int MAX_ENDPOINTS = 10;
    static final int MAX_UNTESTED = 15;
    static final int MAX_FILES = 6;
    static final int MAX_COMMITS = 5;
    static final int MAX_CLAIMS = 8;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final String SYSTEM = """
            You explain a code change using ONLY the EVIDENCE in the user message. The evidence was computed from the code by a static analyser; \
            you add no knowledge of your own about this code.

            Reply with one JSON object and nothing else:
            {"claims":[{"text":"one plain sentence","methods":["method id"],"facts":["exact fact text"],"commits":["full commit sha"]}],"unanswerable":""}

            Rules:
            - Every claim must cite at least one method id or one commit sha, copied exactly from the evidence.
            - A fact must be copied exactly from the facts listed for a method that you cite in the same claim.
            - Do not state anything the evidence does not show. Do not guess intent, causes or risk beyond what a cited fact or commit message says.
            - If the evidence does not answer the question, return an empty claims list and put the reason in "unanswerable".
            - At most %d claims.""".formatted(MAX_CLAIMS);

    private final ChatModel model;
    private final ChangeFacts facts;
    private final HistoryIndex baseHistory;
    private final HistoryIndex headHistory;
    private final BiFunction<String, Integer, List<Hit>> search;

    /**
     * @param baseHistory history as of the commit the change started from (the change itself is not counted in it)
     * @param headHistory history as of the end of the change; a commit inside the change is a valid citation
     * @param search      finds commits relevant to the question (keyword, or keyword and meaning)
     */
    public GroundedAsker(ChatModel model, ChangeFacts facts, HistoryIndex baseHistory, HistoryIndex headHistory,
                         BiFunction<String, Integer, List<Hit>> search) {
        this.model = model;
        this.facts = facts;
        this.baseHistory = baseHistory;
        this.headHistory = headHistory;
        this.search = search;
    }

    public Answer ask(String question) {
        String evidence = evidence(question);
        String reply = model.complete(SYSTEM, "QUESTION: " + question.strip() + "\n\nEVIDENCE:\n" + evidence);
        return verify(question, reply, evidence.length());
    }

    /** The evidence text for a question, as the model will see it. */
    public String evidenceFor(String question) {
        return evidence(question);
    }

    // --- retrieve ---

    /** Everything the model may rely on, as plain text, with a hard size limit per section. */
    String evidence(String question) {
        StringBuilder sb = new StringBuilder();
        var o = facts.overview();
        sb.append("OVERVIEW: ").append(o.filesChanged()).append(" files changed; methods added ").append(o.methodsAdded())
                .append(", removed ").append(o.methodsRemoved()).append(", with new or changed logic ").append(o.methodsBehaviorChanged())
                .append(", declaration only ").append(o.methodsSignatureChanged()).append(", only reformatted or renamed locals ")
                .append(o.methodsCosmeticOnly() + o.methodsLocalRenameOnly()).append("; endpoints affected ").append(o.endpointsAffected())
                .append("; changed methods no test reaches ").append(o.changedWithoutTests()).append("; share of calls resolved ")
                .append(Math.round(o.resolvedCallRatio() * 100)).append("%.\n\n");

        List<MethodFact> methods = facts.changedMethods(null, null);
        sb.append("CHANGED METHODS (most important first, ").append(Math.min(methods.size(), MAX_METHODS)).append(" of ").append(methods.size()).append("):\n");
        for (MethodFact m : methods.stream().limit(MAX_METHODS).toList()) {
            sb.append("- id: ").append(m.id()).append("\n  nature: ").append(m.nature()).append(", file: ").append(m.file()).append('\n');
            for (var f : m.facts().stream().limit(MAX_FACTS_PER_METHOD).toList()) {
                sb.append("  fact: ").append(f.text()).append('\n');
            }
        }

        List<EntryFact> entries = facts.affectedEntryPoints();
        sb.append("\nENDPOINTS AND OTHER ENTRY POINTS WHOSE CALL PATH REACHES THE CHANGE (").append(entries.size()).append("):\n");
        for (EntryFact e : entries.stream().limit(MAX_ENDPOINTS).toList()) {
            sb.append("- ").append(e.label()).append(" reaches ")
                    .append(e.reached().stream().limit(3).map(r -> r.method()).collect(Collectors.joining(", "))).append('\n');
        }

        List<String> untested = facts.untestedChanges();
        sb.append("\nCHANGED METHODS NO TEST REACHES (").append(untested.size()).append("):\n");
        untested.stream().limit(MAX_UNTESTED).forEach(id -> sb.append("- ").append(id).append('\n'));

        sb.append("\nHISTORY OF THE TOUCHED FILES, before this change (a commit message that talks about a fix or revert is marked; that is a keyword check):\n");
        Map<String, Integer> files = new LinkedHashMap<>();
        methods.forEach(m -> files.merge(m.file(), 1, Integer::sum));
        for (String file : files.keySet().stream().limit(MAX_FILES).toList()) {
            List<Entry> past = baseHistory.historyOfFile(file, Integer.MAX_VALUE);
            sb.append("- ").append(file).append(": ").append(past.size()).append(" earlier commits, ")
                    .append(past.stream().filter(Entry::looksLikeFix).count()).append(" look like fixes\n");
            past.stream().limit(2).forEach(e -> sb.append("    commit ").append(e.sha()).append(' ').append(e.subject()).append('\n'));
        }

        sb.append("\nCOMMITS RELEVANT TO THE QUESTION:\n");
        List<Hit> hits = search.apply(question, MAX_COMMITS);
        if (hits.isEmpty()) {
            sb.append("(none found)\n");
        }
        for (Hit h : hits) {
            sb.append("- commit ").append(h.entry().sha()).append(' ').append(h.entry().when().toString(), 0, 10).append(' ')
                    .append(h.entry().subject()).append('\n');
        }
        return sb.toString();
    }

    // --- verify ---

    Answer verify(String question, String reply, int evidenceChars) {
        JsonNode root;
        try {
            int from = reply.indexOf('{');
            int to = reply.lastIndexOf('}');
            if (from < 0 || to <= from) {
                throw new IllegalArgumentException("no JSON object");
            }
            root = JSON.readTree(reply.substring(from, to + 1));
        } catch (RuntimeException e) {
            return new Answer(question, model.name(), List.of(), List.of(), "", "The model's reply was not the JSON that was asked for, so nothing could be checked.", evidenceChars);
        }
        List<Claim> verified = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        JsonNode claims = root.path("claims");
        int seen = 0;
        if (claims.isArray()) {
            for (JsonNode c : claims) {
                if (++seen > MAX_CLAIMS) {
                    break;
                }
                Claim claim = new Claim(c.path("text").asString("").strip(), strings(c.path("methods")), strings(c.path("facts")), strings(c.path("commits")));
                String why = problemWith(claim);
                if (why == null) {
                    verified.add(claim);
                } else {
                    rejected.add(new Rejected(claim, why));
                }
            }
        }
        return new Answer(question, model.name(), List.copyOf(verified), List.copyOf(rejected), root.path("unanswerable").asString("").strip(), "", evidenceChars);
    }

    /** Why this claim cannot be let through, or null when every citation checks out. */
    private String problemWith(Claim c) {
        if (c.text().isEmpty()) {
            return "the claim has no text";
        }
        if (c.methods().isEmpty() && c.commits().isEmpty()) {
            return "it cites no method and no commit";
        }
        if (!c.facts().isEmpty() && c.methods().isEmpty()) {
            return "it quotes a fact without naming the method it belongs to";
        }
        for (String m : c.methods()) {
            if (!facts.isKnown(m)) {
                return "it cites a method that is not in this change: " + m;
            }
        }
        for (String fact : c.facts()) {
            boolean produced = c.methods().stream().anyMatch(m -> facts.hasFact(m, fact));
            if (!produced) {
                return "the engine did not produce this fact for the cited method(s): \"" + fact + "\"";
            }
        }
        for (String sha : c.commits()) {
            if (!headHistory.hasCommit(sha)) {
                return "it cites a commit that is not in the history: " + sha;
            }
        }
        return null;
    }

    private static List<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array.isArray()) {
            array.forEach(n -> {
                String s = n.asString("").strip();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            });
        }
        return List.copyOf(out);
    }
}
