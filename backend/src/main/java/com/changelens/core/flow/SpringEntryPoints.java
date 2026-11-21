package com.changelens.core.flow;

import com.changelens.core.flow.FlowModel.EntryKind;
import com.changelens.core.flow.FlowModel.EntryPoint;
import com.changelens.core.model.CodeModel.MethodModel;
import com.changelens.core.model.CodeModel.Snapshot;
import com.changelens.core.model.CodeModel.TypeModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds the ways a Spring application is entered: HTTP endpoints, message listeners, scheduled jobs, event listeners. */
public final class SpringEntryPoints {

    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(GET|POST|PUT|DELETE|PATCH)");
    private static final Map<String, String> VERB = Map.of("@GetMapping", "GET", "@PostMapping", "POST", "@PutMapping", "PUT",
            "@DeleteMapping", "DELETE", "@PatchMapping", "PATCH");

    private SpringEntryPoints() {
    }

    public static List<EntryPoint> find(Snapshot s) {
        List<EntryPoint> out = new ArrayList<>();
        for (TypeModel t : s.types().values()) {
            String base = "";
            for (String a : t.annotations()) {
                if (a.startsWith("@RequestMapping")) {
                    base = firstString(a);
                }
            }
            for (MethodModel m : t.methods()) {
                for (String a : m.annotations()) {
                    String name = a.contains("(") ? a.substring(0, a.indexOf('(')) : a;
                    if (VERB.containsKey(name)) {
                        out.add(new EntryPoint(EntryKind.HTTP, VERB.get(name) + " " + join(base, firstString(a)), m.id(), t.fqn(), a));
                    } else if (name.equals("@RequestMapping")) {
                        Matcher rm = REQUEST_METHOD.matcher(a);
                        String verb = rm.find() ? rm.group(1) : "ANY";
                        out.add(new EntryPoint(EntryKind.HTTP, verb + " " + join(base, firstString(a)), m.id(), t.fqn(), a));
                    } else if (name.equals("@KafkaListener") || name.equals("@RabbitListener") || name.equals("@JmsListener")) {
                        out.add(new EntryPoint(EntryKind.MESSAGE, name.substring(1) + " " + firstString(a), m.id(), t.fqn(), a));
                    } else if (name.equals("@Scheduled")) {
                        out.add(new EntryPoint(EntryKind.SCHEDULED, "Scheduled " + a.substring(a.indexOf('(') < 0 ? a.length() : a.indexOf('(')), m.id(), t.fqn(), a));
                    } else if (name.equals("@EventListener") || name.equals("@TransactionalEventListener")) {
                        out.add(new EntryPoint(EntryKind.EVENT, "Event " + m.paramTypes(), m.id(), t.fqn(), a));
                    }
                }
            }
        }
        out.sort((x, y) -> x.label().compareTo(y.label()));
        return out;
    }

    private static String firstString(String annotation) {
        Matcher m = STRING.matcher(annotation);
        return m.find() ? m.group(1) : "";
    }

    static String join(String base, String path) {
        String b = base == null ? "" : base;
        String p = path == null ? "" : path;
        String joined = (b + (p.startsWith("/") || p.isEmpty() || b.endsWith("/") ? "" : "/") + p).replaceAll("/{2,}", "/");
        return joined.isEmpty() ? "/" : joined;
    }
}
