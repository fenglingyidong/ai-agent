import json
import re
import subprocess
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


@dataclass(frozen=True)
class TraceSummary:
    session_id: str
    trace_id: str = ""
    input: str = ""
    output: str = ""
    observation_names: list[str] | None = None
    rag_count: int = 0
    mall_enrich_count: int = 0
    tool_count: int = 0
    output_has_why: int = 0
    router_output: str = ""
    error: str | None = None

    def to_dict(self) -> dict:
        return {
            "session_id": self.session_id,
            "trace_id": self.trace_id,
            "input": self.input,
            "output": self.output,
            "observation_names": self.observation_names or [],
            "rag_count": self.rag_count,
            "mall_enrich_count": self.mall_enrich_count,
            "tool_count": self.tool_count,
            "output_has_why": self.output_has_why,
            "router_output": self.router_output,
            "error": self.error,
        }


@dataclass(frozen=True)
class ScoredQuestion:
    id: str
    category: str
    question: str
    answer: str
    score: int
    reason: str
    diagnosis: str
    manual_review_required: bool
    route_task_type: str
    mall_tool_count: int
    rag_span_count: int
    mall_enrich_count: int
    mall_tools: list[str]

    def to_dict(self) -> dict:
        return {
            "id": self.id,
            "category": self.category,
            "question": self.question,
            "answer": self.answer,
            "score": self.score,
            "reason": self.reason,
            "diagnosis": self.diagnosis,
            "manualReviewRequired": self.manual_review_required,
            "routeTaskType": self.route_task_type,
            "mallToolCount": self.mall_tool_count,
            "ragSpanCount": self.rag_span_count,
            "mallEnrichCount": self.mall_enrich_count,
            "mallTools": self.mall_tools,
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


def collect_trace_summaries(rows: list[dict]) -> list[TraceSummary]:
    summaries: list[TraceSummary] = []
    for row in rows:
        summaries.append(
            TraceSummary(
                session_id=str(row.get("session_id", "")),
                trace_id=str(row.get("trace_id", "")),
                input=str(row.get("input", "")),
                output=str(row.get("output", "")),
                observation_names=_as_string_list(row.get("observation_names", [])),
                rag_count=int(row.get("rag_count") or 0),
                mall_enrich_count=int(row.get("mall_enrich_count") or 0),
                tool_count=int(row.get("tool_count") or 0),
                output_has_why=int(row.get("output_has_why") or 0),
                router_output=str(row.get("router_output", "")),
                error=row.get("error"),
            )
        )
    return summaries


def write_trace_summary_jsonl(summaries: list[TraceSummary], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps(summary.to_dict(), ensure_ascii=False) for summary in summaries]
    output.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


def score_run(
    run_id: str,
    questions: list[QuestionSpec],
    results: list[EvalResult],
    traces: list[TraceSummary],
) -> dict:
    result_by_id = {result.question_id: result for result in results}
    trace_by_session = {trace.session_id: trace for trace in traces}
    scored_questions: list[ScoredQuestion] = []
    for question in questions:
        result = result_by_id.get(question.id)
        if result is None:
            result = EvalResult(
                question_id=question.id,
                question=question.question,
                session_id="",
                status=0,
                duration_seconds=0,
                answer="",
                error="missing result",
            )
        trace = trace_by_session.get(result.session_id)
        scored_questions.append(score_question(question, result, trace))

    category_order: list[str] = []
    category_scores: dict[str, dict] = {}
    for scored in scored_questions:
        if scored.category not in category_scores:
            category_order.append(scored.category)
            category_scores[scored.category] = {"category": scored.category, "score": 0, "max": 0, "count": 0}
        category_scores[scored.category]["score"] += scored.score
        category_scores[scored.category]["max"] += 10
        category_scores[scored.category]["count"] += 1

    return {
        "runId": run_id,
        "totalScore": sum(scored.score for scored in scored_questions),
        "maxScore": len(scored_questions) * 10,
        "categoryScores": [category_scores[category] for category in category_order],
        "results": [scored.to_dict() for scored in scored_questions],
    }


def score_question(question: QuestionSpec, result: EvalResult, trace: TraceSummary | None) -> ScoredQuestion:
    answer = result.answer or ""
    normalized_answer = normalize_text(answer)
    expected_tokens = [_expected_token(hit) for hit in question.expected_hits]
    expected_tokens = [token for token in expected_tokens if token]
    matched = [token for token in expected_tokens if normalize_text(token) in normalized_answer]
    coverage = len(matched) / len(expected_tokens) if expected_tokens else 0.0

    only_followup = _is_followup_only(answer)
    external_generic = _has_external_generic_brand(answer)
    route_task_type = _route_task_type(trace.router_output if trace else "")
    observation_names = trace.observation_names if trace else []
    mall_tools = [name for name in observation_names if name.startswith("tool.mall_") or name == "tool.searchProductKnowledge"]
    rag_count = trace.rag_count if trace else 0
    tool_count = trace.tool_count if trace else 0
    mall_enrich_count = trace.mall_enrich_count if trace else 0

    score = 0
    reasons: list[str] = []
    if result.status == 200 and answer.strip():
        score += 1
    else:
        reasons.append(result.error or f"HTTP {result.status}")

    if coverage >= 0.8:
        score += 5
        reasons.append("命中多数期望商品/规格/数字")
    elif coverage >= 0.4:
        score += 3
        reasons.append("命中部分期望商品/规格/数字")
    elif coverage > 0:
        score += 1
        reasons.append("仅命中少量期望信息")
    else:
        reasons.append("未命中期望商品/规格/数字")

    if rag_count > 0 or tool_count > 0 or mall_enrich_count > 0:
        score += 2
        reasons.append("链路包含 RAG 或工具证据")
    elif trace is None:
        reasons.append("缺少 Langfuse trace")
    else:
        reasons.append("未看到 RAG 或工具证据")

    if _responsive_answer(answer, question):
        score += 2
    else:
        reasons.append("未直接回答核心问题")

    if only_followup:
        score = min(score, 3)
        reasons.append("只追问，未先给结论")
    if external_generic:
        score = min(score, 5)
        reasons.append("包含知识库外通用品牌或泛化建议")
    if result.status != 200:
        score = min(score, 2)

    manual_review_required = (
        trace is None
        or question.category in {"复杂导购题", "商品对比题"}
        or only_followup
        or external_generic
        or result.status != 200
    )
    diagnosis = _diagnosis(route_task_type, rag_count, tool_count, mall_enrich_count, only_followup, external_generic)
    return ScoredQuestion(
        id=question.id,
        category=question.category,
        question=question.question,
        answer=answer,
        score=max(0, min(10, score)),
        reason="；".join(reasons) if reasons else "自动初评未发现明显问题",
        diagnosis=diagnosis,
        manual_review_required=manual_review_required,
        route_task_type=route_task_type,
        mall_tool_count=tool_count,
        rag_span_count=rag_count,
        mall_enrich_count=mall_enrich_count,
        mall_tools=mall_tools,
    )


def query_trace_summaries(session_ids: list[str], runner=None) -> list[TraceSummary]:
    if not session_ids:
        return []
    sql = build_trace_summary_sql(session_ids)
    rows = runner(sql) if runner else query_clickhouse_json_rows(sql)
    return collect_trace_summaries(rows)


def build_trace_summary_sql(session_ids: list[str]) -> str:
    values = ", ".join(_sql_string(session_id) for session_id in session_ids)
    return f"""
WITH target_traces AS (
    SELECT
        id,
        session_id,
        input,
        output
    FROM default.traces
    WHERE is_deleted = 0
      AND session_id IN ({values})
),
obs AS (
    SELECT
        trace_id,
        groupUniqArray(name) AS observation_names,
        countIf(startsWith(name, 'rag.')) AS rag_count,
        countIf(name = 'rag.mall_enrich') AS mall_enrich_count,
        countIf(startsWith(name, 'tool.')) AS tool_count,
        maxIf(output, name = 'llm.intent_router') AS router_output
    FROM default.observations
    WHERE is_deleted = 0
      AND trace_id IN (SELECT id FROM target_traces)
    GROUP BY trace_id
)
SELECT
    t.session_id AS session_id,
    t.id AS trace_id,
    ifNull(t.input, '') AS input,
    ifNull(t.output, '') AS output,
    ifNull(o.observation_names, []) AS observation_names,
    ifNull(o.rag_count, 0) AS rag_count,
    ifNull(o.mall_enrich_count, 0) AS mall_enrich_count,
    ifNull(o.tool_count, 0) AS tool_count,
    if(position(ifNull(t.output, ''), '为什么') > 0, 1, 0) AS output_has_why,
    ifNull(o.router_output, '') AS router_output
FROM target_traces t
LEFT JOIN obs o ON o.trace_id = t.id
ORDER BY t.session_id
FORMAT JSONEachRow
""".strip()


def query_clickhouse_json_rows(sql: str, run_command=None) -> list[dict]:
    command = [
        "docker",
        "compose",
        "-f",
        "observability/langfuse/docker-compose.yml",
        "exec",
        "-T",
        "clickhouse",
        "clickhouse-client",
        "--query",
        sql,
        "--format",
        "JSONEachRow",
    ]
    if run_command:
        stdout = run_command(command)
    else:
        completed = subprocess.run(command, check=True, capture_output=True, text=True, encoding="utf-8")
        stdout = completed.stdout
    return [json.loads(line) for line in stdout.splitlines() if line.strip()]


def _as_string_list(value) -> list[str]:
    if value is None:
        return []
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str):
        stripped = value.strip()
        if not stripped:
            return []
        try:
            parsed = json.loads(stripped)
        except json.JSONDecodeError:
            return [stripped]
        if isinstance(parsed, list):
            return [str(item) for item in parsed]
        return [str(parsed)]
    return [str(value)]


def _sql_string(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", "", value or "").lower()


def _expected_token(value: str) -> str:
    value = value.strip()
    value = re.sub(r"^优先\s*", "", value)
    value = re.sub(r"^可组合\s*", "", value)
    value = value.strip("；;，,。 ")
    return value


def _is_followup_only(answer: str) -> bool:
    normalized = normalize_text(answer)
    if not normalized:
        return False
    followup_markers = ["需要先确认", "我需要先了解", "请告诉我", "收到您的回复后", "为了给您提供"]
    conclusion_markers = ["推荐", "首推", "价格", "库存", "sku", "合计"]
    followup_count = sum(1 for marker in followup_markers if marker in answer)
    conclusion_count = sum(1 for marker in conclusion_markers if marker in normalized)
    return followup_count >= 1 and conclusion_count == 0


def _has_external_generic_brand(answer: str) -> bool:
    generic_terms = [
        "罗技",
        "微软",
        "雷柏",
        "迪卡侬",
        "挪客",
        "牧高笛",
        "李宁",
        "尤尼克斯",
        "索尼",
        "bose",
        "naturehike",
        "decathlon",
    ]
    normalized = normalize_text(answer)
    return any(normalize_text(term) in normalized for term in generic_terms)


def _route_task_type(router_output: str) -> str:
    if not router_output:
        return ""
    try:
        payload = json.loads(router_output)
    except json.JSONDecodeError:
        return ""
    return str(payload.get("task_type", ""))


def _responsive_answer(answer: str, question: QuestionSpec) -> bool:
    if _is_followup_only(answer):
        return False
    answer_norm = normalize_text(answer)
    if not answer_norm:
        return False
    if any(normalize_text(point[:8]) and normalize_text(point[:8]) in answer_norm for point in question.answer_points):
        return True
    return any(_expected_token(hit) and normalize_text(_expected_token(hit)) in answer_norm for hit in question.expected_hits)


def _diagnosis(
    route_task_type: str,
    rag_count: int,
    tool_count: int,
    mall_enrich_count: int,
    only_followup: bool,
    external_generic: bool,
) -> str:
    tags: list[str] = []
    if only_followup:
        tags.append("FOLLOWUP_ONLY")
    if external_generic:
        tags.append("GENERIC_EXTERNAL_KNOWLEDGE")
    if rag_count == 0 and tool_count == 0 and mall_enrich_count == 0:
        tags.append("NO_RAG_OR_TOOL_EVIDENCE")
    if route_task_type:
        tags.append(route_task_type)
    return " / ".join(tags) if tags else "AUTO_SCORE_OK"


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
