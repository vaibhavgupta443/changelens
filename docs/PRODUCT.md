# ChangeLens: product definition

**One sentence:** ChangeLens tells a reviewer *what a pull request actually does*, at every level of abstraction, with every statement traceable to code, across repositories, and runnable entirely inside a company's own infrastructure.

## Who it is for

- **Senior engineers and tech leads** reviewing large or AI-written PRs, who today reverse-engineer behaviour from red and green lines.
- **Engineering managers / platform teams** who need to know blast radius and who must approve.
- **Regulated organisations** that need audit evidence and cannot send code to a third party.

## The principle that makes it trustworthy

**Facts come from program analysis; language comes from the model; every sentence cites a fact.**

1. A parser (AST) extracts facts: types, methods, calls, branches, endpoints, entities, config. No LLM.
2. A differ compares two snapshots and classifies each change: added, removed, moved, renamed, formatting-only, local-rename-only, or behavioural.
3. Optional: an LLM turns facts into plain English. Each sentence must cite fact IDs. Uncited or contradicted sentences are dropped, and the UI shows which facts back every claim.
4. The product is fully useful with **no model at all**.

## Features by tier

### Tier 1: the core (the thing nobody else gives you)
| Feature | What the reviewer sees |
|---|---|
| **Abstraction ladder** | Package → class → method → behaviour, each level summarised; drill down or collapse. |
| **Semantic change classification** | "12 of 40 methods only moved", "formatting only", "local variable rename only", vs "behaviour changed". Cuts review noise. |
| **Behaviour delta per method** | New/removed branches and conditions, new/removed calls, exceptions, transactions and security annotations, and calls placed inside loops. |
| **Flow impact** | Entry points (HTTP endpoints, listeners, schedulers) whose call paths pass through changed code: "which existing features does this PR touch". |
| **Contract changes** | Endpoint added/removed/changed, request/response model changes, entity/table/column changes, config keys, migrations. |
| **Visual map** | Interactive nested graph (package/class/method) coloured added / removed / modified / moved, with the impacted flows highlighted. |
| **Honest confidence** | Each call edge is marked resolved / library / unresolved. The UI says what is certain and what is not. |

### Tier 2: what AI-written code needs
| Feature | Why |
|---|---|
| **Intent vs implementation** | Compares the PR description / ticket against facts: *claims supported by the change*, *claims not found*, *changes the description never mentions*. Targets the "reviewer reverse-engineers intent" problem. |
| **Logical change groups** | Clusters changed methods into reviewable units (by call connectivity and shared flow) with a suggested reading order; suggests splitting oversized PRs. |
| **Test coverage of the change** | Which changed behaviour is reached by a test (static reachability), and which is not. |
| **Prompt-injection hardening** | PR text and code comments are untrusted: fenced, scanned, never obeyed, reported. |

### Tier 3: multi-repo and enterprise
| Feature | Why |
|---|---|
| **Multi-repo change sets** | Several PRs analysed as one feature. Cross-repo contracts matched (HTTP method + path, message topic, shared DTO); flags "service A calls an endpoint that service B's PR changes". |
| **Ownership routing** | Parses CODEOWNERS: which teams own the changed and impacted areas. |
| **Policy as code** | Config such as "changes to `payment` or DB migrations need a second approver" produce a required-reviewers list. |
| **Self-hosted, bring-your-own-model** | Analysis is local; the narrator can point at an internal model (Ollama, vLLM, Azure OpenAI) or be off. |
| **Audit log** | Who ran which analysis on which commits, and what was produced; exportable. |
| **Incremental analysis** | Per-file results cached by blob hash so repeated PRs on a large repo are fast. |

## What it deliberately does not do

- It does not claim to prove behaviour is unchanged. "Structurally a refactoring" is reported separately from "behaviour preserved", which static analysis cannot prove.
- It does not generate style or nitpick comments.
- It does not guess where analysis cannot know (reflection, dynamic proxies, runtime configuration): it labels those edges unresolved.

## Scope and honesty

- **Language: Java and Spring first.** The architecture allows other languages later; claims are limited to what is built and tested.
- **Static analysis limits are real.** Call graphs in Java cannot be perfect. Each analysis reports its resolved / unresolved ratio.
- **Refactoring detection:** own detector for rename, move and formatting-only. RefactoringMiner (MIT) is the state of the art for 40 refactoring types and is the planned optional integration.
- **Evaluation:** the project ships fixtures with known edits and reports measured precision and recall of change classification. Numbers in docs are only ones actually measured.

## Architecture

```
GitHub / GitLab / local git ──► Ingestion (Spring Boot, JGit) ──► Snapshots at base & head (per-file cache by blob hash)
                                                                         │
                                       Java/Spring analyser (JavaParser) ▼
                    types · methods · behaviour facts · call graph · endpoints · entities · config · migrations
                                                                         │
                                                         Differ + classifier ──► ChangeSet (fact IDs)
                                                                         │
            ┌──────────────── Impact & flows ────────────────┐   ┌───────┴────────┐
            │ call-graph reachability from entry points,      │   │  Multi-repo     │
            │ logical groups, test reach, ownership, policy   │   │  contract match │
            └──────────────────────┬──────────────────────────┘   └───────┬────────┘
                                   ▼                                       ▼
                         REST API (Spring Boot) ◄────────── AI service (Python): narration + claim verification + injection defence
                                   │
                              React UI (Cytoscape): abstraction map, flows, contracts, intent check
```

## Build order

1. Core analyser and differ with fixtures and tests (Java/Spring facts, classification, move/rename/cosmetic detection).
2. Call graph, entry points, flow impact.
3. REST API, git ingestion (JGit), persistence.
4. Visual UI.
5. Narration with fact-cited claims and verification (AI service).
6. Intent-vs-implementation, change groups, test reach.
7. Multi-repo contracts, ownership, policy, audit log.
8. Evaluation harness, CI, docs.
