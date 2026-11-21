"""Request / response contract between the Spring Boot core and the AI service.

Everything the model returns is validated against these schemas before it is used.
"""
from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator

Severity = Literal["info", "low", "medium", "high", "critical"]
Category = Literal["bug", "security", "performance", "design", "maintainability", "test", "api-compat", "other"]


class PullRequestMeta(BaseModel):
    title: str = ""
    description: str = ""
    author: str = ""


class ChangedFile(BaseModel):
    path: str
    status: str = "modified"
    additions: int = 0
    deletions: int = 0
    patch: str = ""  # unified diff for this file (only the hunks)

    @property
    def language(self) -> str:
        ext = self.path.rsplit(".", 1)[-1].lower() if "." in self.path else ""
        return {"java": "java", "py": "python", "ts": "typescript", "js": "javascript", "kt": "kotlin", "sql": "sql",
                "yml": "yaml", "yaml": "yaml", "xml": "xml", "properties": "properties"}.get(ext, ext or "text")


class ContextFile(BaseModel):
    path: str
    content: str = ""


class KnownFinding(BaseModel):
    """A finding already produced by the deterministic analysers (so the model does not repeat it)."""
    rule_id: str = Field(alias="ruleId")
    file: str
    line: int
    message: str = ""

    model_config = {"populate_by_name": True}


class ReviewOptions(BaseModel):
    max_findings: int = Field(default=8, alias="maxFindings", ge=1, le=30)
    min_confidence: float = Field(default=0.5, alias="minConfidence", ge=0.0, le=1.0)

    model_config = {"populate_by_name": True}


class ReviewRequest(BaseModel):
    pr: PullRequestMeta = PullRequestMeta()
    files: list[ChangedFile] = []
    context_files: list[ContextFile] = Field(default=[], alias="contextFiles")
    known_findings: list[KnownFinding] = Field(default=[], alias="knownFindings")
    options: ReviewOptions = ReviewOptions()

    model_config = {"populate_by_name": True}


class ProposedFinding(BaseModel):
    """What the model is allowed to say. Anything else in its output is ignored."""
    file: str
    line: int = Field(ge=1)
    severity: Severity = "low"
    category: Category = "other"
    title: str = Field(min_length=3, max_length=140)
    message: str = Field(min_length=3, max_length=1200)
    evidence_quote: str = Field(min_length=3, max_length=400)
    confidence: float = Field(default=0.5, ge=0.0, le=1.0)

    @field_validator("title", "message", "evidence_quote")
    @classmethod
    def _strip(cls, v: str) -> str:
        return v.strip()


class ModelOutput(BaseModel):
    summary: str = ""
    reviewer_questions: list[str] = []
    findings: list[ProposedFinding] = []


class Finding(BaseModel):
    file: str
    line: int
    severity: Severity
    category: Category
    title: str
    message: str
    evidence_quote: str = Field(alias="evidenceQuote", default="")
    confidence: float
    source: Literal["llm", "heuristic", "security"] = "llm"
    verified: bool = True

    model_config = {"populate_by_name": True, "serialize_by_alias": True}


class SecurityReport(BaseModel):
    injection_detected: bool = Field(default=False, alias="injectionDetected")
    signals: list[str] = []

    model_config = {"populate_by_name": True, "serialize_by_alias": True}


class ReviewStats(BaseModel):
    provider: str = "mock"
    proposed: int = 0
    verified: int = 0
    dropped_ungrounded: int = Field(default=0, alias="droppedUngrounded")
    dropped_low_confidence: int = Field(default=0, alias="droppedLowConfidence")
    dropped_duplicate: int = Field(default=0, alias="droppedDuplicate")
    retrieved_snippets: int = Field(default=0, alias="retrievedSnippets")
    latency_ms: int = Field(default=0, alias="latencyMs")

    model_config = {"populate_by_name": True, "serialize_by_alias": True}


class ReviewResponse(BaseModel):
    summary: str = ""
    reviewer_questions: list[str] = Field(default=[], alias="reviewerQuestions")
    findings: list[Finding] = []
    security: SecurityReport = SecurityReport()
    stats: ReviewStats = ReviewStats()

    model_config = {"populate_by_name": True, "serialize_by_alias": True}


Optional  # re-exported for type checkers
