"""Grounding verifier: a finding is kept only if it points at code that is really in the diff.

Why this exists: studies of AI review tools report that false positives (comments that misread the code or point at
something that is not there) are the main reason developers reject AI review comments. A cheap, deterministic check
removes the most embarrassing class of them: a comment whose cited line or quoted evidence does not exist.

A finding passes only if ALL hold:
  1. its file is part of the change,
  2. its line is a real added/context line of that file's hunks,
  3. its evidence quote (whitespace-normalised) appears within a few lines of that line.
"""
from __future__ import annotations

from dataclasses import dataclass

from .models import ChangedFile, ProposedFinding
from .patchutil import PatchLine, has_new_line, norm, parse_patch, visible_text_near


@dataclass
class GroundingResult:
    ok: bool
    reason: str = ""


class DiffIndex:
    def __init__(self, files: list[ChangedFile]):
        self._lines: dict[str, list[PatchLine]] = {f.path: parse_patch(f.patch) for f in files}

    def paths(self) -> list[str]:
        return list(self._lines)

    def lines(self, path: str) -> list[PatchLine]:
        return self._lines.get(path, [])

    def check(self, f: ProposedFinding, window: int = 3) -> GroundingResult:
        lines = self._lines.get(f.file)
        if lines is None:
            return GroundingResult(False, "file-not-in-change")
        if not has_new_line(lines, f.line):
            return GroundingResult(False, "line-not-in-diff")
        near = norm(visible_text_near(lines, f.line, window))
        quote = norm(f.evidence_quote)
        if not quote or quote not in near:
            return GroundingResult(False, "quote-not-found")
        return GroundingResult(True)
