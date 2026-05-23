# 电商导购离线评测最小闭环设计

## 目录

- [1. 总览](#1-总览)
- [2. 目标与非目标](#2-目标与非目标)
- [3. 输入样例格式](#3-输入样例格式)
- [4. 评测链路](#4-评测链路)
- [5. 规则评测指标](#5-规则评测指标)
- [6. LLM Judge 指标](#6-llm-judge-指标)
- [7. 输出报告](#7-输出报告)
- [8. 配置与运行](#8-配置与运行)
- [9. 错误处理](#9-错误处理)
- [10. 测试策略](#10-测试策略)
- [11. 后续扩展](#11-后续扩展)

## 1. 总览

本设计新增一个电商导购离线评测最小闭环，用于评估当前多模态电商导购 Agent 在自建样例集上的路由、工具决策、回答规则和可选回答质量。

第一版采用进程内 `shopping-eval` Runner：读取 JSONL 样例，复用现有 Spring Bean 调用 `ShoppingIntentRouter` 和 `ReActAgent`，先执行确定性规则评测，再按配置可选调用 LLM Judge 打分，最终输出 JSONL 明细和 Markdown 汇总。

核心链路：

```text
eval/shopping-eval.jsonl
-> ShoppingEvaluationRunner
-> ShoppingIntentRouter 路由评测
-> ReActAgent 生成回答
-> 规则评测
-> 可选 LLM Judge
-> reports/shopping-eval-*.jsonl
-> reports/shopping-eval-*.md
```

## 2. 目标与非目标

### 2.1 目标

- 跑通一套 30-100 条自建电商导购样例的离线评测闭环。
- 同时覆盖路由结果、工具候选、风险等级、追问/确认策略和最终回答文本。
- LLM Judge 默认可关闭，避免每次评测都产生额外模型成本。
- 评测输出既能被人阅读，也能被脚本继续分析。
- 复用现有 `ReActAgent`、`ShoppingIntentRouter`、模型注册、记忆和工具配置，不另起一套 Agent。

### 2.2 非目标

- 第一版不接入 ESCI、SQID 或 Amazon Reviews 这类外部大规模数据集。
- 第一版不建设前端报表或 Grafana 看板。
- 第一版不强制启动真实商城 MCP；样例可通过期望值标记 MCP 不可用时的失败语义。
- 第一版不做自动回归门禁，只提供可手动运行的评测入口。

## 3. 输入样例格式

样例文件使用 UTF-8 JSONL，每行一个评测样例。默认路径为 `eval/shopping-eval.jsonl`。

最小字段：

```json
{
  "id": "cart-add-missing-sku-001",
  "userId": "eval-user",
  "sessionId": "eval-session-001",
  "userMessage": "帮我买一双 500 以内的通勤鞋",
  "webSearchEnabled": false,
  "expected": {
    "taskType": "C_COMPLEX_REACT",
    "intent": "PRODUCT_RECOMMENDATION",
    "missingSlots": ["skuId"],
    "toolCandidates": ["mall_search_products"],
    "riskLevel": "MEDIUM",
    "shouldAskClarification": true,
    "requiredTexts": ["预算", "通勤"],
    "forbiddenTexts": ["已加入购物车", "下单成功"]
  }
}
```

字段说明：

- `id`：样例唯一标识。
- `userId`：评测用户，默认 `eval-user`。
- `sessionId`：评测会话，缺省时由 Runner 使用 `id` 派生，避免样例之间互相污染记忆。
- `userMessage`：输入给导购 Agent 的用户问题。
- `webSearchEnabled`：是否启用外部联网工具，默认 `false`。
- `expected.taskType`：期望路由类型，例如 `A_FAQ_SIMPLE_QUERY`、`B_SIMPLE_SHOPPING_TOOL`、`C_COMPLEX_REACT`。
- `expected.intent`：期望意图。
- `expected.missingSlots`：期望缺失槽位，按包含关系评测。
- `expected.toolCandidates`：期望工具候选，按包含关系评测。
- `expected.riskLevel`：期望风险等级。
- `expected.shouldAskClarification`：回答是否应该追问补槽。
- `expected.requiredTexts`：回答必须包含的关键词或短语。
- `expected.forbiddenTexts`：回答不得包含的关键词或短语。

## 4. 评测链路

### 4.1 Runner

新增 `ShoppingEvaluationRunner`，使用 `@Profile("shopping-eval")` 激活。它负责加载样例、逐条运行评测、聚合指标并写出报告。

### 4.2 路由评测

Runner 对每条样例先调用 `ShoppingIntentRouter.route(userMessage, media)`，拿到 `ShoppingIntentRoute` 后和 `expected` 做规则比对。

本阶段只评路由结构化输出，不执行工具。

### 4.3 Agent 回答评测

Runner 调用 `ReActAgent.runStream(...)`，收集完整回答文本并记录耗时。评测会给每条样例使用独立 `sessionId`，避免短期记忆跨样例干扰。

如商城 MCP 未启动且样例触发实时商城能力，允许回答为“商城 MCP 调用失败”，并由样例的 `expected` 决定该结果是否通过。

### 4.4 规则评审

规则评审只依赖路由输出、最终回答和耗时，保持稳定可复现。规则评审结果是第一版评测的主结论。

### 4.5 LLM Judge

当 `app.shopping-eval.llm-judge-enabled=true` 时，Runner 使用独立 Judge Prompt 调用模型，对最终回答做质量评分。Judge 输出结构化 JSON，解析失败时记录为 Judge 失败，但不影响规则评审继续完成。

LLM Judge 不参与工具执行，不改写 Agent 回答，只作为旁路质检。

## 5. 规则评测指标

单样例规则指标：

- `routeTaskTypeHit`：实际 `taskType` 是否等于期望值。
- `routeIntentHit`：实际 `intent` 是否等于期望值。
- `missingSlotsHit`：实际缺失槽位是否包含期望缺失槽位。
- `toolCandidatesHit`：实际工具候选是否包含期望工具候选。
- `riskLevelHit`：实际风险等级是否等于期望值。
- `requiredTextHit`：回答是否包含所有 `requiredTexts`。
- `forbiddenTextHit`：回答是否没有出现任何 `forbiddenTexts`。
- `clarificationHit`：需要追问时，回答是否体现追问或补槽意图。
- `latencyMs`：单样例端到端耗时。
- `passed`：关键规则是否全部通过。

聚合指标：

- `totalSamples`
- `passedSamples`
- `passRate`
- `routeTaskTypeAccuracy`
- `routeIntentAccuracy`
- `toolCandidatesHitRate`
- `riskLevelAccuracy`
- `requiredTextHitRate`
- `forbiddenTextHitRate`
- `clarificationHitRate`
- `avgLatencyMs`
- `p95LatencyMs`

## 6. LLM Judge 指标

Judge 默认关闭。开启后，每条样例输出：

- `relevanceScore`：回答是否贴合用户问题，1-5 分。
- `groundednessScore`：回答是否基于输入、工具结果或已知上下文，1-5 分。
- `policyComplianceScore`：是否遵守导购策略、加购确认和不编造规则，1-5 分。
- `answerHelpfulnessScore`：回答是否清楚、有帮助、可执行，1-5 分。
- `hallucinationRisk`：`LOW`、`MEDIUM`、`HIGH`。
- `judgePassed`：综合是否通过。
- `judgeReason`：简短原因。

Judge Prompt 约束：

- 只评估最终回答，不要求模型猜测隐藏工具调用。
- 对价格、库存、订单状态等实时信息，如果回答没有工具证据却给出确定结论，应降低 groundedness 和 policy compliance。
- 对应追问却直接加购或下单的回答，应判定为高风险。
- 输出必须是 JSON 对象，禁止自然语言前后缀。

## 7. 输出报告

### 7.1 JSONL 明细

默认输出到 `reports/shopping-eval-<timestamp>.jsonl`。

每行包含：

- `id`
- `userMessage`
- `expected`
- `actualRoute`
- `answer`
- `ruleMetrics`
- `judgeMetrics`
- `latencyMs`
- `error`

### 7.2 Markdown 汇总

默认输出到 `reports/shopping-eval-<timestamp>.md`。

内容结构：

- 本次配置
- 总体通过率
- 路由指标
- 回答规则指标
- LLM Judge 指标，未开启时显示“未启用”
- 失败样例列表
- 高风险样例列表

## 8. 配置与运行

新增 `application-shopping-eval.yml`：

```yaml
app:
  shopping-eval:
    input-file: eval/shopping-eval.jsonl
    output-dir: reports
    max-examples: 50
    llm-judge-enabled: false
    judge-model: ${SHOPPING_EVAL_JUDGE_MODEL:qwen-plus-2025-07-28}
    fail-fast: false
    log-answers: false
```

运行命令：

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=shopping-eval"
```

开启 LLM Judge：

```powershell
$env:SHOPPING_EVAL_LLM_JUDGE_ENABLED="true"
mvn spring-boot:run "-Dspring-boot.run.profiles=shopping-eval"
```

如果后续实现采用 Spring Boot 配置绑定，环境变量名按 Spring relaxed binding 支持 `SHOPPING_EVAL_LLM_JUDGE_ENABLED`。

## 9. 错误处理

- 样例 JSON 解析失败：记录错误并跳过该行。
- 必填字段缺失：记录为无效样例，不进入分母。
- 路由模型异常：该样例记录 `routeError`，规则路由指标判失败。
- Agent 调用异常：该样例记录 `agentError`，回答规则判失败。
- Judge 关闭：`judgeMetrics` 为空，Markdown 明确标注未启用。
- Judge 解析失败：记录 `judgeError`，不影响规则评测结果。
- 输出目录不存在：Runner 自动创建。

## 10. 测试策略

单元测试覆盖：

- JSONL 样例解析和默认值处理。
- 规则评测器对命中、缺失、禁词、追问和风险等级的判断。
- Markdown 汇总渲染。
- Judge JSON 解析成功和失败分支。

集成测试覆盖：

- 使用 mock `ShoppingIntentRouter`、mock `ReActAgent` 和关闭 Judge 的 Runner，验证能输出 JSONL 与 Markdown。
- 使用 mock Judge 返回固定 JSON，验证开启 Judge 后指标被写入报告。

不在第一版自动化测试中真实调用外部模型、Milvus、Redis 或商城 MCP。

## 11. 后续扩展

- 接入 ESCI/SQID，补充商品检索与排序评测。
- 记录工具调用轨迹后，增加真实工具调用准确率，而不是只评 `toolCandidates`。
- 增加多轮样例，评估短期记忆、长期记忆和偏好状态。
- 增加基线对比，例如不同模型、不同路由阈值、不同任务策略模板。
- 将 Markdown 汇总接入 CI artifact 或后续看板。
