package com.changelens.ask;

import static org.assertj.core.api.Assertions.assertThat;

import com.changelens.ask.GroundedAsker.Answer;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.ImpactAnalyzer;
import com.changelens.core.history.HistoryIndex;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.SourceFile;
import com.changelens.core.query.ChangeFacts;
import com.changelens.core.query.ChangeFacts.MethodFact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model is scripted, so every outcome is exact. What is tested is the gate: a claim is let through only when every id, fact and
 * commit it cites is confirmed by the engine, and everything else is dropped with the reason.
 */
class GroundedAskerTest {

    private static final String JDBC = "src/main/java/com/acme/repo/JdbcOrderRepository.java";

    private static final String CONTROLLER = """
            package com.acme.web;
            import com.acme.order.OrderService;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/orders")
            public class OrderController {
                private final OrderService service;
                public OrderController(OrderService service) { this.service = service; }
                @GetMapping("/{id}")
                public String show(@PathVariable String id) { return service.describe(id); }
            }
            """;
    private static final String SERVICE = """
            package com.acme.order;
            import com.acme.repo.OrderRepository;
            import org.springframework.stereotype.Service;
            @Service
            public class OrderService {
                private final OrderRepository repository;
                public OrderService(OrderRepository repository) { this.repository = repository; }
                public String describe(String id) { return "order " + repository.findStatus(id); }
            }
            """;
    private static final String IFACE = """
            package com.acme.repo;
            public interface OrderRepository { String findStatus(String id); void markCancelled(String id); }
            """;
    private static final String IMPL_V1 = """
            package com.acme.repo;
            public class JdbcOrderRepository implements OrderRepository {
                public String findStatus(String id) {
                    String s = lookup(id);
                    return s;
                }
                public void markCancelled(String id) {
                    String s = lookup(id);
                    System.out.println(s);
                }
                private String lookup(String id) { return "OPEN"; }
            }
            """;

    @TempDir
    static Path dir;

    static ChangeFacts facts;
    static HistoryIndex baseHistory;
    static HistoryIndex headHistory;
    static String shaAdd;
    static String shaFix;
    static String shaChange;
    static String findStatusId;
    static String findStatusFact;
    static String markCancelledId;

    private static Snapshot snap(String ref, String impl) {
        List<SourceFile> files = new ArrayList<>(List.of(
                new SourceFile("src/main/java/com/acme/web/OrderController.java", CONTROLLER),
                new SourceFile("src/main/java/com/acme/order/OrderService.java", SERVICE),
                new SourceFile("src/main/java/com/acme/repo/OrderRepository.java", IFACE),
                new SourceFile(JDBC, impl)));
        return JavaSnapshotBuilder.build("demo", ref, files, List.of());
    }

    @BeforeAll
    static void build() throws Exception {
        String v2 = IMPL_V1.replace("String s = lookup(id);\n        return s;", "String s = lookup(id);\n        if (s == null) { throw new IllegalStateException(\"missing\"); }\n        return s;")
                .replace("System.out.println(s);", "System.out.println(s);\n        audit(id);")
                .replace("private String lookup", "private void audit(String id) { }\n    private String lookup");
        Snapshot head = snap("b", v2);
        ChangeSet cs = SnapshotDiffer.diff(snap("a", IMPL_V1), head);
        ImpactReport impact = ImpactAnalyzer.analyze(head, cs);
        facts = new ChangeFacts(cs, impact);
        MethodFact find = facts.changedMethods("BEHAVIOR", null).stream().filter(m -> m.id().contains("findStatus")).findFirst().orElseThrow();
        findStatusId = find.id();
        findStatusFact = find.facts().stream().filter(f -> f.kind().equals("throws-added")).findFirst().orElseThrow().text();
        markCancelledId = facts.changedMethods("BEHAVIOR", null).stream().filter(m -> m.id().contains("markCancelled")).findFirst().orElseThrow().id();

        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            shaAdd = commit(git, 1, "Add JdbcOrderRepository");
            shaFix = commit(git, 2, "Fix crash when the status row is missing");
            shaChange = commit(git, 3, "Throw when the status is null");
        }
        baseHistory = HistoryIndex.build(dir, shaFix, 100);
        headHistory = HistoryIndex.build(dir, shaChange, 100);
    }

    private static String commit(Git git, int day, String subject) throws Exception {
        Path f = dir.resolve(JDBC);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "class X { int v = " + day + "; }");
        git.add().addFilepattern(".").call();
        PersonIdent who = new PersonIdent("T", "t@example.com", new Date(1_700_000_000_000L + day * 86_400_000L), TimeZone.getTimeZone("UTC"));
        return git.commit().setMessage(subject).setAuthor(who).setCommitter(who).call().getName();
    }

    private static Answer ask(String reply) {
        return asker(reply, new AtomicReference<>(), new AtomicReference<>()).ask("what could break?");
    }

    private static GroundedAsker asker(String reply, AtomicReference<String> sawSystem, AtomicReference<String> sawUser) {
        ChatModel scripted = new ChatModel() {
            @Override
            public String complete(String system, String user) {
                sawSystem.set(system);
                sawUser.set(user);
                return reply;
            }

            @Override
            public String name() {
                return "scripted";
            }
        };
        return new GroundedAsker(scripted, facts, baseHistory, headHistory, (q, n) -> headHistory.search("status", n));
    }

    private static String claim(String text, List<String> methods, List<String> factsText, List<String> commits) {
        var m = tools.jackson.databind.json.JsonMapper.builder().build();
        return m.writeValueAsString(java.util.Map.of("text", text, "methods", methods, "facts", factsText, "commits", commits));
    }

    private static String reply(String... claims) {
        return "{\"claims\":[" + String.join(",", claims) + "],\"unanswerable\":\"\"}";
    }

    // --- what the model is given ---

    @Test
    void theEvidenceHoldsTheIdsFactsEndpointsAndCommitsAModelMayCite() {
        String evidence = asker("{}", new AtomicReference<>(), new AtomicReference<>()).evidenceFor("what could break?");

        assertThat(evidence).contains(findStatusId).contains(findStatusFact).contains("GET /orders/{id}").contains("NO TEST REACHES")
                .contains(shaFix).contains(JDBC).contains("2 earlier commits").contains("1 look like fixes");
        assertThat(evidence.length()).isLessThan(20_000);
    }

    @Test
    void theModelIsToldToUseOnlyTheEvidenceAndIsCalledOnce() {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();

        asker(reply(), system, user).ask("what could break?");

        assertThat(system.get()).contains("ONLY the EVIDENCE").contains("copied exactly").contains("unanswerable");
        assertThat(user.get()).startsWith("QUESTION: what could break?").contains("EVIDENCE:").contains(findStatusId);
    }

    // --- what is let through ---

    @Test
    void claimsWhoseEveryCitationChecksOutAreLetThrough() {
        Answer a = ask(reply(
                claim("findStatus now throws when the status is missing", List.of(findStatusId), List.of(findStatusFact), List.of()),
                claim("This file had a crash fix before", List.of(), List.of(), List.of(shaFix))));

        assertThat(a.verified()).hasSize(2);
        assertThat(a.rejected()).isEmpty();
        assertThat(a.problem()).isEmpty();
        assertThat(a.model()).isEqualTo("scripted");
    }

    @Test
    void aCommitInsideTheChangeItselfIsAValidCitation() {
        Answer a = ask(reply(claim("The change was made by this commit", List.of(), List.of(), List.of(shaChange))));

        assertThat(a.verified()).hasSize(1); // base history lacks it, head history has it
        assertThat(baseHistory.hasCommit(shaChange)).isFalse();
    }

    @Test
    void anInventedMethodIsDroppedWithTheReason() {
        Answer a = ask(reply(claim("PaymentGateway.charge now retries", List.of("com.acme.pay.PaymentGateway#charge(long)"), List.of(), List.of())));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected()).singleElement().satisfies(r -> assertThat(r.reason()).contains("not in this change").contains("PaymentGateway"));
    }

    @Test
    void anInventedFactAboutARealMethodIsDropped() {
        Answer a = ask(reply(claim("findStatus now calls the payment service", List.of(findStatusId), List.of("now calls PaymentGateway.charge"), List.of())));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected().get(0).reason()).contains("did not produce this fact");
    }

    @Test
    void aTrueFactAttachedToTheWrongMethodIsDropped() {
        // the fact is real, but for findStatus; citing only markCancelled for it is a misattribution
        Answer a = ask(reply(claim("markCancelled throws", List.of(markCancelledId), List.of(findStatusFact), List.of())));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected().get(0).reason()).contains("did not produce this fact");
    }

    @Test
    void anInventedCommitIsDropped() {
        Answer a = ask(reply(claim("A hotfix landed last week", List.of(), List.of(), List.of("0123456789abcdef0123456789abcdef01234567"))));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected().get(0).reason()).contains("not in the history");
    }

    @Test
    void aClaimThatCitesNothingIsDroppedHoweverPlausible() {
        Answer a = ask(reply(claim("This change is probably safe", List.of(), List.of(), List.of())));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected().get(0).reason()).contains("cites no method and no commit");
    }

    @Test
    void aFactWithoutItsMethodIsDropped() {
        Answer a = ask(reply(claim("something throws", List.of(), List.of(findStatusFact), List.of(shaFix))));

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected().get(0).reason()).contains("without naming the method");
    }

    @Test
    void goodAndBadClaimsInOneReplyAreSeparated() {
        Answer a = ask(reply(
                claim("findStatus now throws", List.of(findStatusId), List.of(findStatusFact), List.of()),
                claim("made up", List.of("nope.Nothing#x()"), List.of(), List.of()),
                claim("history shows a fix", List.of(), List.of(), List.of(shaFix))));

        assertThat(a.verified()).extracting(GroundedAsker.Claim::text).containsExactly("findStatus now throws", "history shows a fix");
        assertThat(a.rejected()).extracting(r -> r.claim().text()).containsExactly("made up");
    }

    // --- the model's reply ---

    @Test
    void aReplyWrappedInFencesAndProseIsStillRead() {
        String body = reply(claim("findStatus now throws", List.of(findStatusId), List.of(findStatusFact), List.of()));

        Answer a = ask("Sure! Here is the answer:\n```json\n" + body + "\n```\nHope that helps.");

        assertThat(a.verified()).hasSize(1);
    }

    @Test
    void aReplyThatIsNotJsonIsReportedAsAProblemAndNothingIsClaimed() {
        Answer a = ask("The change looks fine to me, nothing to worry about.");

        assertThat(a.verified()).isEmpty();
        assertThat(a.problem()).contains("not the JSON");
        assertThat(ask("{ this is not valid json }").problem()).contains("not the JSON");
    }

    @Test
    void anAnswerThatSaysTheEvidenceIsNotEnoughIsPassedOnAsThat() {
        Answer a = ask("{\"claims\":[],\"unanswerable\":\"The evidence says nothing about performance.\"}");

        assertThat(a.verified()).isEmpty();
        assertThat(a.unanswerable()).contains("performance");
        assertThat(a.problem()).isEmpty();
    }

    @Test
    void moreClaimsThanAllowedAreCutAndDuplicateCitationsAreMerged() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(claim("claim " + i, List.of(findStatusId, findStatusId), List.of(), List.of()));
        }

        Answer a = ask(reply(many.toArray(String[]::new)));

        assertThat(a.verified()).hasSize(GroundedAsker.MAX_CLAIMS);
        assertThat(a.verified().get(0).methods()).containsExactly(findStatusId);
    }

    @Test
    void missingFieldsDoNotCrashTheGate() {
        Answer a = ask("{\"claims\":[{\"text\":\"no citations at all\"},{\"methods\":[\"x\"]},{}]}");

        assertThat(a.verified()).isEmpty();
        assertThat(a.rejected()).hasSize(3);
    }
}
