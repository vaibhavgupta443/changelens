# ai-service (experimental)

Early building blocks for an optional AI layer. **The GitHub Action and CLI do not use this folder.** ChangeLens works fully without it.

What is here (not yet wired into the product):

- `app/injection.py`: detects instruction-like text and hidden Unicode in untrusted PR content, and fences it with per-request random delimiters.
- `app/grounding.py` + `app/patchutil.py`: check that a claim points at a line and a quote that really exist in the diff.
- `app/retrieval.py`: BM25 search over code chunks to pick relevant context.
- `app/models.py`, `app/config.py`: request/response schemas and environment-based settings.

Planned use: turn the facts ChangeLens extracts into plain-English narration where every sentence cites a fact, with a code check that rejects anything uncited. See `docs/PRODUCT.md`.

Status: these modules have not been tested yet.
