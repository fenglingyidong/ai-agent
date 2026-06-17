import json
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from scripts.mall_sku_rag_eval_lib import (
    collect_trace_summaries,
    export_questions_file,
    extract_questions,
    query_clickhouse_json_rows,
    post_react,
    query_trace_summaries,
    write_trace_summary_jsonl,
)


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


if __name__ == "__main__":
    unittest.main()
