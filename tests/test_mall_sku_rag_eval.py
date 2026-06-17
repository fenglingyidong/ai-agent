import json
import subprocess
import sys
import tempfile
import threading
import unittest
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import StringIO
from pathlib import Path

from scripts.mall_sku_rag_eval_lib import (
    collect_trace_summaries,
    EvalResult,
    export_questions_file,
    extract_questions,
    query_clickhouse_json_rows,
    post_react,
    query_trace_summaries,
    QuestionSpec,
    score_question,
    score_run,
    TraceSummary,
    write_trace_summary_jsonl,
)
from scripts.run_mall_sku_rag_eval import main


class MallSkuRagEvalTest(unittest.TestCase):

    def test_extract_questions_stops_before_browser_section(self):
        markdown = """
### A. 单场景推荐题

#### Q07. 推荐露营椅

题目：`周末露营想买把椅子，主要想轻便好带，推荐哪款？`

题型：单场景推荐题

难度：中等

期望命中商品：`4013_折叠露营椅 轻量款 卡其色`，价格 `139.00 元`

理想答案要点：

- 推荐折叠露营椅轻量款卡其色。
- 说明轻便好带更符合用户需求。

## 2026-06-12 浏览器实测记录

#### Q99. 不应解析
题目：`旧记录`
"""
        questions = extract_questions(markdown)

        self.assertEqual(["Q07"], [q.id for q in questions])
        self.assertEqual("单场景推荐题", questions[0].category)
        self.assertEqual("中等", questions[0].difficulty)
        self.assertEqual(
            ["4013_折叠露营椅 轻量款 卡其色", "139.00 元"],
            questions[0].expected_hits,
        )
        self.assertEqual(
            ["推荐折叠露营椅轻量款卡其色。", "说明轻便好带更符合用户需求。"],
            questions[0].answer_points,
        )

    def test_export_questions_file_writes_json(self):
        markdown = """
#### Q08. 推荐办公室安静鼠标

题目：`办公室用鼠标，希望点击声音小一点，买哪款？`

题型：单场景推荐题

难度：简单

期望命中商品：`3004_无线鼠标 静音版`，价格 `89.00 元`，库存 `500 件`

理想答案要点：

- 首推无线鼠标静音版。
- 说明适合办公室、点击声音小、预算友好。
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "eval.md"
            output = Path(temp_dir) / "questions.json"
            source.write_text(markdown, encoding="utf-8")

            count = export_questions_file(source, output)

            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(1, count)
            self.assertEqual("docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md", data["source"])
            self.assertEqual("Q08", data["questions"][0]["id"])
            self.assertEqual("办公室用鼠标，希望点击声音小一点，买哪款？", data["questions"][0]["question"])

    def test_export_questions_command_runs_by_script_path(self):
        markdown = """
#### Q01. 查询跑鞋价格和库存

题目：`轻量跑步鞋 42 码现在多少钱？库存够不够？`

题型：简单事实题

难度：简单

期望命中商品：`2001_轻量跑步鞋 42码`，价格 `399.00 元`

理想答案要点：

- 明确回答轻量跑步鞋 42 码价格为 399.00 元。
"""
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "eval.md"
            output = Path(temp_dir) / "questions.json"
            source.write_text(markdown, encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    "scripts/run_mall_sku_rag_eval.py",
                    "export-questions",
                    "--source",
                    str(source),
                    "--output",
                    str(output),
                ],
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

            self.assertEqual("", completed.stderr)
            self.assertEqual(0, completed.returncode)
            self.assertTrue(output.exists())

    def test_post_react_builds_multipart_and_basic_auth(self):
        captured = {}

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                captured["path"] = self.path
                captured["auth"] = self.headers.get("Authorization")
                captured["content_type"] = self.headers.get("Content-Type")
                content_length = int(self.headers.get("Content-Length", "0"))
                captured["body"] = self.rfile.read(content_length)
                self.send_response(200)
                self.end_headers()
                self.wfile.write("工具结果事实：静音鼠标。".encode("utf-8"))

            def log_message(self, format, *args):
                return

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.daemon = True
        thread.start()
        try:
            url = f"http://127.0.0.1:{server.server_port}/api/react"
            result = post_react(
                endpoint=url,
                username="alice",
                password="demo123",
                message="办公室用鼠标，希望点击声音小一点，买哪款？",
                session_id="unit-Q08",
                model_id="qwen",
                web_search_enabled=False,
            )
        finally:
            server.shutdown()
            thread.join(timeout=5)
            server.server_close()

        self.assertEqual(200, result.status)
        self.assertEqual("工具结果事实：静音鼠标。", result.answer)
        self.assertEqual("/api/react", captured["path"])
        self.assertEqual("Basic YWxpY2U6ZGVtbzEyMw==", captured["auth"])
        self.assertTrue(captured["content_type"].startswith("multipart/form-data; boundary="))
        self.assertIn(b'name="message"', captured["body"])
        self.assertIn("办公室用鼠标，希望点击声音小一点，买哪款？".encode("utf-8"), captured["body"])
        self.assertIn(b'name="sessionId"', captured["body"])
        self.assertIn(b"unit-Q08", captured["body"])
        self.assertIn(b'name="modelId"', captured["body"])
        self.assertIn(b"qwen", captured["body"])
        self.assertIn(b'name="webSearchEnabled"', captured["body"])
        self.assertIn(b"false", captured["body"])

    def test_collect_trace_summaries_merges_json_rows(self):
        rows = [
            {
                "session_id": "api-20260617-092529-why-summary-Q08",
                "trace_id": "0d5983d9b875516ecfb5f246adcc2d42",
                "input": "办公室用鼠标，希望点击声音小一点，买哪款？",
                "output": "Mall Labs 无线鼠标 静音版",
                "observation_names": ["llm.intent_router", "rag.hybrid.retrieve", "tool.mall_search_products"],
                "rag_count": 14,
                "mall_enrich_count": 2,
                "tool_count": 1,
                "output_has_why": 0,
                "router_output": '{"task_type":"COMPLEX_REACT"}',
            }
        ]

        summaries = collect_trace_summaries(rows)

        self.assertEqual("api-20260617-092529-why-summary-Q08", summaries[0].session_id)
        self.assertEqual("0d5983d9b875516ecfb5f246adcc2d42", summaries[0].trace_id)
        self.assertEqual(14, summaries[0].rag_count)
        self.assertEqual(1, summaries[0].tool_count)
        self.assertIn("tool.mall_search_products", summaries[0].observation_names)

    def test_write_trace_summary_jsonl_writes_one_json_per_line(self):
        summaries = collect_trace_summaries(
            [
                {
                    "session_id": "unit-Q08",
                    "trace_id": "trace-1",
                    "input": "问题",
                    "output": "答案",
                    "observation_names": ["llm.intent_router"],
                    "rag_count": 0,
                    "mall_enrich_count": 0,
                    "tool_count": 0,
                    "output_has_why": 0,
                    "router_output": '{"task_type":"FAQ_SIMPLE_QUERY"}',
                }
            ]
        )
        with tempfile.TemporaryDirectory() as temp_dir:
            output = Path(temp_dir) / "trace.jsonl"

            write_trace_summary_jsonl(summaries, output)

            lines = output.read_text(encoding="utf-8").splitlines()
            self.assertEqual(1, len(lines))
            self.assertEqual("unit-Q08", json.loads(lines[0])["session_id"])

    def test_query_trace_summaries_uses_requested_sessions(self):
        captured = {}

        def runner(sql):
            captured["sql"] = sql
            return [
                {
                    "session_id": "unit-Q08",
                    "trace_id": "trace-1",
                    "input": "问题",
                    "output": "答案",
                    "observation_names": '["rag.hybrid.retrieve"]',
                    "rag_count": 1,
                    "mall_enrich_count": 0,
                    "tool_count": 0,
                    "output_has_why": 0,
                    "router_output": "",
                }
            ]

        summaries = query_trace_summaries(["unit-Q08", "quote's"], runner=runner)

        self.assertEqual(1, len(summaries))
        self.assertIn("'unit-Q08'", captured["sql"])
        self.assertIn("'quote\\'s'", captured["sql"])
        self.assertEqual(["rag.hybrid.retrieve"], summaries[0].observation_names)

    def test_query_clickhouse_json_rows_parses_json_each_row(self):
        def run_command(command):
            self.assertIn("--format", command)
            return '{"a":1}\n{"a":2}\n'

        rows = query_clickhouse_json_rows("SELECT 1", run_command=run_command)

        self.assertEqual([{"a": 1}, {"a": 2}], rows)

    def test_score_question_gives_high_score_for_expected_hit_and_trace(self):
        question = QuestionSpec(
            id="Q08",
            title="推荐办公室安静鼠标",
            category="单场景推荐题",
            difficulty="简单",
            question="办公室用鼠标，希望点击声音小一点，买哪款？",
            expected_hits=["3004_无线鼠标 静音版", "89.00 元", "500 件"],
            answer_points=["首推无线鼠标静音版。", "说明适合办公室、点击声音小、预算友好。"],
        )
        result = EvalResult(
            question_id="Q08",
            question=question.question,
            session_id="unit-Q08",
            status=200,
            duration_seconds=1.2,
            answer="Mall Labs 无线鼠标 静音版 (SKU: 3004) 价格 89.00 元，库存 500 件，适合办公室静音使用。",
        )
        trace = TraceSummary(
            session_id="unit-Q08",
            trace_id="trace-1",
            observation_names=["llm.intent_router", "rag.hybrid.retrieve", "tool.mall_search_products"],
            rag_count=7,
            mall_enrich_count=1,
            tool_count=1,
            router_output='{"task_type":"COMPLEX_REACT"}',
        )

        scored = score_question(question, result, trace)

        self.assertGreaterEqual(scored.score, 8)
        self.assertFalse(scored.manual_review_required)
        self.assertEqual("COMPLEX_REACT", scored.route_task_type)

    def test_score_question_marks_generic_followup_low_and_manual(self):
        question = QuestionSpec(
            id="Q25",
            title="模糊需求：桌面效率升级",
            category="复杂导购题",
            difficulty="较难",
            question="我想把办公桌面升级一下，预算 400 左右，能不能给我配一套实用的？",
            expected_hits=["3002_机械键盘 红轴 87键", "3004_无线鼠标 静音版", "4009_桌面显示器挂灯 标准版"],
            answer_points=["给出一套预算内组合，不只是推荐一个商品。", "合计价格应接近且不超过 400 元。"],
        )
        result = EvalResult(
            question_id="Q25",
            question=question.question,
            session_id="unit-Q25",
            status=200,
            duration_seconds=1.0,
            answer="为了给您提供最精准的配置清单，我需要先确认主要用途、现有设备和风格偏好。",
        )
        trace = TraceSummary(
            session_id="unit-Q25",
            trace_id="trace-2",
            observation_names=["llm.react"],
            rag_count=0,
            tool_count=0,
            router_output='{"task_type":"COMPLEX_REACT"}',
        )

        scored = score_question(question, result, trace)

        self.assertLessEqual(scored.score, 3)
        self.assertTrue(scored.manual_review_required)
        self.assertIn("只追问", scored.reason)

    def test_score_run_summarizes_categories(self):
        question = QuestionSpec(
            id="Q08",
            title="推荐办公室安静鼠标",
            category="单场景推荐题",
            difficulty="简单",
            question="办公室用鼠标，希望点击声音小一点，买哪款？",
            expected_hits=["3004_无线鼠标 静音版"],
            answer_points=[],
        )
        result = EvalResult(
            question_id="Q08",
            question=question.question,
            session_id="unit-Q08",
            status=200,
            duration_seconds=1.0,
            answer="无线鼠标 静音版 3004",
        )

        scored = score_run("unit", [question], [result], [])

        self.assertEqual("unit", scored["runId"])
        self.assertEqual(10, scored["maxScore"])
        self.assertEqual("单场景推荐题", scored["categoryScores"][0]["category"])
        self.assertEqual("Q08", scored["results"][0]["id"])

    def test_main_run_keeps_requested_subset_order(self):
        questions_payload = {
            "source": "unit",
            "count": 2,
            "questions": [
                {
                    "id": "Q07",
                    "title": "推荐通勤降噪耳机",
                    "category": "单场景推荐题",
                    "difficulty": "中等",
                    "question": "我每天坐地铁通勤，想买个降噪耳机，推荐哪款？",
                    "expectedHits": ["1001_旗舰降噪耳机 黑色"],
                    "answerPoints": [],
                },
                {
                    "id": "Q08",
                    "title": "推荐办公室安静鼠标",
                    "category": "单场景推荐题",
                    "difficulty": "简单",
                    "question": "办公室用鼠标，希望点击声音小一点，买哪款？",
                    "expectedHits": ["3004_无线鼠标 静音版"],
                    "answerPoints": [],
                },
            ],
        }
        captured = {}

        def fake_post(**kwargs):
            captured.setdefault("ids", []).append(kwargs["question_id"])
            return EvalResult(
                question_id=kwargs["question_id"],
                question=kwargs["message"],
                session_id=kwargs["session_id"],
                status=200,
                duration_seconds=0.1,
                answer=f"answer {kwargs['question_id']}",
            )

        def fake_trace(session_ids):
            captured["session_ids"] = session_ids
            return [
                TraceSummary(session_id=session_id, trace_id=f"trace-{session_id}")
                for session_id in session_ids
            ]

        with tempfile.TemporaryDirectory() as temp_dir:
            questions = Path(temp_dir) / "questions.json"
            output_dir = Path(temp_dir) / "out"
            questions.write_text(json.dumps(questions_payload, ensure_ascii=False), encoding="utf-8")

            with redirect_stdout(StringIO()):
                code = main(
                    [
                        "run",
                        "--ids",
                        "Q08,Q07",
                        "--run-id",
                        "unit",
                        "--questions",
                        str(questions),
                        "--output-dir",
                        str(output_dir),
                    ],
                    post_func=fake_post,
                    trace_func=fake_trace,
                )

            self.assertEqual(0, code)
            self.assertEqual(["Q08", "Q07"], captured["ids"])
            self.assertEqual(["unit-Q08", "unit-Q07"], captured["session_ids"])
            results = json.loads((output_dir / "unit-mall-sku-rag-eval-results.json").read_text(encoding="utf-8"))
            self.assertEqual(["Q08", "Q07"], [item["id"] for item in results["results"]])
            self.assertTrue((output_dir / "unit-langfuse-trace-summary.jsonl").exists())
            self.assertTrue((output_dir / "unit-mall-sku-rag-eval-scored.json").exists())


if __name__ == "__main__":
    unittest.main()
