package com.changelens.core.history;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.io.NullOutputStream;

/**
 * Searchable history of one repository: for every commit, its message, date and the files it touched. This is the retrieval half
 * of "ask the history": a question comes back as real commits, each with the sha that proves it exists, never as generated text.
 *
 * <p>Ranking is BM25 over the words of the message and the names of the touched files, with camelCase split so that
 * {@code ModelPullListener} matches "model pull listener". Author names and e-mail addresses are deliberately not indexed or returned.
 */
public final class HistoryIndex {

    /** Messages longer than this are cut; a commit body is context, not the whole changelog. */
    private static final int MAX_BODY_CHARS = 1500;
    /** A commit that touches more files than this keeps only the first ones and is marked truncated. */
    private static final int MAX_FILES = 200;
    /**
     * A keyword hit must score at least this factor times ln(commits). A question about nothing in the history still shares an
     * everyday word such as "results" with some commits; those one-word overlaps score about 5 to 6 on a 1,000-commit history, real answers
     * score 11 and up. Raw BM25 grows with the size of the history, hence the scaling; below {@link #FLOOR_FROM_COMMITS} commits there is
     * no stable scale and no floor. Calibrated on 5 unrelated and 22 labelled questions (tools/history-eval), so treat it as a starting point.
     */
    private static final double MIN_SCORE_FACTOR = Double.parseDouble(System.getProperty("changelens.minKeywordFactor", "1.2"));
    private static final int FLOOR_FROM_COMMITS = 100;
    /**
     * The words a commit shares with the question must also be rare enough, in total, to be evidence: the sum of their idf must reach
     * this factor times ln(commits). One rare word ("logback") is enough; the everyday word "fix" on its own is not, however high a
     * short message scores for it. Without this, a question about a null pointer returned "try fixing code block". Calibrated on the
     * same labelled questions as the score floor (tools/history-eval).
     */
    private static final double MIN_EVIDENCE_FACTOR = Double.parseDouble(System.getProperty("changelens.minEvidenceFactor", "0.65"));
    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private static final Pattern FIX_LIKE = Pattern.compile("\\b(fix(es|ed)?|bug(fix)?|hotfix|revert(s|ed)?|regression|npe|crash(es)?|nullpointer\\w*)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was",
            "be", "by", "at", "as", "it", "this", "that", "from", "when", "how", "what", "why", "did", "do", "does", "we", "you", "i");

    public record Entry(String sha, Instant when, String subject, String body, List<String> files, boolean filesTruncated) {

        public String shortSha() {
            return sha.substring(0, 10);
        }

        /** A keyword heuristic, not a judgement: the message talks about a fix, a bug or a revert. */
        public boolean looksLikeFix() {
            return FIX_LIKE.matcher(subject).find();
        }
    }

    public record Hit(Entry entry, double score, List<String> matchedTerms) {
    }

    private final String revision;
    private final List<Entry> entries;
    private final Map<String, Integer> byShaPrefix = new HashMap<>();
    private final Map<String, int[]> postings = new HashMap<>();
    private final int[] docLength;
    private final double averageLength;

    private HistoryIndex(String revision, List<Entry> entries) {
        this.revision = revision;
        this.entries = List.copyOf(entries);
        Map<String, List<int[]>> building = new HashMap<>();
        docLength = new int[entries.size()];
        long total = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            byShaPrefix.put(e.sha(), i);
            Map<String, Integer> counts = new HashMap<>();
            for (String t : tokens(documentText(e))) {
                counts.merge(t, 1, Integer::sum);
                docLength[i]++;
            }
            total += docLength[i];
            final int doc = i;
            counts.forEach((term, tf) -> building.computeIfAbsent(term, k -> new ArrayList<>()).add(new int[] { doc, tf }));
        }
        averageLength = entries.isEmpty() ? 0 : (double) total / entries.size();
        building.forEach((term, list) -> {
            int[] flat = new int[list.size() * 2];
            for (int i = 0; i < list.size(); i++) {
                flat[2 * i] = list.get(i)[0];
                flat[2 * i + 1] = list.get(i)[1];
            }
            postings.put(term, flat);
        });
    }

    /**
     * The full sha a revision (branch, tag, abbreviated sha, HEAD~1) points to right now.
     *
     * @throws IOException if the repository cannot be read or the revision does not exist
     */
    public static String resolve(Path repoDir, String revision) throws IOException {
        try (Git git = Git.open(repoDir.toFile())) {
            ObjectId id = git.getRepository().resolve(revision + "^{commit}");
            if (id == null) {
                throw new IOException("Unknown revision: " + revision);
            }
            return id.getName();
        }
    }

    /** The date of the oldest commit in the index, or null when it is empty. */
    public Instant oldest() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1).when();
    }

    /**
     * Reads up to {@code maxCommits} non-merge commits reachable from {@code revision}, newest first.
     *
     * @throws IOException if the repository cannot be read or the revision does not exist
     */
    public static HistoryIndex build(Path repoDir, String revision, int maxCommits) throws IOException {
        List<Entry> list = new ArrayList<>();
        try (Git git = Git.open(repoDir.toFile()); Repository repo = git.getRepository(); RevWalk walk = new RevWalk(repo);
                DiffFormatter diff = new DiffFormatter(NullOutputStream.INSTANCE)) {
            ObjectId start = repo.resolve(revision + "^{commit}");
            if (start == null) {
                throw new IOException("Unknown revision: " + revision);
            }
            diff.setRepository(repo);
            diff.setDetectRenames(false);
            walk.sort(RevSort.TOPO);
            walk.markStart(walk.parseCommit(start));
            for (RevCommit c : walk) {
                if (list.size() >= maxCommits) {
                    break;
                }
                if (c.getParentCount() > 1) {
                    continue;
                }
                list.add(toEntry(repo, walk, diff, c));
            }
            return new HistoryIndex(start.getName(), list);
        }
    }

    private static Entry toEntry(Repository repo, RevWalk walk, DiffFormatter diff, RevCommit c) throws IOException {
        List<String> files = new ArrayList<>();
        boolean truncated = false;
        List<DiffEntry> diffs;
        if (c.getParentCount() == 0) {
            diffs = diff.scan(new org.eclipse.jgit.treewalk.EmptyTreeIterator(),
                    new org.eclipse.jgit.treewalk.CanonicalTreeParser(null, repo.newObjectReader(), c.getTree()));
        } else {
            walk.parseHeaders(c.getParent(0));
            diffs = diff.scan(c.getParent(0).getTree(), c.getTree());
        }
        for (DiffEntry d : diffs) {
            if (files.size() >= MAX_FILES) {
                truncated = true;
                break;
            }
            files.add(d.getChangeType() == DiffEntry.ChangeType.DELETE ? d.getOldPath() : d.getNewPath());
        }
        String full = c.getFullMessage().strip();
        int firstBreak = full.indexOf('\n');
        String subject = (firstBreak < 0 ? full : full.substring(0, firstBreak)).strip();
        String body = firstBreak < 0 ? "" : full.substring(firstBreak + 1).strip();
        if (body.length() > MAX_BODY_CHARS) {
            body = body.substring(0, MAX_BODY_CHARS);
        }
        return new Entry(c.getName(), Instant.ofEpochSecond(c.getCommitTime()), subject, body, List.copyOf(files), truncated);
    }

    public int size() {
        return entries.size();
    }

    /** All indexed commits, newest first. */
    public List<Entry> entries() {
        return entries;
    }

    /** The text a sentence-embedding model reads for a commit: the message and the names of the files, without the keyword weighting. */
    public static String embeddingText(Entry e) {
        StringBuilder sb = new StringBuilder(e.subject());
        if (!e.body().isEmpty()) {
            sb.append(". ").append(e.body(), 0, Math.min(e.body().length(), 600));
        }
        List<String> names = e.files().stream().limit(6).map(f -> f.substring(f.lastIndexOf('/') + 1)).toList();
        if (!names.isEmpty()) {
            sb.append(" Files: ").append(String.join(", ", names));
        }
        return sb.toString();
    }

    /** The full sha the index was built from. */
    public String revision() {
        return revision;
    }

    /** True if this commit is in the index. Accepts a full sha or an unambiguous prefix of at least 7 characters. */
    public boolean hasCommit(String sha) {
        return find(sha).isPresent();
    }

    public java.util.Optional<Entry> find(String sha) {
        if (sha == null || sha.length() < 7) {
            return java.util.Optional.empty();
        }
        String s = sha.toLowerCase(Locale.ROOT);
        if (byShaPrefix.containsKey(s)) {
            return java.util.Optional.of(entries.get(byShaPrefix.get(s)));
        }
        List<Entry> matches = entries.stream().filter(e -> e.sha().startsWith(s)).limit(2).toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.get(0)) : java.util.Optional.empty();
    }

    /** Best matches for a question, highest score first. An empty or all-stop-word query matches nothing. */
    public List<Hit> search(String query, int limit) {
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(tokens(query)));
        if (terms.isEmpty() || entries.isEmpty() || limit < 1) {
            return List.of();
        }
        double[] score = new double[entries.size()];
        double[] evidence = new double[entries.size()];
        Map<Integer, Set<String>> matched = new HashMap<>();
        int n = entries.size();
        for (String term : terms) {
            int[] posting = postings.get(term);
            if (posting == null) {
                continue;
            }
            int df = posting.length / 2;
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < posting.length; i += 2) {
                int doc = posting[i];
                double tf = posting[i + 1];
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * docLength[doc] / averageLength));
                score[doc] += idf * norm;
                evidence[doc] += idf;
                matched.computeIfAbsent(doc, k -> new LinkedHashSet<>()).add(term);
            }
        }
        double floor = n >= FLOOR_FROM_COMMITS ? MIN_SCORE_FACTOR * Math.log(n + 1) : 0;
        List<Integer> order = new ArrayList<>(matched.keySet());
        double needed = n >= FLOOR_FROM_COMMITS ? MIN_EVIDENCE_FACTOR * Math.log(n + 1) : 0;
        order.removeIf(d -> score[d] < floor || evidence[d] < needed);
        order.sort(Comparator.<Integer>comparingDouble(d -> -score[d]).thenComparing(d -> d));
        return order.stream().limit(limit).map(d -> new Hit(entries.get(d), score[d], List.copyOf(matched.get(d)))).toList();
    }

    /** Commits in the index that touched this exact path, newest first. */
    public List<Entry> historyOfFile(String path, int limit) {
        String p = path.replace('\\', '/');
        return entries.stream().filter(e -> e.files().contains(p)).limit(Math.max(limit, 0)).toList();
    }

    /** How many indexed commits touched this exact path. */
    public int touchCount(String path) {
        String p = path.replace('\\', '/');
        return (int) entries.stream().filter(e -> e.files().contains(p)).count();
    }

    // --- text ---

    private static String documentText(Entry e) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            sb.append(e.subject()).append(' ');
        }
        sb.append(e.body()).append(' ');
        for (String f : e.files()) {
            sb.append(f.substring(f.lastIndexOf('/') + 1)).append(' ');
        }
        return sb.toString();
    }

    /** Lower-case words, camelCase and snake_case split, a light suffix strip, stop words and one-letter words dropped. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String raw : text.split("[^A-Za-z0-9]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            Set<String> parts = new LinkedHashSet<>();
            String whole = raw.toLowerCase(Locale.ROOT);
            for (String piece : raw.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                parts.add(piece.toLowerCase(Locale.ROOT));
            }
            if (parts.size() > 1) {
                parts.add(whole);
            }
            for (String p : parts) {
                String t = stem(p);
                if (t.length() > 1 && !STOP.contains(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) {
            return w.substring(0, w.length() - 3);
        }
        if (w.length() > 4 && w.endsWith("ed")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 4 && w.endsWith("es")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** For tests: the indexed words of a text. */
    static Set<String> vocabulary(String text) {
        return new HashSet<>(tokens(text));
    }
}
