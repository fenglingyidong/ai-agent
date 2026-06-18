# 2026-06-16 Mall SKU RAG Guide 新一轮评测结果

## 概览

- Run ID：api-20260616-20260616-205918
- 原始结果：docs/evaluation/artifacts/api-20260616-20260616-205918-mall-sku-rag-eval-results.json
- 带分数结果：docs/evaluation/artifacts/api-20260616-20260616-205918-mall-sku-rag-eval-scored.json
- Langfuse 摘要：docs/evaluation/artifacts/api-20260616-20260616-205918-langfuse-trace-summary.jsonl
- 总分：133/300

| 题型 | 得分 | 满分 | 题数 |
|---|---:|---:|---:|
| 简单事实题 | 45 | 60 | 6 |
| 单场景推荐题 | 10 | 60 | 6 |
| 多条件推荐题 | 27 | 60 | 6 |
| 商品对比题 | 33 | 60 | 6 |
| 复杂导购题 | 18 | 60 | 6 |

## 关键发现

1. 简单事实题明显改善，Q02/Q03 通过 RAG 命中 SKU 后的 rag.mall_enrich 拿到了商城实时详情；这说明 searchProductKnowledge 的 SKU 补强方向有效。
2. Q05 仍失败，但根因不是 MCP 不可用：Langfuse 中 mall_search_products 返回 ok=true,data=[]，最终话术误报为“商城 MCP 调用失败”。
3. 复杂链路的主要问题不是“完全没有 RAG”：Q07/Q08/Q09/Q10/Q14/Q15/Q20/Q21/Q25/Q29/Q30 都有 RAG 预检索和 rag.mall_enrich，但 ReAct 最终忽略候选，转追问或通用知识。
4. 仍有一批复杂题没有触发 RAG 预检索：Q11/Q12/Q17/Q22/Q23/Q24/Q26/Q27。守卫条件对“不要太贵、方便便宜、怎么选、适合送礼、预算冲突”等表达覆盖不足。
5. 即使命中商品，事实边界仍不稳：Q19/Q20/Q21 等对比题会混入续航、技术、适用年龄、功能体验等知识库外推断。

## 明细

| 题号 | 题型 | 分数 | 路由 | RAG | mall_enrich | mall工具 | 诊断 |
|---|---|---:|---|---:|---:|---:|---|
| Q01 | 简单事实题 | 9 | SIMPLE_SHOPPING_TOOL | 0 | 0 | 1 | 命中跑鞋 42 码、399 元和库存 300；未明确可售数。 SIMPLE_SHOPPING_TOOL 正确调用 mall_search_products，答案基本可靠。 |
| Q02 | 简单事实题 | 9 | FAQ_SIMPLE_QUERY | 7 | 1 | 0 | 命中 87 键红轴机械键盘和 129 元；未补充品牌/SKU。 虽然路由为 FAQ_SIMPLE_QUERY，但 RAG 命中 SKU 后 rag.mall_enrich 补到了商城详情。 |
| Q03 | 简单事实题 | 9 | FAQ_SIMPLE_QUERY | 7 | 1 | 0 | 命中 Aster 黑色保温杯、750ml 和 109 元。 路由为 FAQ_SIMPLE_QUERY，RAG 加 rag.mall_enrich 弥补了商城关键词搜索弱的问题。 |
| Q04 | 简单事实题 | 8 | SIMPLE_SHOPPING_TOOL | 0 | 0 | 2 | 命中 10kg 猫粮库存 200；未明确可售数和价格。 SIMPLE_SHOPPING_TOOL 调用了 mall_search_products 和 mall_get_product_detail，链路正确。 |
| Q05 | 简单事实题 | 1 | SIMPLE_SHOPPING_TOOL | 0 | 0 | 1 | 未回答 24 支、39 元、库存 420；把空结果说成 MCP 调用失败。 mall_search_products 返回 ok=true,data=[]；关键词召回失败和空结果话术错误。 |
| Q06 | 简单事实题 | 9 | SIMPLE_SHOPPING_TOOL | 0 | 0 | 1 | 命中 80 抽 12 包、99 元和库存 300。 SIMPLE_SHOPPING_TOOL 调用 mall_search_products，链路可用。 |
| Q07 | 单场景推荐题 | 2 | COMPLEX_REACT | 7 | 1 | 0 | 只追问预算/佩戴/品牌，未推荐知识库耳机。 有 RAG 预检索和 mall_enrich，但 ReAct 忽略候选，追问策略压过推荐。 |
| Q08 | 单场景推荐题 | 2 | COMPLEX_REACT | 7 | 1 | 0 | 转通用罗技/微软/雷柏建议，未命中 3004 静音鼠标。 有 RAG 预检索但模型未使用候选；存在知识库外品牌推荐。 |
| Q09 | 单场景推荐题 | 1 | COMPLEX_REACT | 7 | 1 | 0 | 只追问预算/品牌/外观，未推荐 3015 护眼台灯。 有 RAG 预检索但追问策略仍阻塞首推。 |
| Q10 | 单场景推荐题 | 2 | COMPLEX_REACT | 7 | 1 | 0 | 推荐通用月亮椅和参数，未命中 4013 轻量露营椅。 有 RAG 预检索但模型输出通用户外知识。 |
| Q11 | 单场景推荐题 | 2 | COMPLEX_REACT | 0 | 0 | 0 | 转通用品牌和选购知识，未命中 4015 入门羽毛球拍。 COMPLEX_REACT 未触发 RAG 预检索，守卫规则漏召回。 |
| Q12 | 单场景推荐题 | 1 | COMPLEX_REACT | 0 | 0 | 0 | 只追问口味/宿舍条件/预算，未推荐 4031 燕麦片。 COMPLEX_REACT 未触发 RAG 预检索，追问策略过强。 |
| Q13 | 多条件推荐题 | 8 | COMPLEX_REACT | 14 | 2 | 0 | 命中 42 码跑鞋、399 元、库存 300；混入评价/外观等可能外推信息。 RAG 和 mall_enrich 均触发，候选使用较好。 |
| Q14 | 多条件推荐题 | 3 | COMPLEX_REACT | 7 | 1 | 0 | 识别 20000mAh 方向，但未命中 4006/4005，转通用品牌和参数。 有 RAG 预检索但模型忽略候选，泛化严重。 |
| Q15 | 多条件推荐题 | 1 | COMPLEX_REACT | 7 | 1 | 0 | 只追问猫龄和猫砂类型，未给 3023+4062 组合。 有 RAG 预检索但组合题仍被补槽追问覆盖。 |
| Q16 | 多条件推荐题 | 5 | COMPLEX_REACT | 14 | 2 | 0 | 命中黑色笔和阅读书架，但误判没有错题本，未给 87 元三件组合。 RAG 预检索可能未覆盖所有品类；组合拆解不足。 |
| Q17 | 多条件推荐题 | 2 | COMPLEX_REACT | 0 | 0 | 0 | 转知识库外个护品牌，未命中 4049+4041。 COMPLEX_REACT 未触发 RAG 预检索，且生成知识库外商品。 |
| Q18 | 多条件推荐题 | 8 | COMPLEX_REACT | 7 | 1 | 0 | 命中 8 件套真空收纳袋 99 元；未给 4027 组合，存在少量泛化。 RAG 预检索和候选使用有效。 |
| Q19 | 商品对比题 | 7 | COMPLEX_REACT | 7 | 1 | 0 | 覆盖三款耳机 SKU 和价格，但编造/外推续航、技术、库存等细节。 RAG 预检索有效，但事实边界仍不够硬。 |
| Q20 | 商品对比题 | 4 | COMPLEX_REACT | 7 | 1 | 0 | 提到青轴/红轴价格，但误判规格冲突，漏无线矮轴 259 元和三款 SKU 对比。 有 RAG 预检索但模型用通用轴体知识覆盖商品事实。 |
| Q21 | 商品对比题 | 5 | COMPLEX_REACT | 7 | 1 | 0 | 覆盖 4L/6L 选择逻辑，但缺 299/399 价格并外推功能。 有 RAG 预检索但价格事实未进入答案。 |
| Q22 | 商品对比题 | 8 | COMPLEX_REACT | 0 | 0 | 0 | 价格 129/189、差价 60 和适用人群正确；来源/SKU 未明确。 未触发 RAG，但模型恰好答中；链路上仍是证据不足。 |
| Q23 | 商品对比题 | 5 | COMPLEX_REACT | 0 | 0 | 0 | 推荐 128 片送礼合理，但缺 129/229 价格且年龄判断外推。 未触发 RAG，主要靠常识生成。 |
| Q24 | 商品对比题 | 4 | COMPLEX_REACT | 0 | 0 | 0 | 给出波浪/立式选择逻辑，但缺 39/99 价格且大量外推属性。 未触发 RAG，知识库依据不足。 |
| Q25 | 复杂导购题 | 1 | COMPLEX_REACT | 7 | 1 | 0 | 只追问用途/设备/风格，未给 400 元桌面组合。 有 RAG 预检索但追问策略阻塞组合方案。 |
| Q26 | 复杂导购题 | 2 | COMPLEX_REACT | 0 | 0 | 0 | 识别预算冲突，但未命中 4007/4008，输出通用投影参数和外部品牌。 未触发 RAG，复杂导购泛化。 |
| Q27 | 复杂导购题 | 3 | COMPLEX_REACT | 0 | 0 | 0 | 给出宠物礼物方向，但未命中猫抓板/除毛刷/猫砂等 SKU 和价格。 未触发 RAG，商品建议知识库外化。 |
| Q28 | 复杂导购题 | 8 | COMPLEX_REACT | 14 | 2 | 0 | 正确说明没有 41 码，并列出 42/43/44 相近款价格；仍建议尝试 42 需谨慎。 RAG 预检索有效，能处理无完全匹配。 |
| Q29 | 复杂导购题 | 1 | COMPLEX_REACT | 7 | 1 | 0 | 只追问厨房痛点，未给 547 元厨房组合。 有 RAG 预检索但组合题仍被追问覆盖。 |
| Q30 | 复杂导购题 | 3 | COMPLEX_REACT | 7 | 1 | 0 | 给出户外预算方案，但全部是知识库外装备，未命中登山杖/露营椅/护膝组合。 有 RAG 预检索但模型忽略候选，泛化为露营大件清单。 |

## 可疑模块

- ShoppingRouteExecutor.shouldPreRetrieveKnowledge：推荐/对比/复杂导购的触发词覆盖不足，导致 Q11/Q12/Q17/Q22/Q23/Q24/Q26/Q27 没有预检索。
- react.system.st / ReAct 决策：已有预检索候选时仍会先追问或输出通用品牌，候选使用优先级不够硬。
- 简单商城任务搜索策略：Q05 只用“彩色中性笔套装”搜索商城返回空，应先 RAG 定位 SKU 或增加关键词降级。
- 简单商城任务空结果话术：ok=true,data=[] 不应回答“商城 MCP 调用失败”，应说明未搜到并尝试 RAG/SKU 补查。
- 事实边界控制：对比题和推荐题仍把常识/外部品牌当成商品建议，需要在生成前后加强候选约束。