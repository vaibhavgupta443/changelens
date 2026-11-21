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

## Real-code check (not a measurement)

The engine was also run on seven real commits and a 150-commit range of spring-petclinic. It did not crash and the output matched what the commits did on reading them, but there are no labels for those commits, so this is a sanity check, not an accuracy number.

## Next steps for a stronger evaluation

- Cases written or reviewed by other developers.
- A set mined from real refactoring commits (for example the RefactoringMiner oracle) to measure move and rename detection on real code.
- Measure call-resolution accuracy against a compiler-backed resolver on a sample project.
