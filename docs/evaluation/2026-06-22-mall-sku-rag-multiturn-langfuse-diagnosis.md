# 2026-06-22 Mall SKU RAG 多轮评测 Langfuse 排查

## 结果索引

- 有效 runId: `api-20260622-113527-multiturn-traced`
- 评测集: `docs/evaluation/2026-06-22-mall-sku-rag-multiturn-eval.md`
- 原始回答: `docs/evaluation/artifacts/api-20260622-113527-multiturn-traced-mall-sku-rag-multiturn-results.json`
- Langfuse traces: `docs/evaluation/artifacts/api-20260622-113527-multiturn-traced-langfuse-traces.json`
- Langfuse observations: `docs/evaluation/artifacts/api-20260622-113527-multiturn-traced-langfuse-observations.json`

> 备注：`api-20260622-113309-multiturn-traced` 是无效 run，PowerShell 管道把中文问题写成了 `????`，只可用于确认 Langfuse 写入链路，不用于质量评估。

## Trace 概览

- 共执行 5 个会话、16 轮请求，全部 HTTP 200。
- Langfuse 写入正常：16 条 root trace、481 条 observation。
- Token 已能追踪到 ReactAgent 的 `qwen-plus` 用量：
  - `qwen-plus`: input 35003，output 1733，total 36736
  - `qwen3-vl-8b-instruct`: input 47297，output 3106，total 50403
  - `text-embedding-v2`: input 531，total 531
- 进入复杂 ReAct/RAG 的轮次为 7 轮；其余多为 `SIMPLE_SHOPPING_TOOL`，由 simple task 小模型直接调 mall 工具。

## 主要问题

| 问题 | Trace 证据 | 影响 |
|---|---|---|
| M01 首轮桌面组合召回偏题 | `rag.mall_enrich.sku_ids=1003,4006,4076`，最终推荐耳机/充电宝/错题本/收纳架，没有召回 `3002/3004/4009` 组合 | 组合导购命中率低，后续只能局部修正 |
| M02 猫砂在融合候选中出现但被截断 | `rag.rrf.rank.top_child_ids` 包含 `mall-sku-4062`，但 `rag.result.parent_count=2`，`rag.mall_enrich.sku_ids=3023,3022` | 模型误答“当前商品池无猫砂” |
| M03 首轮把待比较项误读成硬偏好 | router 输出 `preference_delta={"category":"机械键盘","size":"87键","usage_scenario":"无线矮轴"}`，ReAct 输出“根据您的明确偏好（87键、无线、矮轴）” | 对比任务变成单品筛选 |
| M03 第二轮丢失“机械键盘”上下文 | RAG query 仅为“我是办公室用，预算150以内，别太吵”，`rag.mall_enrich.sku_ids=4076,4038,4001` | 未召回 `3002_机械键盘 红轴 87键` |
| M03 第三轮错误使用精确价格过滤 | `tool.mall_search_products` input 为 `{"keyword":"机械键盘","minPrice":150,"maxPrice":150}` | 129 元红轴键盘被过滤掉 |
| M04 已选 SKU 未继承，且模型编造 SKU | Turn 2 调用 `mall_get_product_detail` input 为 `{"skuId":123456}` | 明明上轮已选 `2001`，却查不存在商品 |
| M04 加购路由和工具选择错误 | Turn 3 router intent 为 `PRODUCT_SELECTION`，工具为 `mall_search_products`，input 为 `{"keyword":"跑步鞋","minPrice":400,"maxPrice":400}` | 没有调用 `mall_add_to_cart` |
| M05 加购/购物车/订单授权失败 | `mall_add_to_cart` / `mall_view_cart` / `mall_prepare_order` 返回 `AUTH_REQUIRED: valid mall context is required` | 受保护商城动作不可用 |
| Simple task 工具观测 user/session 不准确 | `SimpleTaskAgent.simpleMallToolCallbacks()` 给 `LoggingToolCallback` 固定传入 `"simple-task","simple-task"` | Langfuse 上看不到真实 userId/sessionId，排查授权链路更困难 |
| Simple task 允许模型生成工具入参 sessionId | M05 `mall_add_to_cart` input 为 `{"sessionId":"session_1234567890","skuId":987654321,"quantity":2}` | 模型同时编造 sessionId 和 SKU，真实上下文没有被强约束进工具参数 |

## 根因判断

1. **RAG 多意图/多品类召回没有保底机制。**
   M02 的猫砂 `4062` 已进入融合候选，但被动态截断和 parent 限制丢掉；M01 的桌面组合没有按“键盘、鼠标、挂灯”拆分检索。

2. **路由偏好抽取把比较项当成用户偏好。**
   M03 第一轮应该识别为对比任务，而不是把“青轴、红轴 87 键、无线矮轴”合并成硬约束。

3. **后续轮次没有稳定继承已选 SKU。**
   M03/M04/M05 后续都依赖模型从历史自然语言里猜商品，导致查错 SKU、重搜或编造工具参数。

4. **Simple task 的 mall 工具入参缺少代码侧兜底。**
   虽然 `mallToolContext()` 传入了真实 `sessionId` 和 mall 账号信息，但工具 schema 仍要求模型填写 `sessionId`，模型生成了假值；受保护工具最终无法解析有效商城上下文。

5. **观测包装固定写入 `simple-task`，掩盖真实上下文。**
   `LoggingToolCallback` 的观测 user/session 和实际 ToolContext 分离，导致 Langfuse 上无法直接看到真实请求上下文。

## 建议修复顺序

1. **先修 Simple task 工具上下文。**
   - `LoggingToolCallback` 支持从 `ToolContext` 读取 `userId/sessionId` 后写入 span。
   - 对 mall 工具做一层参数归一化：`sessionId` 优先使用 ToolContext 中的真实值，模型传入的 `sessionId` 只作为兜底。
   - 对 `mall_add_to_cart`、`mall_get_product_detail`、`mall_prepare_order` 禁止模型编造 SKU；没有已选 SKU 时回退主 ReAct 或要求确认。

2. **增加会话级“已选商品状态”。**
   - 记录 `selectedSkuIds`、`candidateSkuIds`、`lastRecommendedBundle`。
   - 后续“这双 / 这款 / 库存确认 / 加购 / 确认下单”优先从状态取 SKU。

3. **拆分组合检索。**
   - M01 类请求拆成 `机械键盘`、`静音鼠标`、`显示器挂灯` 多次检索。
   - M02 类请求拆成 `猫粮`、`猫砂`，每个品类至少保留 1 个 parent，再做预算组合。

4. **修正路由/偏好抽取规则。**
   - 对“X、Y、Z 怎么选/对比”只记录 `comparisonItems`，不要写入硬偏好。
   - 后续轮次检索 query 应合并当前输入和会话中稳定品类，例如“机械键盘 + 办公室 + 150以内 + 别太吵”。

5. **修正价格过滤策略。**
   - “预算 150 以内”应转为 `maxPrice=150`，不要同时设置 `minPrice=150`。
   - “预算 400 左右”应给范围或只设上限，不要变成 `minPrice=maxPrice=400`。

