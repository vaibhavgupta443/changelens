"""Unified-diff helpers: map new-file line numbers to the text a reviewer actually sees."""
from __future__ import annotations

import re
from dataclasses import dataclass

HUNK_RE = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")


@dataclass(frozen=True)
class PatchLine:
    new_line: int          # line number in the NEW file (0 for deleted lines)
    kind: str              # "add" | "ctx" | "del"
    text: str              # the line without the leading diff marker


def parse_patch(patch: str) -> list[PatchLine]:
    """Parse the hunks of one file's patch. Tolerant: unknown lines are skipped, never raise."""
    out: list[PatchLine] = []
    new_no = 0
    in_hunk = False
    for raw in (patch or "").splitlines():
        m = HUNK_RE.match(raw)
        if m:
            new_no = int(m.group(3))
            in_hunk = True
            continue
        if not in_hunk:
            continue
        if raw.startswith("\\"):  # "\ No newline at end of file"
            continue
        if raw.startswith("+"):
            out.append(PatchLine(new_no, "add", raw[1:]))
            new_no += 1
        elif raw.startswith("-"):
            out.append(PatchLine(0, "del", raw[1:]))
        else:
            out.append(PatchLine(new_no, "ctx", raw[1:] if raw.startswith(" ") else raw))
            new_no += 1
    return out


def visible_text_near(lines: list[PatchLine], line: int, window: int = 3) -> str:
    """Concatenate the added/context lines whose new-file number is within `window` of `line`."""
    return "\n".join(l.text for l in lines if l.kind != "del" and l.new_line and abs(l.new_line - line) <= window)


def has_new_line(lines: list[PatchLine], line: int) -> bool:
    return any(l.new_line == line and l.kind != "del" for l in lines)


_WS = re.compile(r"\s+")


def norm(s: str) -> str:
    """Whitespace-insensitive, case-sensitive comparison form."""
    return _WS.sub(" ", s or "").strip()
