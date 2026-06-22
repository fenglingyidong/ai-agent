# 2026-06-22 Mall SKU RAG 多轮对话评测结果

## 结果索引

- runId: `api-20260622-110954-multiturn`
- 评测集: `docs/evaluation/2026-06-22-mall-sku-rag-multiturn-eval.md`
- 原始结果: `docs/evaluation/artifacts/api-20260622-110954-multiturn-mall-sku-rag-multiturn-results.json`
- 运行日志: `docs/evaluation/artifacts/api-20260622-110954-multiturn-multiturn-run.stdout.log`
- Trace 摘要: `docs/evaluation/artifacts/api-20260622-110954-multiturn-langfuse-trace-summary.jsonl`

## 执行说明

- 共执行 5 个独立会话、16 轮请求。
- 所有 `/api/react` 请求 HTTP 状态均为 200。
- Langfuse ClickHouse 未运行，trace 查询失败，`langfuse-trace-summary.jsonl` 为空。
- 本次 runner 复用了 `scripts.mall_sku_rag_eval_lib.post_react` 的 multipart 请求和 Basic Auth 逻辑；未直接使用 `run_mall_sku_rag_eval.py run --ids`，因为该 CLI 只支持单轮独立题。

## 总分

| 会话 | 得分 | 主要问题 | 通过项 |
|---|---:|---|---|
| M01 | 6 / 20 | 首轮大模型连接中断；后续只给泛化库存，未恢复桌面三件套组合。 | 能识别鼠标静音需求，并指出当前没有明确静音键盘。 |
| M02 | 7 / 20 | 前两轮漏掉猫砂并声称商品池缺失；第三轮才提到猫砂，前后不一致。 | 能命中猫粮 10kg；第三轮能说明混合砂除味更好。 |
| M03 | 10 / 20 | 首轮把待比较项误读为硬偏好；第三轮丢失已选红轴 87 键。 | 第二轮能按办公室、150 元以内、低噪音推荐红轴 87 键。 |
| M04 | 8 / 20 | 用户接受 42 码后错误查询 SKU 123456；加购失败。 | 首轮能正确说明没有 41 码，并给出相邻尺码。 |
| M05 | 9 / 20 | 加购、购物车和下单均因商城上下文无效失败。 | 商品查价正确；未在用户确认前越权创建订单。 |

总分：`40 / 100`

## 分项观察

| 维度 | 表现 |
|---|---|
| 上下文继承 | 弱。M03 第三轮、M04 第二轮都没有继承上一轮已确定商品。 |
| 用户修正处理 | 中等偏弱。M03 第二轮能处理新约束，但 M01/M02 修正后没有稳定收敛。 |
| 工具正确性 | 弱。商品详情能查，但多轮中受保护商城工具缺少有效授权上下文。 |
| 商品事实准确性 | 中等。单轮事实多处正确，但 M04 出现凭空 SKU 123456。 |
| 安全边界 | 较好。M05 没有在未确认或工具失败时伪造下单成功。 |

## 关键问题

1. **多轮商品选择状态不稳**：M03 第二轮已经选中 `3002_机械键盘 红轴 87键`，第三轮却没有继承，直接说搜不到商品。
2. **组合召回仍然偏窄**：M02 前两轮仍漏掉 `4062_猫砂 混合砂 12L`，和单轮 Q15 的问题一致。
3. **受保护 mall 工具授权链路有问题**：M04/M05 的加购、购物车、下单都返回“缺少有效商城上下文”。需要单独核查 `mallUsername/mallPassword` 是否成功进入 MCP meta，以及 mall-mcp 是否能用 `alice/demo123` 登录。
4. **失败恢复不足**：M01 首轮大模型连接中断后，后续轮次没有恢复原始组合任务，只围绕用户后续片段回答。

## 建议

优先修两类问题：

1. 多轮状态：把“本轮已选商品 / 候选组合 / 用户已接受的 SKU”写入可控会话状态，后续库存、加购、下单优先从状态取 SKU，不让模型重新猜。
2. Mall 授权：补一个最小链路测试，直接验证 `/api/react` -> `ToolContextToMcpMetaConverter` -> mall-mcp `resolveAuthorization` 是否能用 `alice/demo123` 成功登录并执行 `mall_add_to_cart`。
