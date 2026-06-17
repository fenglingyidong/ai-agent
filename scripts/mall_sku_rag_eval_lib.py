import json
import re
from dataclasses import dataclass
from pathlib import Path


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
