package com.changelens.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.flow.FlowModel.FlowImpact;
import com.changelens.core.flow.FlowModel.ImpactReport;
import com.changelens.core.flow.ImpactAnalyzer;
import com.changelens.core.flow.SpringEntryPoints;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.SourceFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which existing features does a change run through, and who depends on it? */
class FlowImpactTest {

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

    private static final String REPO_IMPL_V1 = """
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

    private static Snapshot snap(String ref, String implSource) {
        List<SourceFile> files = new ArrayList<>(List.of(
                new SourceFile("src/main/java/com/acme/web/OrderController.java", CONTROLLER),
                new SourceFile("src/main/java/com/acme/order/OrderService.java", SERVICE),
                new SourceFile("src/main/java/com/acme/repo/OrderRepository.java", REPO_IFACE),
                new SourceFile("src/main/java/com/acme/repo/JdbcOrderRepository.java", implSource),
                new SourceFile("src/test/java/com/acme/web/OrderServiceTest.java", TEST)));
        return JavaSnapshotBuilder.build("demo", ref, files, List.of());
    }

    @Test
    void findsHttpEntryPointsWithCombinedPaths() {
        var eps = SpringEntryPoints.find(snap("a", REPO_IMPL_V1));
        assertThat(eps).extracting(e -> e.label()).containsExactlyInAnyOrder("GET /orders/{id}", "POST /orders/{id}/cancel", "GET /orders/health");
    }

    @Test
    void aChangeInAnInterfaceImplementationIsTracedBackToTheEndpointsThatReachIt() {
        String v2 = REPO_IMPL_V1.replace("String s = lookup(id);\n        return s;", "String s = lookup(id);\n        if (s == null) { throw new IllegalStateException(\"missing\"); }\n        return s;");
        Snapshot base = snap("a", REPO_IMPL_V1);
        Snapshot head = snap("b", v2);
        ChangeSet cs = SnapshotDiffer.diff(base, head);
        ImpactReport report = ImpactAnalyzer.analyze(head, cs);

        assertThat(report.flows()).extracting(f -> f.entry().label())
                .containsExactlyInAnyOrder("GET /orders/{id}", "POST /orders/{id}/cancel");
        assertThat(report.flows()).extracting(f -> f.entry().label()).doesNotContain("GET /orders/health");

        FlowImpact show = report.flows().stream().filter(f -> f.entry().label().equals("GET /orders/{id}")).findFirst().orElseThrow();
        var reached = show.reached().get(0);
        assertThat(reached.methodId()).contains("JdbcOrderRepository#findStatus");
        assertThat(reached.path()).hasSize(3); // controller -> service -> implementation (reached through the interface)
        assertThat(reached.viaInterface()).isTrue();
    }

    @Test
    void reportsUnchangedCallersAndMissingTestCoverage() {
        String v2 = REPO_IMPL_V1.replace("String s = lookup(id);\n        System.out.println(s);", "String s = lookup(id);\n        System.out.println(\"cancelled \" + s);");
        Snapshot head = snap("b", v2);
        ChangeSet cs = SnapshotDiffer.diff(snap("a", REPO_IMPL_V1), head);
        ImpactReport report = ImpactAnalyzer.analyze(head, cs);

        // markCancelled changed; OrderService.cancel calls it (via the interface) and is not itself changed
        var blast = report.blast().stream().filter(b -> b.changedMethod().contains("markCancelled")).findFirst().orElseThrow();
        assertThat(blast.unchangedCallers()).anyMatch(c -> c.contains("OrderService#cancel"));
        // no test reaches markCancelled: the only test calls describe()
        assertThat(report.changedWithoutTests()).anyMatch(id -> id.contains("markCancelled"));
        assertThat(blast.coveringTests()).isEmpty();
    }

    @Test
    void aChangeNothingReachesProducesNoFlows() {
        String v2 = REPO_IMPL_V1.replace("return \"OPEN\";", "return \"OPEN\"; // same\n    }\n\n    private int unused() {\n        int a = 1;\n        return a + 1;");
        Snapshot head = snap("b", v2);
        ImpactReport report = ImpactAnalyzer.analyze(head, SnapshotDiffer.diff(snap("a", REPO_IMPL_V1), head));
        assertThat(report.flows()).isEmpty();
    }
}
