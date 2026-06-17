# 电商导购 RAG 评测程序设计

## 目录

- [目标](#目标)
- [输入与输出](#输入与输出)
- [程序行为](#程序行为)
- [Langfuse 链路采集](#langfuse-链路采集)
- [评分策略](#评分策略)
- [错误处理](#错误处理)
- [测试与验证](#测试与验证)

## 目标

新增一个本地评测程序，把 `docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md` 中的题目转换为 JSON 题库，并支持按传入题号子集调用已启动的 RAGAgent 后端完成部分评测。

程序不负责启动 RAGAgent、商城后端、mall-mcp 或 Langfuse。调用账号默认使用 `alice / demo123`。

## 输入与输出

输入：

- 评测题库 JSON：从现有 Markdown 评测集生成。
- 题号子集：例如 `Q07,Q08,Q25`。
- 后端接口：默认 `http://localhost:18082/api/react`。
- 登录信息：默认 `alice / demo123`。
- 模型和联网开关：默认 `modelId=qwen`、`webSearchEnabled=false`。

输出写入 `docs/evaluation/artifacts/`：

- `RUN_ID-mall-sku-rag-eval-results.json`：接口调用结果、回答、耗时和错误。
- `RUN_ID-langfuse-trace-summary.jsonl`：按题聚合的 Langfuse trace 摘要，格式参考现有 `api-20260617-092529-why-summary-langfuse-trace-summary.jsonl`。
- `RUN_ID-mall-sku-rag-eval-scored.json`：按评测规则生成的评分草稿、分类汇总和链路诊断字段。

题库文件建议为：

- `docs/evaluation/artifacts/mall-sku-rag-guide-eval-questions.json`

## 程序行为

使用 Python 脚本实现，放在 `scripts/run_mall_sku_rag_eval.py`。

核心流程：

1. 读取题库 JSON。
2. 校验传入题号子集，保持用户输入顺序。
3. 为每题生成独立 `sessionId`，形如 `RUN_ID-Q07`。
4. 用 HTTP Basic Auth 调用 `/api/react` multipart 接口。
5. 保存完整文本回答、HTTP 状态、耗时和错误。
6. 等待 Langfuse 写入完成后，按 sessionId 从本地 ClickHouse 查询 trace。
7. 聚合链路摘要并写入 JSONL。
8. 结合题库期望和 trace 摘要生成评分草稿 JSON。

脚本参数保持极简：

- `--ids Q07,Q08`：必填，指定题号子集。
- `--endpoint`：可选，默认本地后端。
- `--username`、`--password`：可选，默认 `alice`、`demo123`。
- `--model-id`：可选，默认 `qwen`。
- `--run-id`：可选，不传则用时间戳生成。
- `--langfuse-clickhouse-url`：可选，默认通过本地 Docker ClickHouse HTTP 端口或容器内执行查询。

## Langfuse 链路采集

第一版使用本地 Langfuse ClickHouse，不接 Langfuse Web 登录页面和公开 API。

采集目标：

- 顶层 trace id。
- 输入输出。
- observation 名称列表。
- `rag.*` span 数量。
- `rag.mall_enrich` 数量。
- `tool.*` 或商城工具调用数量。
- `llm.intent_router` 输出中的 `task_type`。
- 可用时提取工具名和关键 payload 片段。

如果 ClickHouse 不可用，程序仍保留接口结果，并在 trace summary 中标记错误；评分结果中的链路字段置空或为 0，`manualReviewRequired=true`。

## 评分策略

评分以 `docs/evaluation/2026-06-10-mall-sku-rag-guide-eval.md` 的 10 分制为准。

第一版采用可解释的规则评分草稿：

- 根据期望命中商品、SKU、价格、库存、容量、规格等关键词判断答案覆盖。
- 根据 trace 判断是否调用 RAG、商城工具或 simple task。
- 检测只追问、通用品牌建议、知识库外品牌、外部参数泛化等明显问题。
- 对组合题检查总价和预算约束的关键数字。
- 输出 `score`、`reason`、`diagnosis`、`manualReviewRequired`。

因为自然语言答案存在变体，脚本评分定位为“自动初评”。对复杂导购题、缺少 trace 或存在边界判断的题目，必须标记 `manualReviewRequired=true`，方便人工复核。

## 错误处理

- 后端未启动：该题记录 HTTP 错误或连接异常，不中断后续题。
- 认证失败：记录 401，并建议检查账号密码。
- 题号不存在：启动前失败，提示可用题号。
- Langfuse 延迟写入：支持等待和重试。
- ClickHouse 查询失败：保留 API 结果，trace summary 写失败原因。
- 工作区已有未提交改动：脚本不修改业务文件，只写 artifacts。

## 测试与验证

最小验证：

```powershell
python scripts/run_mall_sku_rag_eval.py --ids Q08
```

预期：

- 生成 3 个评测产物文件。
- `results.json` 包含 Q08 的回答和 `sessionId`。
- `trace-summary.jsonl` 至少包含 Q08 一行；如果 Langfuse 未启动，应有明确错误。
- `scored.json` 包含 Q08 分数草稿、分类汇总和 `manualReviewRequired` 字段。

离线验证：

- 题库 JSON 可从 Markdown 生成并包含 30 题。
- 评分函数可基于已有 `api-20260617-092529-why-summary-mall-sku-rag-target-results.json` 与 trace summary 样例运行。
