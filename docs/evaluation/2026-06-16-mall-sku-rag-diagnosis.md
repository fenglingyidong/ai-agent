# 2026-06-16 Mall SKU RAG 失败样本诊断

## 结论摘要

本次抽样诊断覆盖 Q08、Q09、Q13、Q20、Q25、Q29。六题共同现象是：路由都进入 `COMPLEX_REACT`，ReAct 输入中也注册了 `searchProductKnowledge`，但 Langfuse ClickHouse 中没有 `tool.*` 或 `rag.*` 观测记录，最终回答表现为追问、通用品牌建议或知识库外泛化。

核心根因不是目标商品不存在，也不是简单链路 RAG 整体不可用，而是复杂链路把“推荐题先查 RAG”交给模型自主遵守。当前 prompt 中的“先调用 searchProductKnowledge”只是软约束，模型可以跳过工具直接生成答案。

## 证据边界

- 评测结果来源：`docs/evaluation/artifacts/api-20260616-20260616-091344-mall-sku-rag-eval-scored.json`
- trace 来源：Langfuse ClickHouse，session 为 `api-20260616-20260616-091344-Q08/Q09/Q13/Q20/Q25/Q29`
- 观测限制：这批 trace 的 `llm.intent_router.*`、`llm.react.*` 主要记录在 root trace metadata；查询 `observations` 中的 `tool.%`、`rag.%` 对六个 trace 均返回 0 行。
- 商品存在性：MySQL `rag_parent_documents` 中能查到目标 SKU 父文档，包含价格和库存。

## 链路基线

- `ShoppingRouteExecutor` 会把高置信简单事实题交给 `SimpleTaskAgent`，简单链路通过受限工具直接查知识库。
- 这六题均被路由为 `COMPLEX_REACT`，进入 `ReActAgent`。
- `ReActAgent` 收到的工具列表包含 `searchProductKnowledge` 和 `updateShoppingPreference`。
- `mall_tools_allowed=false` 只影响商城实时 MCP 工具，不影响内置 RAG 工具。
- 复杂链路是否调用 RAG 由模型自主决定；当前没有程序级强制检索或漏调用拦截。

## 商品文档检查

| 题号 | 期望 SKU | RAG 父文档检查 |
|---|---|---|
| Q08 | `3004` 无线鼠标 静音版 | 存在，价格 89.0，库存 500 |
| Q09 | `3015` 智能台灯 护眼版 黑色 | 存在，价格 219.0，库存 90 |
| Q13 | `2001` 轻量跑步鞋 42 码 | 存在，价格 399.0，库存 300 |
| Q20 | `3001`、`3002`、`3003` 机械键盘 | 均存在，价格分别为 299.0、129.0、259.0 |
| Q25 | `3002`、`3004`、`4009`、`4073` 桌面组合 | 均存在 |
| Q29 | `4021`、`4030`、`3027` 厨房组合 | 均存在 |

## 逐题诊断

| 题号 | 现象 | 链路判断 | 证据 | 根因标签 | 优化方向 |
|---|---|---|---|---|---|
| Q08 | 未命中 `3004`，转为罗技、微软、雷柏等通用品牌建议 | 进入 ReAct，工具可用，但未查 RAG | trace `cbdc20617de98794f4ab1b4dca32092c`；`tools: [searchProductKnowledge, updateShoppingPreference]`；`tool.%/rag.%` 观测 0 行；目标 SKU 文档存在 | `NO_TOOL_CALL`、`PROMPT_GAP` | 推荐题进入 ReAct 前先执行一次商品知识库检索，结果作为候选商品上下文注入 |
| Q09 | 只追问预算、品牌、外观、桌面情况，未推荐 `3015` | 进入 ReAct 后被“补槽追问”覆盖，没有先给已有商品推荐 | trace `628302359b5084021d1c04c88eede8fa`；工具可用；`tool.%/rag.%` 观测 0 行；`3015` 文档存在 | `NO_TOOL_CALL`、`PROMPT_GAP` | 明确“预算/品牌/外观是可选槽位，不阻塞基于 RAG 的首推”；更稳妥是代码侧先检索 |
| Q13 | 未推荐 `2001`，并引入“之前侧重外观/时尚感” | 进入 ReAct，未查 RAG；回答受到历史偏好或记忆上下文干扰 | trace `fe4f2f2ce2071def997780f57bec6cb6`；ReAct 输入用户原话只有跑步、300-500、42 码、颜色无所谓；最终输出出现“之前提到外观/时尚感”；`tool.%/rag.%` 观测 0 行；`2001` 文档存在 | `NO_TOOL_CALL`、`MEMORY_POLLUTION`、`PROMPT_GAP` | 评测用户隔离或禁用长期偏好；本轮明确约束优先级高于历史偏好；推荐题先检索再生成 |
| Q20 | 未覆盖三款 SKU 和价格，误判“规格冲突” | 进入 ReAct 后直接用通用键盘知识推理，没有读取知识库中的三款商品 | trace `77dcc6de1c0ae10b7f0735c75332422e`；工具可用；`tool.%/rag.%` 观测 0 行；三款键盘文档均存在 | `NO_TOOL_CALL`、`PROMPT_GAP` | 对“怎么选/对比”类问题强制先按商品名和属性检索，再基于召回商品做对比 |
| Q25 | 只追问用途、设备、风格，未给 400 元桌面组合 | 组合导购进入 ReAct，但没有拆分品类和检索候选 SKU | trace `eeb29a3d8bfb44ad24ca3369b6d277f7`；工具可用；`tool.%/rag.%` 观测 0 行；桌面组合候选 SKU 文档存在 | `NO_TOOL_CALL`、`PROMPT_GAP` | 组合题先做品类拆解，再多次检索并做预算内组合；避免把可选偏好作为阻塞问题 |
| Q29 | 只追问厨房痛点，未给 550 元内厨房组合 | 组合导购进入 ReAct，但没有检索厨房电器和收纳候选 | trace `d9701734242a82741d669025b512b8da`；工具可用；`tool.%/rag.%` 观测 0 行；厨房组合候选 SKU 文档存在 | `NO_TOOL_CALL`、`PROMPT_GAP` | 对预算明确的组合题先返回一套可行清单，再把追问作为可选优化项 |

## 共性问题

1. 复杂链路没有形成“推荐题必须先查 RAG”的硬约束。Q08、Q09、Q20 都是单商品或对比推荐，目标商品存在，但 ReAct 直接生成。
2. 追问策略过强。Q09、Q25、Q29 的预算、品牌、风格、痛点都是可选优化信息，不应该阻塞已有商品推荐。
3. 组合题缺少检索拆解和候选合并。Q25、Q29 需要先按场景拆成多个品类，再在预算内组合 SKU。
4. 记忆上下文仍可能污染本轮明确需求。Q13 明确说颜色无所谓，却被“外观/时尚感”影响。
5. 观测仍需补强。当前 root metadata 能看到 ReAct 输入输出，但 `llm.react` 不一定以独立 observation 出现；工具和 RAG 未出现可以判断未调用，但后续最好增加 `tool_call_required`、`tool_call_seen`、`rag_query_count` 等结构化字段。

## 优化建议

### 短期提分

1. 在 `COMPLEX_REACT` 推荐、对比、组合任务进入 ReAct 前，代码侧先调用一次 `searchProductKnowledge`，把结果作为“商品知识库候选”注入 ReAct 上下文。
2. 如果任务是组合导购，至少用用户原话先检索一次；后续再做更细的品类拆解。
3. 对评测会话使用独立用户或禁用长期偏好，先排除 Q13 这类历史偏好污染。

### 根因治理

1. 增加工具调用守卫：当任务类型为推荐、对比、组合，且最终回答前没有执行 `searchProductKnowledge`，自动补检索并重试生成。
2. 增加组合检索器：从用户预算和场景抽取 2-4 个品类 query，多路召回后按预算、库存和场景排序。
3. 调整追问策略：关键槽位缺失才追问；已知品类、场景、预算或核心诉求时，先给候选商品，再提示可补充条件优化。
4. 记忆使用增加冲突检测：本轮明确字段优先，历史偏好只能作为弱排序信号，不能覆盖本轮“颜色无所谓”等显式条件。

### 观测补强

1. 记录 `route.task_type`、`react.tools_available`、`react.tool_call_required`、`react.tool_call_seen`。
2. 对 `searchProductKnowledge` 记录 query、召回 parent source_id、top score、child count。
3. 将 `llm.react.input/output` 落为独立 observation，root trace metadata 只保留摘要和索引字段。

## 下一步建议

优先实现“复杂推荐题预检索”或“工具调用守卫”。这两类改动可以直接覆盖本次六个失败样本中的主要根因，比继续细化 prompt 更可控。
