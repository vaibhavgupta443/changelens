package com.changelens.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.ImpactAnalyzer;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.SourceFile;
import com.changelens.core.query.ChangeFacts;
import com.changelens.core.query.ChangeFacts.EntryFact;
import com.changelens.core.query.ChangeFacts.MethodFact;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fact layer is what a person, an MCP client or a language model asks about a change. These tests pin down that what it says is
 * what the engine found, and that invented citations and invented facts are rejected.
 */
class ChangeFactsTest {

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
                public String show(@PathVariable String id) {
                    return service.describe(id);
                }

                @PostMapping("/{id}/cancel")
                public String cancel(@PathVariable String id) {
                    return service.cancel(id);
                }

                @GetMapping("/health")
                public String health() { return "ok"; }
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

                public String describe(String id) {
                    return "order " + repository.findStatus(id);
                }

                public String cancel(String id) {
                    String status = repository.findStatus(id);
                    repository.markCancelled(id);
                    return status;
                }
            }
            """;

    private static final String REPO_IFACE = """
            package com.acme.repo;

            public interface OrderRepository {
                String findStatus(String id);
                void markCancelled(String id);
            }
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

                private String lookup(String id) {
                    return "OPEN";
                }
            }
            """;

    private static final String TEST = """
            package com.acme.web;

            import com.acme.order.OrderService;
            import org.junit.jupiter.api.Test;

            class OrderServiceTest {
                @Test
                void describesOrders() {
                    OrderService s = new OrderService(null);
                    s.describe("1");
                }
            }
            """;

    private static Snapshot snap(String ref, String impl) {
        List<SourceFile> files = new ArrayList<>(List.of(
                new SourceFile("src/main/java/com/acme/web/OrderController.java", CONTROLLER),
                new SourceFile("src/main/java/com/acme/order/OrderService.java", SERVICE),
                new SourceFile("src/main/java/com/acme/repo/OrderRepository.java", REPO_IFACE),
                new SourceFile("src/main/java/com/acme/repo/JdbcOrderRepository.java", impl),
                new SourceFile("src/test/java/com/acme/web/OrderServiceTest.java", TEST)));
        return JavaSnapshotBuilder.build("demo", ref, files, List.of());
    }

    /** findStatus gains a null check that throws, and markCancelled gains a new call. */
    private static ChangeFacts facts() {
        String v2 = IMPL_V1
                .replace("String s = lookup(id);\n        return s;",
                        "String s = lookup(id);\n        if (s == null) { throw new IllegalStateException(\"missing\"); }\n        return s;")
                .replace("System.out.println(s);", "System.out.println(s);\n        audit(id);")
                .replace("private String lookup", "private void audit(String id) { }\n\n    private String lookup");
        Snapshot head = snap("b", v2);
        ChangeSet cs = SnapshotDiffer.diff(snap("a", IMPL_V1), head);
        ImpactReport impact = ImpactAnalyzer.analyze(head, cs);
        return new ChangeFacts(cs, impact);
    }

    private static MethodFact method(List<MethodFact> all, String idPart) {
        return all.stream().filter(m -> m.id().contains(idPart)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + idPart + " in " + all.stream().map(MethodFact::id).toList()));
    }

    @Test
    void behaviourChangesCarryTheCompleteListOfWhatChanged() {
        MethodFact find = method(facts().changedMethods("BEHAVIOR", null), "JdbcOrderRepository#findStatus");

        assertThat(find.nature()).isEqualTo("BEHAVIOR");
        assertThat(find.facts()).extracting(ChangeFacts.Fact::kind).contains("branch-added", "throws-added");
        assertThat(find.facts()).extracting(ChangeFacts.Fact::text).contains("now throws IllegalStateException");
        assertThat(find.file()).endsWith("JdbcOrderRepository.java");
        assertThat(find.startLine()).isPositive();
    }

    @Test
    void aNewCallIsReportedAsACall() {
        MethodFact cancelled = method(facts().changedMethods(null, null), "JdbcOrderRepository#markCancelled");

        assertThat(cancelled.facts()).extracting(ChangeFacts.Fact::kind).contains("calls-added");
        assertThat(cancelled.facts()).extracting(ChangeFacts.Fact::text).anyMatch(t -> t.contains("audit"));
    }

    @Test
    void changesAreFilteredByNatureAndTypeAndOrderedByImportance() {
        ChangeFacts f = facts();

        assertThat(f.changedMethods("behavior", null)).allMatch(m -> m.nature().equals("BEHAVIOR"));
        assertThat(f.changedMethods(null, "jdbc")).allMatch(m -> m.type().contains("JdbcOrderRepository"));
        assertThat(f.changedMethods(null, "NoSuchType")).isEmpty();
        List<String> natures = f.changedMethods(null, null).stream().map(MethodFact::nature).toList();
        assertThat(natures.get(0)).isEqualTo("BEHAVIOR");
    }

    @Test
    void anUnknownNatureIsRejectedWithTheValidChoices() {
        assertThatThrownBy(() -> facts().changedMethods("SCARY", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BEHAVIOR").hasMessageContaining("SCARY");
    }

    @Test
    void endpointsThatReachTheChangeAreListedWithTheirPath() {
        List<EntryFact> entries = facts().affectedEntryPoints();

        assertThat(entries).extracting(EntryFact::label).containsExactlyInAnyOrder("GET /orders/{id}", "POST /orders/{id}/cancel");
        EntryFact show = entries.stream().filter(e -> e.label().equals("GET /orders/{id}")).findFirst().orElseThrow();
        assertThat(show.reached()).isNotEmpty();
        assertThat(show.reached().get(0).path()).hasSizeGreaterThan(1);
    }

    @Test
    void everyIdItReturnsIsAValidCitation() {
        ChangeFacts f = facts();

        f.changedMethods(null, null).forEach(m -> assertThat(f.isKnown(m.id())).as(m.id()).isTrue());
        f.affectedEntryPoints().forEach(e -> {
            assertThat(f.isKnown(e.entryMethod())).isTrue();
            e.reached().forEach(r -> r.path().forEach(p -> assertThat(f.isKnown(p)).as(p).isTrue()));
        });
    }

    @Test
    void inventedCitationsAndInventedFactsAreRejected() {
        ChangeFacts f = facts();
        String real = method(f.changedMethods(null, null), "JdbcOrderRepository#findStatus").id();

        assertThat(f.isKnown("com.acme.pay.PaymentGateway#charge(long)")).isFalse();
        assertThat(f.isKnown(null)).isFalse();
        assertThat(f.hasFact(real, "now throws IllegalStateException")).isTrue();
        assertThat(f.hasFact(real, "  NOW THROWS illegalstateexception ")).isTrue();
        assertThat(f.hasFact(real, "now calls PaymentGateway.charge")).isFalse();
        assertThat(f.hasFact("com.acme.pay.PaymentGateway#charge(long)", "now throws IllegalStateException")).isFalse();
    }

    @Test
    void aPartialReferenceResolvesToTheMatchingMethods() {
        ChangeFacts f = facts();

        assertThat(f.resolve("JdbcOrderRepository#findStatus")).singleElement().asString().contains("findStatus");
        assertThat(f.resolve("findStatus")).isNotEmpty().allMatch(id -> id.contains("findStatus"));
        assertThat(f.resolve("nothingLikeThis")).isEmpty();
        assertThat(f.resolve("  ")).isEmpty();
    }

    @Test
    void changedMethodsNoTestReachesAreListedAndTheBlastRadiusNamesTheCallers() {
        ChangeFacts f = facts();

        assertThat(f.untestedChanges()).anyMatch(id -> id.contains("markCancelled"));
        String cancelled = f.untestedChanges().stream().filter(id -> id.contains("markCancelled")).findFirst().orElseThrow();
        var blast = f.blastRadius(cancelled).orElseThrow();
        assertThat(blast.unchangedCallers()).anyMatch(c -> c.contains("OrderService#cancel"));
        assertThat(f.blastRadius("com.acme.Nothing#x()")).isEmpty();
    }

    @Test
    void theOverviewMatchesTheDetail() {
        ChangeFacts f = facts();

        assertThat(f.overview().methodsBehaviorChanged()).isEqualTo(f.changedMethods("BEHAVIOR", null).size());
        assertThat(f.overview().endpointsAffected()).isEqualTo(f.affectedEntryPoints().size());
        assertThat(f.overview().changedWithoutTests()).isEqualTo(f.untestedChanges().size());
    }
}
