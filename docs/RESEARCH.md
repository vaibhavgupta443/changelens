# Research behind ChangeLens

What people struggle with in code review, what exists today, and what gap ChangeLens targets. Evidence quality is marked:
**[paper]** peer-reviewed or arXiv, **[vendor]** a company's own report or marketing (treat figures with caution),
**[secondary]** a blog or summary I could not trace to a primary source.

## 1. The core problem is *understanding the change*, not finding bugs

- Bacchelli & Bird (ICSE 2013, Microsoft) found that reviews deliver less defect-finding than developers expect, and that **code and change understanding is the key aspect of reviewing**; most of the ways developers try to understand a change are not supported by their tools. [paper] ([source](https://microsoft.com/en-us/research/?p=164195))
- Bosu, Greiler & Bird (Microsoft) found roughly a third of review comments were not useful; Czerwonka et al. found the rate of useful feedback drops for reviews touching 20 or more files. [paper] ([source](https://www.microsoft.com/en-us/research/?p=168510))
- A recent preprint over 330K PRs found later-positioned files in a PR have a higher latent bug rate and that review attention falls as PRs grow. [paper, preprint] ([source](https://arxiv.org/pdf/2609.22610))

**Implication:** a tool that tells a reviewer *what the change does, at every level*, attacks the actual bottleneck.

## 2. AI-generated code makes it worse

- A 2026 survey of 239 practitioners frames AI-authored PR review as an *evidence-ownership* problem: reviewers need a traceable path from the generated change to validated behaviour. [paper] ([source](https://arxiv.org/pdf/2610.21144))
- Industry surveys report reviewers spending more time reviewing since adopting AI tools (Harness: 81%; another survey: 11.4 h/week reviewing AI code vs 9.8 h writing). [vendor / secondary, original reports not verified] ([source](https://dev.to/harsh2644/the-review-tax-why-81-of-developers-are-buried-in-ai-code-review-9k6))
- Practitioners describe the core issue as intent: "the reasoning is gone, the reviewer reverse-engineers intent from a diff". [secondary]

**Implication:** syntax is no longer the question (AI code compiles). The questions are *what does it do* and *does it do what the PR says*.

## 3. Existing AI reviewers are noisy and mostly ignored

- Only 0.9-19.2% of valid AI review comments led to code changes vs ~60% for human comments. [paper] ([source](https://arxiv.org/pdf/2508.18771))
- Among 31,073 CodeRabbit review pairs, 36.4% were accepted; false positives dominate rejections. [paper] ([source](https://www.alphaxiv.org/abs/2607.03316.md))
- Missing cross-file context is a major source of false positives (one field study attributes ~10% of them to it). [paper / vendor] ([source](https://www.qodo.ai/?p=22524))

**Implication:** do not add another comment generator. Provide *facts* a human can trust, and make every statement traceable.

## 4. Diff tools do not understand code movement or refactoring

- GitHub/Gerrit diffs struggle with moves and extract-method; RefactoringMiner detects 40 refactoring types with ~99.6% precision and ~94% recall, and an AST-diff built on it exists for commits and PRs (Alikhanifard & Tsantalis, TOSEM 2024). [paper] ([source](https://arxiv.org/abs/2403.05939v2))
- GumTree (ASE 2014) established practical AST differencing with move detection. [paper] ([source](https://www.labri.fr/perso/xblanc/data/papers/ASE14.pdf))
- Caveat from a 2026 paper (SemaDiff): detecting that a commit is a refactoring does **not** prove behaviour is preserved. [paper] ([source](https://arxiv.org/pdf/2607.13111))

**Implication:** separate "structurally a refactoring" from "behaviour proven unchanged", and never overclaim the second.

## 5. Impact / blast-radius analysis is emerging but incomplete

- CHID (EMSE 2024) combines a project-wide function call graph with history mining and a per-PR risk score. [paper] ([source](https://link.springer.com/10.1007/s10664-024-10600-2))
- CodeRabbit offers "blast radius" (open beta); several small open-source tools map a diff onto a dependency graph. [vendor / open source] ([source](https://docs.coderabbit.ai/security/blast-radius))
- Gap: cross-repository and cross-service impact, and tying impact to *user-facing flows* and *owners*, is not well covered.

## 6. API breaking changes are caught for specs, not across repos

- oasdiff compares OpenAPI specs and fails CI on breaking changes; Pact covers consumer-driven contracts when you control consumers. Neither addresses the case where the contract lives only in code and consumers live in other repositories. [open source docs] ([source](https://github.com/tufin/oasdiff/blob/v1.10.16/BREAKING-CHANGES.md))

## 7. What large organisations require before adopting

(Vendor material; consistent across vendors, not independently verified.)
- Self-hosted / VPC deployment; code and model calls stay inside the company; "bring your own model".
- No retention of code and no training on it.
- Exportable audit logs tied to user identity (SOC 2, ISO 27001, SOX evidence).
- SSO / RBAC, and integration with the SCM they already use. ([source](https://www.qodo.ai/blog/best-ai-code-review-tools-for-regulated-industries-in-2026/))

## 8. Prompt injection through PR content is a documented attack

- GitInject reproduces eleven prompt-injection cases against AI CI/CD workflows; "bias injection" shows persuasive PR text steering automated review; PR-Agent was shown vulnerable via text inserted into its prompt. [paper / security research] ([source](https://arxiv.org/html/2606.09935))

## What this adds up to

No product combines **(a)** program-analysis-grade, fully traceable facts about a change at package / class / method / behaviour level, **(b)** impact on existing user-facing flows, **(c)** cross-repository contract effects, and **(d)** an intent-versus-implementation check for AI-written PRs, in **(e)** a form that can run entirely inside a company's own infrastructure. That combination is the target.
