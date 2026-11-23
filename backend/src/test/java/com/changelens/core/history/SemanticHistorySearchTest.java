package com.changelens.core.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.changelens.core.history.HistoryIndex.Hit;
import com.changelens.core.history.SemanticHistorySearch.Mode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fusion logic is tested with a fake embedder whose "meaning" is a handful of word groups, so the expected order is exact. One
 * test then uses the real MiniLM model on a paraphrase that no keyword search can answer.
 */
class SemanticHistorySearchTest {

    @TempDir
    static Path dir;

    static String shaImages;
    static String shaLogback;
    static String shaPull;
    static String shaNull;
    static HistoryIndex index;

    @BeforeAll
    static void createHistory() throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            shaImages = commit(git, 1, "Add image generation support", "Imagen.java");
            shaLogback = commit(git, 2, "Bump logback-classic from 1.5.20 to 1.5.21", "pom.xml");
            shaPull = commit(git, 3, "Show progress while pulling a model", "PullListener.java");
            shaNull = commit(git, 4, "Fix crash when the name is null", "Greeter.java");
        }
        index = HistoryIndex.build(dir, "HEAD", 100);
    }

    private static String commit(Git git, int day, String subject, String file) throws Exception {
        Files.writeString(dir.resolve(file), subject + "\n" + day);
        git.add().addFilepattern(".").call();
        PersonIdent who = new PersonIdent("T", "t@example.com", new Date(1_700_000_000_000L + day * 86_400_000L), TimeZone.getTimeZone("UTC"));
        return git.commit().setMessage(subject).setAuthor(who).setCommitter(who).call().getName();
    }

    /** "Meaning" for the tests: a text is a vector of how many words it has from each of three word groups. */
    static final class GroupEmbedder implements TextEmbedder {
        final AtomicInteger texts = new AtomicInteger();
        private static final List<List<String>> GROUPS = List.of(
                List.of("image", "picture", "pictures", "draw", "render", "illustration"),
                List.of("pull", "pulling", "download", "fetching", "progress", "status"),
                List.of("null", "crash", "npe", "empty"));

        @Override
        public List<float[]> embed(List<String> input) {
            texts.addAndGet(input.size());
            return input.stream().map(t -> {
                String lower = t.toLowerCase(Locale.ROOT);
                float[] v = new float[GROUPS.size() + 1];
                for (int g = 0; g < GROUPS.size(); g++) {
                    for (String w : GROUPS.get(g)) {
                        if (lower.matches(".*\\b" + w + "\\b.*")) {
                            v[g]++;
                        }
                    }
                }
                v[GROUPS.size()] = 0.01f; // keeps unrelated texts from having a zero vector
                return v;
            }).toList();
        }
    }

    @Test
    void keywordSearchCannotBridgeAVocabularyGapButMeaningCan() {
        SemanticHistorySearch search = new SemanticHistorySearch(index, new GroupEmbedder());
        String question = "how do we draw pictures from a prompt";

        assertThat(search.search(question, 3, Mode.KEYWORD)).isEmpty();
        assertThat(search.search(question, 3, Mode.SEMANTIC).get(0).entry().sha()).isEqualTo(shaImages);
        assertThat(search.search(question, 3, Mode.HYBRID).get(0).entry().sha()).isEqualTo(shaImages);
    }

    @Test
    void aCommitBothRankingsLikeBeatsOneThatOnlyOneLikes() {
        SemanticHistorySearch search = new SemanticHistorySearch(index, new GroupEmbedder());

        List<Hit> hits = search.search("progress while pulling a model", 4, Mode.HYBRID);

        assertThat(hits.get(0).entry().sha()).isEqualTo(shaPull);
        assertThat(hits.get(0).matchedTerms()).contains("progress");
        for (int i = 1; i < hits.size(); i++) {
            assertThat(hits.get(i - 1).score()).isGreaterThanOrEqualTo(hits.get(i).score());
        }
    }

    @Test
    void keywordModeIsExactlyTheKeywordIndex() {
        SemanticHistorySearch search = new SemanticHistorySearch(index, new GroupEmbedder());

        assertThat(search.search("logback", 5, Mode.KEYWORD)).extracting(h -> h.entry().sha()).containsExactly(shaLogback);
        assertThat(search.search("logback", 5, Mode.KEYWORD)).isEqualTo(index.search("logback", 5));
    }

    @Test
    void everyCommitIsEmbeddedOnlyOnceNoMatterHowManyQuestionsAreAsked() {
        GroupEmbedder embedder = new GroupEmbedder();
        SemanticHistorySearch search = new SemanticHistorySearch(index, embedder);

        search.search("null crash", 2, Mode.SEMANTIC);
        search.search("image", 2, Mode.SEMANTIC);
        search.search("download", 2, Mode.HYBRID);

        assertThat(embedder.texts.get()).isEqualTo(index.size() + 3); // each commit once, plus one text per question
    }

    @Test
    void keywordSearchNeverLoadsTheModel() {
        GroupEmbedder embedder = new GroupEmbedder();

        new SemanticHistorySearch(index, embedder).search("logback", 5, Mode.KEYWORD);

        assertThat(embedder.texts.get()).isZero();
    }

    @Test
    void blankQuestionsAndZeroLimitsFindNothingInEveryMode() {
        SemanticHistorySearch search = new SemanticHistorySearch(index, new GroupEmbedder());

        for (Mode mode : Mode.values()) {
            assertThat(search.search("  ", 5, mode)).isEmpty();
            assertThat(search.search(null, 5, mode)).isEmpty();
            assertThat(search.search("null", 0, mode)).isEmpty();
        }
    }

    @Test
    void theRealModelRanksTheRightCommitFirstForAParaphraseThatSharesNoWordWithIt() {
        TextEmbedder model = MiniLmEmbedder.tryCreate();
        assumeTrue(model != null, "the MiniLM embedding model is not available in this build or on this machine");
        String question = "how do we draw pictures from a text description";
        // measured on this history: the right commit scores 0.27 and every other commit 0.12 or less, so the floor is switched off here
        // to test the ranking by itself
        SemanticHistorySearch ranking = new SemanticHistorySearch(index, model, 0.0);

        assertThat(index.search(question, 3)).isEmpty(); // keywords find nothing: no word is shared with any commit
        assertThat(ranking.search(question, 3, Mode.SEMANTIC).get(0).entry().sha()).isEqualTo(shaImages);
        assertThat(ranking.search(question, 3, Mode.HYBRID).get(0).entry().sha()).isEqualTo(shaImages);
    }

    @Test
    void theRealModelReturnsNothingForAQuestionAboutNothingInTheHistory() {
        TextEmbedder model = MiniLmEmbedder.tryCreate();
        assumeTrue(model != null, "the MiniLM embedding model is not available in this build or on this machine");
        SemanticHistorySearch search = new SemanticHistorySearch(index, model); // the default floor

        for (Mode mode : Mode.values()) {
            assertThat(search.search("kubernetes helm chart deployment", 5, mode)).as(mode.name()).isEmpty();
            assertThat(search.search("recipe for chocolate cake", 5, mode)).as(mode.name()).isEmpty();
        }
    }
}
