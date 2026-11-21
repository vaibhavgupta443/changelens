"""Prompt-injection defence for untrusted PR content (diff, title, description, comments, file contents).

Threat model (see docs/SECURITY.md): the author of a pull request is not trusted. Anything they write can end up in
the model's context, and published research and security reports show AI review agents being steered by instructions
hidden in PR text, code comments or issue bodies, and by persuasive "bias injection" in descriptions.

Defences implemented here (defence in depth, none is claimed to be perfect):
  * detect instruction-like text and invisible/bidirectional control characters and REPORT them to the reviewer;
  * make invisible characters visible before the text reaches a model;
  * wrap untrusted text in per-request random delimiters and remove any forged delimiter from the content;
  * (elsewhere) the model has no tools, its output is schema-validated, and every finding must be grounded in the diff.
"""
from __future__ import annotations

import re
import secrets
from dataclasses import dataclass

# invisible / formatting characters often used to hide instructions or to make code render differently
_HIDDEN = {
    "​": "ZWSP", "‌": "ZWNJ", "‍": "ZWJ", "⁠": "WJ", "﻿": "BOM",
    "‪": "LRE", "‫": "RLE", "‬": "PDF", "‭": "LRO", "‮": "RLO",
    "⁦": "LRI", "⁧": "RLI", "⁨": "FSI", "⁩": "PDI",
}
_HIDDEN_RE = re.compile("[" + "".join(_HIDDEN) + "]")

_PATTERNS: list[tuple[str, re.Pattern]] = [
    ("override-instructions", re.compile(r"\b(ignore|disregard|forget|override)\b.{0,40}\b(previous|prior|above|earlier|all|any|your)\b.{0,40}\b(instruction|rule|prompt|guideline|direction)s?\b", re.I | re.S)),
    ("role-reassignment", re.compile(r"\b(you are now|from now on you|act as|pretend (to be|you are)|new instructions?:)\b", re.I)),
    ("reveal-prompt", re.compile(r"\b(reveal|print|show|repeat|output)\b.{0,30}\b(system prompt|your prompt|your instructions|hidden instructions)\b", re.I | re.S)),
    ("force-approval", re.compile(r"\b(approve|lgtm|ship it|merge)\b.{0,30}\b(this|the)\b.{0,15}\b(pr|pull request|change|patch)\b|\bdo not (report|flag|mention|comment)\b|\bno (issues|findings|problems) (found|here)\b", re.I | re.S)),
    ("tool-or-secret-exfiltration", re.compile(r"\b(send|post|upload|exfiltrate|leak)\b.{0,40}\b(token|secret|api[ _-]?key|password|credentials?|environment variables?)\b", re.I | re.S)),
    ("fake-system-message", re.compile(r"(^|\n)\s*(system|assistant|developer)\s*:\s", re.I)),
    ("delimiter-forgery", re.compile(r"<<<\s*(END|BEGIN)?[ _-]*(UNTRUSTED|SYSTEM|INSTRUCTIONS?)", re.I)),
]


@dataclass(frozen=True)
class Signal:
    kind: str
    where: str
    excerpt: str

    def describe(self) -> str:
        return f"{self.kind} in {self.where}: {self.excerpt!r}"


def scan(text: str, where: str) -> list[Signal]:
    signals: list[Signal] = []
    if not text:
        return signals
    hidden = sorted({_HIDDEN[c] for c in _HIDDEN_RE.findall(text)})
    if hidden:
        signals.append(Signal("hidden-characters", where, ",".join(hidden)))
    for kind, rx in _PATTERNS:
        m = rx.search(text)
        if m:
            signals.append(Signal(kind, where, m.group(0).strip()[:120]))
    return signals


def make_visible(text: str) -> str:
    """Replace invisible/bidi characters with a visible marker so neither a human nor a model is misled."""
    return _HIDDEN_RE.sub(lambda m: f"<U+{ord(m.group(0)):04X}>", text or "")


class Fence:
    """Per-request random delimiters. The model is told that only text between them is data, never instructions."""

    def __init__(self) -> None:
        self.nonce = secrets.token_hex(6)

    @property
    def begin(self) -> str:
        return f"<<<UNTRUSTED_{self.nonce}"

    @property
    def end(self) -> str:
        return f"UNTRUSTED_{self.nonce}>>>"

    def wrap(self, label: str, text: str) -> str:
        safe = make_visible(text).replace("<<<", "<‹<").replace(">>>", ">›>")  # content can never forge a delimiter
        return f"{self.begin} {label}\n{safe}\n{self.end}"
