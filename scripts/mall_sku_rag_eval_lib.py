import json
import re
import time
from base64 import b64encode
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from uuid import uuid4


QUESTION_HEADING = re.compile(r"^####\s+(Q\d{2})\.\s*(.+?)\s*$", re.MULTILINE)


@dataclass(frozen=True)
class QuestionSpec:
    id: str
    title: str
    category: str
    difficulty: str
    question: str
    expected_hits: list[str]
    answer_points: list[str]

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "title": self.title,
            "category": self.category,
            "difficulty": self.difficulty,
            "question": self.question,
            "expectedHits": self.expected_hits,
            "answerPoints": self.answer_points,
        }


@dataclass(frozen=True)
class EvalResult:
    question_id: str
    question: str
    session_id: str
    status: int
    duration_seconds: float
    answer: str
    error: str | None = None

    def to_dict(self) -> dict:
        return {
            "id": self.question_id,
            "question": self.question,
            "sessionId": self.session_id,
            "status": self.status,
            "durationSeconds": round(self.duration_seconds, 3),
            "answer": self.answer,
            "error": self.error,
        }


def extract_questions(markdown: str) -> list[QuestionSpec]:
    source = _before_browser_record(markdown)
    headings = list(QUESTION_HEADING.finditer(source))
    questions: list[QuestionSpec] = []
    for index, heading in enumerate(headings):
        start = heading.end()
        end = headings[index + 1].start() if index + 1 < len(headings) else len(source)
        block = source[start:end]
        questions.append(
            QuestionSpec(
                id=heading.group(1),
                title=heading.group(2).strip(),
                category=_field(block, "题型"),
                difficulty=_field(block, "难度"),
                question=_field(block, "题目"),
                expected_hits=_parse_inline_values(_field(block, "期望命中商品")),
                answer_points=_parse_answer_points(block),
            )
        )
    return questions


def export_questions_file(source: Path, output: Path) -> int:
    questions = extract_questions(source.read_text(encoding="utf-8"))
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "source": "docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md",
        "count": len(questions),
        "questions": [question.to_dict() for question in questions],
    }
    output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return len(questions)


def post_react(
    endpoint: str,
    username: str,
    password: str,
    message: str,
    session_id: str,
    model_id: str,
    web_search_enabled: bool,
    question_id: str = "",
) -> EvalResult:
    fields = {
        "message": message,
        "sessionId": session_id,
        "modelId": model_id,
        "webSearchEnabled": "true" if web_search_enabled else "false",
    }
    body, content_type = build_multipart_form(fields)
    auth = b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    request = Request(
        endpoint,
        data=body,
        headers={
            "Authorization": f"Basic {auth}",
            "Content-Type": content_type,
        },
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=180) as response:
            answer = response.read().decode("utf-8", errors="replace")
            status = response.status
            error = None
    except HTTPError as ex:
        answer = ex.read().decode("utf-8", errors="replace")
        status = ex.code
        error = f"HTTP {ex.code}"
    except URLError as ex:
        answer = ""
        status = 0
        error = str(ex.reason)
    duration = time.perf_counter() - started
    return EvalResult(
        question_id=question_id,
        question=message,
        session_id=session_id,
        status=status,
        duration_seconds=duration,
        answer=answer,
        error=error,
    )


def build_multipart_form(fields: dict[str, str]) -> tuple[bytes, str]:
    boundary = f"----ragagent-{uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.append(f"--{boundary}\r\n".encode("ascii"))
        chunks.append(f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("ascii"))
        chunks.append(value.encode("utf-8"))
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("ascii"))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def _before_browser_record(markdown: str) -> str:
    marker = "## 2026-06-12 浏览器实测记录"
    index = markdown.find(marker)
    return markdown[:index] if index >= 0 else markdown


def _field(block: str, label: str) -> str:
    pattern = re.compile(rf"^{re.escape(label)}：(.+)$", re.MULTILINE)
    match = pattern.search(block)
    if not match:
        return ""
    return _strip_code(match.group(1).strip())


def _strip_code(value: str) -> str:
    value = value.strip()
    match = re.fullmatch(r"`([^`]+)`", value)
    if match:
        return match.group(1).strip()
    return value


def _parse_inline_values(value: str) -> list[str]:
    values = [item.strip() for item in re.findall(r"`([^`]+)`", value)]
    if values:
        return values
    clean = value.strip()
    return [clean] if clean else []


def _parse_answer_points(block: str) -> list[str]:
    marker = "理想答案要点："
    start = block.find(marker)
    if start < 0:
        return []
    points: list[str] = []
    for line in block[start + len(marker):].splitlines():
        stripped = line.strip()
        if stripped.startswith("评分规则："):
            break
        if stripped.startswith("- "):
            points.append(stripped[2:].strip())
    return points
