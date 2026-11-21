# Evaluation

How well does ChangeLens tell the kinds of change apart? This page reports what was measured, how, and what it does not show.

Run it yourself: `cd backend && ./mvnw test -Dtest=EvaluationTest`. The report is written to `backend/target/evaluation.md`.

## Method

Each case is a before/after pair of Java sources with the label a careful human reviewer would give it:
`UNCHANGED`, `COSMETIC` (comments/formatting only), `LOCAL_RENAME` (only local names), `SIGNATURE` (declaration changed, body same), `BEHAVIOR` (logic changed), `METHOD_ADDED/REMOVED/RENAMED/MOVED`, `TYPE_ADDED/REMOVED/MOVED`, `FIELD_CHANGED`.

The engine classifies every case. The test builds a confusion matrix and reports precision and recall per label.

## Results

| Set | Cases | Correct |
|---|---|---|
| Core set (the changes the engine was designed for) | 36 | 36 (100%) |
| Harder set (added after the core set scored 100%) | 16 | 16 (100%) after the fixes below; 12 of 15 on the first run |

Per-label precision and recall are 100% on every label that appears in these sets.

## What the harder set found

The core set scored 100% on the first run, which proved little: its author also wrote the engine. A harder set was added to look for real weaknesses, and it found them. The first run of the harder set got **12 of 15** right. These were genuine bugs, now fixed and covered by tests:

1. **A fingerprint that was not rename-proof when a parameter was added.** Local variables were numbered after the parameters, so adding one parameter renumbered every local and an unchanged body looked changed. Parameters and locals are now numbered separately.
2. **A file the parser cannot read was reported as deleted classes.** On a syntax error (or a language feature the parser does not know), the classes in that file vanished from the snapshot and the diff said they were removed. The differ now never reports a class as added or removed because a file could not be fully parsed on one side.
3. **A method that moved and was edited at the same time appeared as one removed and one added method.** Methods with the same name and parameters in different classes and similar behaviour are now matched as a move with the edit shown.
4. **Field initialisers and enum constants were not tracked**, so changing `int count = 0` to `= 1` or adding an enum constant looked like no change. Both are now part of the model.

(One failure on the first harder run was a mistake in the test case itself, an `import` placed above `package`.)

## What this does not show

- The cases are **hand-written** by the engine's author. They show the logic is right for the kinds of change listed. They do not show how it behaves on every real codebase. Cases written by someone else would be a stronger test.
- Each case is a **single, isolated change**. Real pull requests mix many changes.
- **Call resolution** (which method a call goes to) is evaluated only indirectly here. On spring-petclinic about 21% of call sites resolve to code in the repository, 39% to library code and 40% remain unresolved, mostly chained library calls and test assertions. That limits how complete the "which features does this touch" list can be.
- Extract-method style refactorings are reported as new and changed methods, not recognised as a refactoring.
- No claim is made that a move, rename or reformat preserves behaviour.

## Replay of real history

The hand-written cases above were written by the author, so they cannot show how the engine behaves on code nobody prepared for it. To get closer to that, real git history was replayed through the CLI (`tools/replay/replay.js`).

**How it works.** For every non-merge commit that touches Java, an independent check asks: *does the diff change any code line, ignoring whitespace, comments, Javadoc and imports?* (`git diff -w`, then filtered lines.) ChangeLens is then run on the same commit and asked: *do you report any change?* Disagreements are read by hand.

| Repository | Commits | Real code change | No code change | ChangeLens reported every real change | False alarms |
|---|---|---|---|---|---|
| ollama4j (about 100 Java files) | 150 most recent that touch Java | 139 | 11 | yes, 139 of 139 | 0 |
| SootUp (about 2,400 Java files) | 40 most recent that touch Java | 36 | 4 | yes, 36 of 36 | 0 |

**What the disagreements were.** The first pass on ollama4j showed 7 disagreements. Reading ChangeLens's output for each one:
- 6 were limits of the *checker*, not of ChangeLens: it only looked for method, type and field lines, and ChangeLens had reported the change as a changed constant (`field modified`), an added class annotation, or a renamed class.
- 1 was ChangeLens being right and the checker wrong: the commit changed only whitespace inside a log message string, which `git diff -w` ignores.
- **1 was a real bug in ChangeLens.** Renaming a method while leaving its body unchanged was labelled "only local variables or parameters renamed" and marked as skippable. A renamed method breaks its callers, so it must not be skippable. It is now reported as a declaration change with the old name (`renamed testSetAuth() (was setAuth())`), and a regression test covers it. (The 150-commit figures above were measured before this fix; the changed commit is now labelled correctly.)

**What this does and does not show.**
- It shows that on two real, differently written code bases the engine noticed every change that touched code and invented none.
- It does **not** measure whether each description is right (for example that a "logic changed" method really changed logic in the way the report says), nor call tracing, nor moves and renames on real refactoring commits.
- The checker is an approximation. A commit that only reorders methods looks like a change to it and not to ChangeLens, and it cannot see whitespace inside strings. It is a screen that tells a human where to look.
- Two repositories, one language, and commits that reached the main branch (so mostly small, reviewed changes).

Reproduce: `node tools/replay/replay.js <repo-dir> 150 results.json`

## Earlier sanity check on spring-petclinic

The engine was also run on seven real commits and a 150-commit range of spring-petclinic. It did not crash and the output matched what the commits did on reading them. There were no labels for those, so that was a sanity check, not an accuracy number.

## Next steps for a stronger evaluation

- Cases written or reviewed by other developers.
- A set mined from real refactoring commits (for example the RefactoringMiner oracle) to measure move and rename detection on real code.
- Measure call-resolution accuracy against a compiler-backed resolver on a sample project.
