# History search evaluation

Measures `search_history` through the real MCP server over stdio, so it tests what a client gets, not a unit.

```bash
cd backend
./mvnw -P mcp -DskipTests package            # target/changelens-mcp.jar            (keyword search, about 11 MB)
./mvnw -P mcp-semantic -DskipTests package   # target/changelens-mcp-semantic.jar   (adds local embeddings, about 126 MB)
cd ../tools/history-eval
node run.js ../../backend/target/changelens-mcp-semantic.jar <folder holding the repo> questions-ollama4j.json hybrid
```

Modes: `keyword`, `semantic`, `hybrid`. Add `--scores` to print the raw scores of right and wrong answers, which is how the floors were set.

## The question set

22 questions about the real git history of [ollama4j](https://github.com/ollama4j/ollama4j), plus 5 questions about nothing in it.
The answers were read from `git log` and written down before searching.

- **15 keyword-style** questions reuse words from the commit ("rename the setAuth test method").
- **7 paraphrases** avoid them ("how did the library gain the ability to draw pictures from a text prompt" for an image-generation commit).
- **5 unrelated** ("recipe for chocolate cake"): a correct search returns nothing.

## Results (992 commits indexed)

| | keyword-style (15) | paraphrase (7) | unrelated (5) |
|---|---|---|---|
| keywords only | top-1 14/15, MRR 0.96 | top-1 2/7, top-5 2/7, MRR 0.29 | 0 wrong |
| meaning only (MiniLM) | top-1 12/15, MRR 0.83 | top-1 2/7, top-5 3/7, MRR 0.36 | 0 wrong |
| **hybrid** | top-1 14/15, MRR 0.94 | top-1 2/7, **top-5 4/7**, MRR 0.39 | 0 wrong |

Reading it plainly:

- On questions that reuse the commit's words, keywords are already excellent and the model adds nothing.
- On paraphrases the hybrid **finds the right commit in the top 5 for 4 of 7 questions, against 2 of 7 for keywords**. It does not put it first more often. Four paraphrases still fail, because a small model does not know that "draw pictures" means "image generation".
- Nothing unrelated comes back. Nearest-neighbour search always has a nearest neighbour, so both rankings have a relevance floor, set from the scores in `--scores` output.

## What this does not show

- 22 questions, written by one person, on one repository. One question moves a number by about 14 points on the paraphrase side. It is a sanity check, not a benchmark.
- A first version of this table reported hybrid paraphrase top-1 as 4/7 (MRR 0.57). That was an artefact: with equal votes a keyword hit and a meaning-only hit at the same rank tied, and the tie was settled by the commit hash. The meaning side now votes at 0.9, so ties go to the words (covered by a test). Any weight from 0.4 to 0.95 gives the numbers above.
- The thresholds (`changelens.minKeywordFactor`, `changelens.minEvidenceFactor`, `changelens.minSimilarity`, `changelens.meaningWeight`) were chosen on this set plus spot checks on SootUp. The labelled set cannot tell the evidence floor apart from no floor; it is there because the SootUp spot checks needed it.
- On SootUp (9,600 commits) there are no labels. By eye, results are good for questions with distinctive words and weaker for generic ones.

## Speed

On SootUp, 2,000 commits indexed. The first meaning-based search of a repository embeds every commit once (about 18 to 28 s on this laptop's CPU) and writes the vectors to `~/.changelens/cache` (override with `CHANGELENS_CACHE`, `off` disables it). A new process then starts in about 7 s, mostly JVM and model start-up plus reading the git history, and each further question takes about 25 ms. New commits are embedded one by one.
