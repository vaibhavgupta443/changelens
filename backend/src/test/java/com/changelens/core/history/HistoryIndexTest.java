package com.changelens.core.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.changelens.core.history.HistoryIndex.Entry;
import com.changelens.core.history.HistoryIndex.Hit;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The history is real git history, so the right answers are known in advance. */
class HistoryIndexTest {

    private static final String GREETER = "src/main/java/demo/Greeter.java";

    @TempDir
    static Path dir;

    static String shaAddGreeter;
    static String shaFixNull;
    static String shaBumpLogback;
    static String shaRevert;
    static String shaPullListener;
    static HistoryIndex index;

    @BeforeAll
    static void createHistory() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            shaAddGreeter = commit(git, 1, "Add Greeter class", "", GREETER, "class Greeter { String greet() { return \"hi\"; } }");
            shaFixNull = commit(git, 2, "Fix NullPointerException when the name is null in greet",
                    "greet crashed for a missing name; it now returns a default greeting.", GREETER, "class Greeter { String greet(String n) { return n == null ? \"hi\" : n; } }");
            shaBumpLogback = commit(git, 3, "Bump logback-classic from 1.5.20 to 1.5.21", "", "pom.xml", "<project>logback 1.5.21</project>");
            shaPullListener = commit(git, 4, "feat: implement ModelPullListener to track progress while pulling a model", "",
                    "src/main/java/demo/ModelPullListener.java", "interface ModelPullListener { void onStatus(String s); }");
            shaRevert = commit(git, 5, "Revert \"Fix NullPointerException when the name is null in greet\"", "This reverts commit " + shaFixNull + ".", GREETER, "class Greeter { String greet() { return \"hi\"; } }");
        }
        index = HistoryIndex.build(dir, "HEAD", 100);
    }

    private static String commit(Git git, int day, String subject, String body, String path, String content) throws Exception {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(".").call();
        PersonIdent who = new PersonIdent("Someone Real", "someone@example.com", new Date(1_700_000_000_000L + day * 86_400_000L), TimeZone.getTimeZone("UTC"));
        return git.commit().setMessage(body.isEmpty() ? subject : subject + "\n\n" + body).setAuthor(who).setCommitter(who).call().getName();
    }

    @Test
    void indexesEveryCommitNewestFirstWithItsFiles() {
        assertThat(index.size()).isEqualTo(5);
        assertThat(index.revision()).isEqualTo(shaRevert);
        Entry fix = index.find(shaFixNull).orElseThrow();
        assertThat(fix.subject()).startsWith("Fix NullPointerException");
        assertThat(fix.files()).containsExactly(GREETER);
        assertThat(fix.body()).contains("default greeting");
    }

    @Test
    void aQuestionFindsTheCommitThatAnswersIt() {
        assertThat(top("null pointer crash in greet")).isEqualTo(shaFixNull);
        assertThat(top("which logback version did we move to")).isEqualTo(shaBumpLogback);
        assertThat(top("who added progress tracking for model pulls")).isEqualTo(shaPullListener);
    }

    @Test
    void camelCaseIdentifiersMatchTheirWordsAndTheOtherWayRound() {
        assertThat(top("model pull listener")).isEqualTo(shaPullListener);
        assertThat(top("ModelPullListener")).isEqualTo(shaPullListener);
        assertThat(HistoryIndex.vocabulary("ModelPullListener")).contains("model", "pull", "listener", "modelpulllistener");
    }

    @Test
    void theFileNamesOfACommitAreSearchableToo() {
        List<Hit> hits = index.search("Greeter.java", 10);

        assertThat(hits).extracting(h -> h.entry().sha()).contains(shaAddGreeter, shaFixNull, shaRevert).doesNotContain(shaBumpLogback);
    }

    @Test
    void hitsExplainThemselvesAndAreOrderedByScore() {
        List<Hit> hits = index.search("fix null greet", 5);

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).matchedTerms()).contains("null", "greet");
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i - 1).score()).isGreaterThanOrEqualTo(hits.get(i).score());
        }
        assertThat(index.search("fix null greet", 2)).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void aQuestionAboutNothingInTheHistoryFindsNothing() {
        assertThat(index.search("kubernetes helm chart", 5)).isEmpty();
        assertThat(index.search("", 5)).isEmpty();
        assertThat(index.search("the of and", 5)).isEmpty();
        assertThat(index.search(null, 5)).isEmpty();
        assertThat(index.search("greet", 0)).isEmpty();
    }

    @Test
    void theHistoryOfAFileIsTheCommitsThatTouchedIt() {
        List<Entry> history = index.historyOfFile(GREETER, 10);

        assertThat(history).extracting(Entry::sha).containsExactly(shaRevert, shaFixNull, shaAddGreeter);
        assertThat(index.historyOfFile(GREETER.replace('/', '\\'), 10)).hasSize(3);
        assertThat(index.touchCount(GREETER)).isEqualTo(3);
        assertThat(index.historyOfFile(GREETER, 1)).extracting(Entry::sha).containsExactly(shaRevert);
        assertThat(index.historyOfFile("no/such/File.java", 10)).isEmpty();
    }

    @Test
    void fixAndRevertCommitsAreFlaggedByTheirMessageOnly() {
        assertThat(index.find(shaFixNull).orElseThrow().looksLikeFix()).isTrue();
        assertThat(index.find(shaRevert).orElseThrow().looksLikeFix()).isTrue();
        assertThat(index.find(shaAddGreeter).orElseThrow().looksLikeFix()).isFalse();
        assertThat(index.find(shaBumpLogback).orElseThrow().looksLikeFix()).isFalse();
    }

    @Test
    void aCommitCanBeVerifiedByFullShaOrPrefixButNotInvented() {
        assertThat(index.hasCommit(shaFixNull)).isTrue();
        assertThat(index.hasCommit(shaFixNull.substring(0, 10))).isTrue();
        assertThat(index.hasCommit(shaFixNull.substring(0, 6))).isFalse();
        assertThat(index.hasCommit("0123456789abcdef0123456789abcdef01234567")).isFalse();
        assertThat(index.hasCommit(null)).isFalse();
    }

    @Test
    void anOlderRevisionIndexesOnlyWhatCameBefore() throws IOException {
        HistoryIndex older = HistoryIndex.build(dir, shaFixNull, 100);

        assertThat(older.size()).isEqualTo(2);
        assertThat(older.hasCommit(shaRevert)).isFalse();
        assertThat(older.hasCommit(shaFixNull)).isTrue();
    }

    @Test
    void theCommitLimitKeepsTheNewestAndAnUnknownRevisionIsAnError() throws IOException {
        HistoryIndex two = HistoryIndex.build(dir, "HEAD", 2);

        assertThat(two.size()).isEqualTo(2);
        assertThat(two.hasCommit(shaRevert)).isTrue();
        assertThat(two.hasCommit(shaAddGreeter)).isFalse();
        assertThatThrownBy(() -> HistoryIndex.build(dir, "no-such-branch", 10)).isInstanceOf(IOException.class).hasMessageContaining("no-such-branch");
    }

    @Test
    void authorsAreNotPartOfTheIndex() {
        assertThat(index.search("Someone Real", 5)).isEmpty();
        assertThat(index.search("someone@example.com", 5)).isEmpty();
    }

    private static String top(String question) {
        List<Hit> hits = index.search(question, 3);
        assertThat(hits).as(question).isNotEmpty();
        return hits.get(0).entry().sha();
    }
}
