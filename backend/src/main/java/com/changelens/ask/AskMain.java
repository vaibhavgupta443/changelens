package com.changelens.ask;

import com.changelens.core.Analyzer;
import com.changelens.core.history.HistoryIndex;
import com.changelens.core.history.MiniLmEmbedder;
import com.changelens.core.history.SemanticHistorySearch;
import com.changelens.core.history.SemanticHistorySearch.Mode;
import com.changelens.core.history.TextEmbedder;
import com.changelens.core.query.ChangeFacts;
import java.nio.file.Path;
import java.util.List;

/**
 * Ask a question about a change and get back only the claims the engine can confirm.
 *
 * <pre>{@code
 * export CHANGELENS_LLM_URL=https://openrouter.ai/api/v1      # or http://localhost:11434/v1 for Ollama
 * export CHANGELENS_LLM_MODEL=<model id>
 * export CHANGELENS_LLM_KEY=<key, if the server needs one>
 * java -cp changelens-mcp.jar com.changelens.ask.AskMain <repo folder> <base> <head> "what could break because of this change?"
 * }</pre>
 *
 * One analysis and one model request per question. The model sees the evidence text printed with {@code --show-evidence} (or alone, with
 * no model and no key, by {@code --evidence-only}), and nothing
 * else about your code.
 */
public final class AskMain {

    private AskMain() {
    }

    public static void main(String[] args) throws Exception {
        boolean showEvidence = List.of(args).contains("--show-evidence");
        boolean evidenceOnly = List.of(args).contains("--evidence-only");
        List<String> positional = List.of(args).stream().filter(a -> !a.startsWith("--")).toList();
        if (positional.size() != 4) {
            System.err.println("usage: AskMain <repo folder> <base> <head> \"question\" [--show-evidence | --evidence-only]");
            System.exit(2);
        }
        Path repo = Path.of(positional.get(0)).toAbsolutePath().normalize();
        String base = positional.get(1);
        String head = positional.get(2);
        String question = positional.get(3);

        ChatModel model = evidenceOnly ? new NoModel() : OpenAiCompatibleModel.fromEnvironment();
        Analyzer.Result result = Analyzer.analyze(repo, repo.getFileName().toString(), base, head);
        ChangeFacts facts = new ChangeFacts(result.changes(), result.impact());
        HistoryIndex baseHistory = HistoryIndex.build(repo, HistoryIndex.resolve(repo, base), 2000);
        HistoryIndex headHistory = HistoryIndex.build(repo, HistoryIndex.resolve(repo, head), 2000);

        TextEmbedder embedder = MiniLmEmbedder.tryCreate();
        SemanticHistorySearch semantic = embedder == null ? null : new SemanticHistorySearch(headHistory, embedder);
        GroundedAsker asker = new GroundedAsker(model, facts, baseHistory, headHistory,
                (q, n) -> semantic == null ? headHistory.search(q, n) : semantic.search(q, n, Mode.HYBRID));

        if (evidenceOnly) {
            System.out.println(asker.evidenceFor(question));
            return;
        }
        if (showEvidence) {
            System.out.println("=== evidence the model is given ===\n" + asker.evidenceFor(question) + "\n=== end of evidence ===\n");
        }
        GroundedAsker.Answer a = asker.ask(question);
        print(a);
    }

    /** Stands in for the model when only the evidence is wanted, so no key is needed and nothing is sent anywhere. */
    private static final class NoModel implements ChatModel {
        @Override
        public String complete(String system, String user) {
            throw new ModelException("--evidence-only never calls a model");
        }
    }

    static void print(GroundedAsker.Answer a) {
        System.out.println("Question: " + a.question() + "   (model: " + a.model() + ", evidence " + a.evidenceChars() + " characters)\n");
        if (!a.problem().isEmpty()) {
            System.out.println("PROBLEM: " + a.problem());
            return;
        }
        if (a.verified().isEmpty() && !a.unanswerable().isEmpty()) {
            System.out.println("The evidence does not answer this: " + a.unanswerable());
        }
        int i = 1;
        for (GroundedAsker.Claim c : a.verified()) {
            System.out.println(i++ + ". " + c.text());
            c.methods().forEach(m -> System.out.println("     method  " + m));
            c.facts().forEach(f -> System.out.println("     fact    " + f));
            c.commits().forEach(s -> System.out.println("     commit  " + s));
        }
        if (!a.rejected().isEmpty()) {
            System.out.println("\nDropped " + a.rejected().size() + " claim(s) that could not be verified:");
            for (GroundedAsker.Rejected r : a.rejected()) {
                System.out.println("  x " + r.claim().text() + "\n      because " + r.reason());
            }
        }
        System.out.println("\nVerified " + a.verified().size() + ", dropped " + a.rejected().size() + ".");
    }
}
