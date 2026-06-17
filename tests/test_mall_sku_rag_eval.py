import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.mall_sku_rag_eval_lib import export_questions_file, extract_questions


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


if __name__ == "__main__":
    unittest.main()
