"""Lightweight repository-context retrieval (BM25 over code chunks), no embeddings and no extra dependencies.

Problem it addresses: a reviewer (human or model) that sees only the diff cannot know about callers, configuration or
code that already handles a case. Evaluations of AI review report missing cross-file context as a major source of
false positives. Here the Spring Boot core sends the files around the change and this module picks the few chunks that
are most relevant to the identifiers the change touches, so the model can be shown *real* surrounding code.
"""
from __future__ import annotations

import math
import re
from collections import Counter
from dataclasses import dataclass

_TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_CAMEL = re.compile(r"(?<=[a-z0-9])(?=[A-Z])|_")
STOP = {"the", "a", "an", "and", "or", "if", "else", "for", "while", "return", "new", "this", "null", "true", "false", "public",
        "private", "protected", "static", "final", "void", "int", "long", "string", "class", "import", "package", "var", "def", "self"}


def tokens(text: str) -> list[str]:
    out: list[str] = []
    for ident in _TOKEN.findall(text or ""):
        parts = [p.lower() for p in _CAMEL.split(ident) if p]
        for p in parts + ([ident.lower()] if len(parts) > 1 else []):
            if len(p) > 2 and p not in STOP:
                out.append(p)
    return out


@dataclass(frozen=True)
class Chunk:
    path: str
    start_line: int
    text: str


def chunk_file(path: str, content: str, size: int = 40, overlap: int = 8) -> list[Chunk]:
    lines = (content or "").splitlines()
    chunks: list[Chunk] = []
    step = max(1, size - overlap)
    for i in range(0, max(1, len(lines)), step):
        part = lines[i:i + size]
        if part:
            chunks.append(Chunk(path, i + 1, "\n".join(part)))
        if i + size >= len(lines):
            break
    return chunks


class Bm25:
    def __init__(self, chunks: list[Chunk], k1: float = 1.5, b: float = 0.75):
        self.chunks = chunks
        self.k1, self.b = k1, b
        self.docs = [Counter(tokens(c.text)) for c in chunks]
        self.len = [sum(d.values()) for d in self.docs]
        self.avg = (sum(self.len) / len(self.len)) if self.len else 0.0
        df: Counter = Counter()
        for d in self.docs:
            df.update(d.keys())
        n = len(chunks)
        self.idf = {t: math.log(1 + (n - f + 0.5) / (f + 0.5)) for t, f in df.items()}

    def search(self, query: str, k: int = 4, exclude_paths: set[str] | None = None) -> list[tuple[float, Chunk]]:
        q = Counter(tokens(query))
        scored: list[tuple[float, Chunk]] = []
        for i, d in enumerate(self.docs):
            c = self.chunks[i]
            if exclude_paths and c.path in exclude_paths:
                continue
            s = 0.0
            for t in q:
                f = d.get(t, 0)
                if not f:
                    continue
                denom = f + self.k1 * (1 - self.b + self.b * (self.len[i] / self.avg if self.avg else 1))
                s += self.idf.get(t, 0.0) * f * (self.k1 + 1) / denom
            if s > 0:
                scored.append((s, c))
        scored.sort(key=lambda x: -x[0])
        return scored[:k]
