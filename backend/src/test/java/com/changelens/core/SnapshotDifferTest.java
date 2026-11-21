package com.changelens.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.changelens.core.diff.ChangeModel.ChangeKind;
import com.changelens.core.diff.ChangeModel.ChangeSet;
import com.changelens.core.diff.ChangeModel.MethodChange;
import com.changelens.core.diff.ChangeModel.Nature;
import com.changelens.core.diff.ChangeModel.TypeChange;
import com.changelens.core.diff.SnapshotDiffer;
import com.changelens.core.model.CodeModel.Resolution;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.parse.JavaSnapshotBuilder;
import com.changelens.core.parse.SourceFile;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Each test states one thing a reviewer should be told, and checks the engine says exactly that. */
class SnapshotDifferTest {

    private static Snapshot snap(String ref, String... pathAndSource) {
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < pathAndSource.length; i += 2) {
            files.add(new SourceFile(pathAndSource[i], pathAndSource[i + 1]));
        }
        return JavaSnapshotBuilder.build("demo", ref, files, List.of());
    }

    private static TypeChange type(ChangeSet cs, String simple) {
        return cs.types().stream().filter(t -> t.simpleName().equals(simple)).findFirst().orElseThrow(
                () -> new AssertionError("no change for " + simple + " in " + cs.types().stream().map(TypeChange::simpleName).toList()));
    }

    private static MethodChange method(TypeChange t, String name) {
        return t.methods().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow(
                () -> new AssertionError("no method change for " + name + " in " + t.methods().stream().map(MethodChange::name).toList()));
    }

    private static final String SVC_V1 = """
            package com.acme.order;

            import com.acme.repo.OrderRepository;
            import org.springframework.stereotype.Service;

            @Service
            public class OrderService {
                private final OrderRepository repository;

                public OrderService(OrderRepository repository) {
                    this.repository = repository;
                }

                public String cancel(String id) {
                    String status = repository.findStatus(id);
                    repository.markCancelled(id);
                    return status;
                }
            }
            """;

    private static final String REPO = """
            package com.acme.repo;

            public interface OrderRepository {
                String findStatus(String id);
                void markCancelled(String id);
            }
            """;

    @Test
    void reformattingAndCommentsAreReportedAsCosmeticNotBehaviour() {
        String v2 = SVC_V1.replace("public String cancel(String id) {", "// cancel an order\n    public   String cancel( String id )\n    {")
                .replace("return status;", "return   status; // done");
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", v2, "OrderRepository.java", REPO));
        MethodChange m = method(type(cs, "OrderService"), "cancel");
        assertThat(m.nature()).isEqualTo(Nature.COSMETIC);
        assertThat(cs.summary().methodsBehaviorChanged()).isZero();
        assertThat(cs.summary().methodsCosmeticOnly()).isEqualTo(1);
    }

    @Test
    void renamingLocalVariablesIsNotABehaviourChange() {
        String v2 = SVC_V1.replace("String status = repository.findStatus(id);", "String previous = repository.findStatus(id);")
                .replace("return status;", "return previous;");
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", v2, "OrderRepository.java", REPO));
        assertThat(method(type(cs, "OrderService"), "cancel").nature()).isEqualTo(Nature.LOCAL_RENAME);
        assertThat(cs.summary().methodsBehaviorChanged()).isZero();
    }

    @Test
    void addedBranchAndNewCallAreDescribedAsBehaviourDelta() {
        String v2 = SVC_V1.replace("repository.markCancelled(id);", """
                if ("SHIPPED".equals(status)) {
                    throw new IllegalStateException("already shipped");
                }
                repository.markCancelled(id);
                """);
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", v2, "OrderRepository.java", REPO));
        MethodChange m = method(type(cs, "OrderService"), "cancel");
        assertThat(m.nature()).isEqualTo(Nature.BEHAVIOR);
        assertThat(m.behavior().branchesAdded()).hasSize(1);
        assertThat(m.behavior().branchesAdded().get(0).kind()).isEqualTo("if");
        assertThat(m.behavior().branchesAdded().get(0).condition()).contains("SHIPPED");
        assertThat(m.behavior().thrownAdded()).containsExactly("IllegalStateException");
        assertThat(m.behavior().callsAdded()).extracting(c -> c.targetMethod()).contains("equals", "<init>");
        assertThat(m.behavior().callsRemoved()).isEmpty();
    }

    @Test
    void callsOnInjectedRepositoriesResolveToTheRepositoryInterface() {
        Snapshot s = snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO);
        var cancel = s.types().get("com.acme.order.OrderService").methods().stream().filter(m -> m.name().equals("cancel")).findFirst().orElseThrow();
        var find = cancel.facts().calls().stream().filter(c -> c.targetMethod().equals("findStatus")).findFirst().orElseThrow();
        assertThat(find.targetType()).isEqualTo("com.acme.repo.OrderRepository");
        assertThat(find.resolution()).isEqualTo(Resolution.REPO);
    }

    @Test
    void addingTransactionalIsASignatureChangeNotALogicChange() {
        String v2 = SVC_V1.replace("    public String cancel", "    @Transactional\n    public String cancel");
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", v2, "OrderRepository.java", REPO));
        MethodChange m = method(type(cs, "OrderService"), "cancel");
        assertThat(m.nature()).isEqualTo(Nature.SIGNATURE);
        assertThat(m.declaration().annotationsAdded()).containsExactly("@Transactional");
        assertThat(m.behavior().isEmpty()).isTrue();
    }

    @Test
    void movingAClassToAnotherPackageIsRelocationWithNoLogicChange() {
        String moved = SVC_V1.replace("package com.acme.order;", "package com.acme.orders.core;");
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "a/OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "b/OrderService.java", moved, "OrderRepository.java", REPO));
        TypeChange t = type(cs, "OrderService");
        assertThat(t.kind()).isEqualTo(ChangeKind.MOVED);
        assertThat(t.oldFqn()).isEqualTo("com.acme.order.OrderService");
        assertThat(t.fqn()).isEqualTo("com.acme.orders.core.OrderService");
        assertThat(cs.summary().methodsBehaviorChanged()).isZero();
        assertThat(cs.summary().typesAdded()).isZero();
        assertThat(cs.summary().typesRemoved()).isZero();
    }

    @Test
    void renamedMethodWithSameBodyIsDetectedAsRename() {
        String v2 = SVC_V1.replace("public String cancel(String id)", "public String cancelOrder(String id)");
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", v2, "OrderRepository.java", REPO));
        MethodChange m = method(type(cs, "OrderService"), "cancelOrder");
        assertThat(m.kind()).isEqualTo(ChangeKind.RENAMED);
        assertThat(m.oldSignature()).contains("cancel(");
        assertThat(cs.summary().methodsBehaviorChanged()).isZero();
        assertThat(type(cs, "OrderService").methods()).noneMatch(x -> x.kind() == ChangeKind.REMOVED);
    }

    @Test
    void methodMovedToAnotherClassIsDetectedAcrossTypes() {
        String helperV1 = """
                package com.acme.util;
                public class Helper {
                    public int total(int a, int b) {
                        int sum = a + b;
                        if (sum > 100) { sum = 100; }
                        return sum;
                    }
                }
                """;
        String callerV1 = """
                package com.acme.util;
                public class Caller {
                    public int run() { return 1; }
                }
                """;
        String helperV2 = """
                package com.acme.util;
                public class Helper {
                }
                """;
        String callerV2 = """
                package com.acme.util;
                public class Caller {
                    public int run() { return 1; }
                    public int total(int a, int b) {
                        int sum = a + b;
                        if (sum > 100) { sum = 100; }
                        return sum;
                    }
                }
                """;
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "Helper.java", helperV1, "Caller.java", callerV1),
                snap("b", "Helper.java", helperV2, "Caller.java", callerV2));
        MethodChange moved = method(type(cs, "Caller"), "total");
        assertThat(moved.kind()).isEqualTo(ChangeKind.MOVED);
        assertThat(moved.nature()).isEqualTo(Nature.RELOCATED);
        assertThat(moved.oldId()).startsWith("com.acme.util.Helper#total");
        assertThat(type(cs, "Helper").methods()).noneMatch(m -> m.name().equals("total"));
    }

    @Test
    void newAndDeletedClassesAreReported() {
        String added = "package com.acme; public class Fresh { public void go() { System.out.println(\"hi\"); } }";
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "Old.java", "package com.acme; public class Old { public void x() { int a = 1; int b = 2; } }"),
                snap("b", "Fresh.java", added));
        assertThat(type(cs, "Fresh").kind()).isEqualTo(ChangeKind.ADDED);
        assertThat(type(cs, "Old").kind()).isEqualTo(ChangeKind.REMOVED);
        assertThat(cs.summary().typesAdded()).isEqualTo(1);
        assertThat(cs.summary().typesRemoved()).isEqualTo(1);
    }

    @Test
    void anUnchangedRepositoryProducesNoChanges() {
        ChangeSet cs = SnapshotDiffer.diff(snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO),
                snap("b", "OrderService.java", SVC_V1, "OrderRepository.java", REPO));
        assertThat(cs.types()).isEmpty();
        assertThat(cs.summary().filesChanged()).isZero();
    }

    @Test
    void classesAreTaggedWithTheirArchitecturalLayer() {
        Snapshot s = snap("a", "OrderService.java", SVC_V1, "OrderRepository.java", REPO,
                "OrderController.java", "package com.acme.web; @RestController public class OrderController { }");
        assertThat(s.types().get("com.acme.order.OrderService").layer().name()).isEqualTo("SERVICE");
        assertThat(s.types().get("com.acme.repo.OrderRepository").layer().name()).isEqualTo("REPOSITORY");
        assertThat(s.types().get("com.acme.web.OrderController").layer().name()).isEqualTo("CONTROLLER");
    }
}
