意图识别 / Planner Agent
现在主要靠系统提示词约束模型，不是真正的小模型意图识别或结构化规划。建议补一个 ShoppingIntentClassifier 或 ShoppingPlanner：输出 intent、slots、missingSlots、toolCandidates、riskLevel、needConfirm，再由主 Agent 决定工具集合。

skills 组合机制
简历写了 skills 组合，但代码里没有独立 skill 模块或 prompt 模板注册机制。目前只是一个统一系统提示词。建议把“选品、对比、追问、推荐、加购确认”拆成可配置 prompt/skill，并在 Planner 阶段选择。

三层递进式记忆
当前实际是“两层”：Redis 短期窗口 + 长期摘要向量召回。ShoppingStateService 虽然存了偏好，但没有自动注入 Agent 上下文。建议改成：
L0 最近对话窗口、L1 结构化偏好槽位 Redis、L2 长期摘要向量记忆，并在每轮对话统一加载。

真实多模态检索
现在图片只是传给多模态模型，缺少 analyzeProductImage、图片向量索引、以图搜同款/相似款。严格说当前是“支持图文输入”，还不是完整“多模态商品召回”。

商品知识库自动同步
当前 /products/import 是手动导入，README 也写了不是自动同步型知识库。建议补商城商品同步任务：从商品服务拉 SPU/SKU 静态字段，构建 RAG 文档，价格库存只实时查商城服务，不写死进向量正文。

千万级商品方案
不建议把所有 SKU、价格、库存放进向量库。更合理是：向量库只放稳定语义信息，商品库/ES/Redis 存动态字段；用户 query 先做类目/品牌/预算解析，再走类目路由 + 结构化过滤 + TopN 召回 + rerank，最后对候选商品做 RAG 解释。

Agent 效果评测
现在只有 DuReader Recall 评测，项目文档也承认不能证明电商导购效果。需要补 ESCI/SQID 或自建电商样例集，指标至少包括 Recall@K、NDCG@10、MRR、工具调用准确率、槽位补全率、加购成功率、幻觉率、平均 To                              ken、延迟。

对话式加购状态机
当前 addToCart 做了库存校验，但“用户明确确认”主要靠提示词约束。建议补显式状态：候选商品 -> SKU 消歧 -> 数量确认 -> 风险确认 -> 执行加购，这样面试讲链路会更硬。

## Spring AI 框架 API 精简替换状态

目标：把 Spring AI 1.1.4 已提供的通用框架能力交回框架，保留电商导购特有的业务边界，减少自研胶水代码和维护面。

### 已完成

- `ShoppingIntentRouter` 路由输出已改为 `ChatClient.call().entity(ShoppingIntentRoute.class)`，路由输出失败时进入现有 fallback。
- `SimpleTaskAgent` 简单任务工具已通过 `ChatClient.tools(...)` 注册，仍保留 A/B 类限定工具集合。
- `ReActAgent` 内置工具定义已通过 `ToolCallbacks.from(builtInTools)` 生成，工具名、描述和参数 schema 以 Spring AI `ToolCallback` 元数据为准。
- 主 Agent 通过 Spring AI `toolContext(...)` 传递 `userId/sessionId/mallToken/mallUsername/mallPassword`，工具方法从 `ToolContext` 读取上下文。
- 商城 MCP 协议调用已收敛到 Spring AI MCP client / `McpSyncClient` / MCP `ToolCallback`，仅保留商城上下文注册、`sessionId` 注入、订单创建硬门禁和“商城 MCP 调用失败”统一失败语义。

### 保留边界

- `ParentChildHybridDocumentRetriever`：RRF + dense/BM25 融合是业务检索策略，Spring AI 没有等价直接替换。
- `MilvusBm25ChildChunkRetriever`：Spring AI 1.1.4 尚未直接暴露 Milvus BM25 Function 查询能力，因此保留薄层 Milvus 原生 client 适配。
- `ParentChildDocumentIndexer` 的 child overlap 滑窗：当前 Spring AI `TokenTextSplitter` 不直接覆盖该滑窗参数组合。
- `PromptSecurityFilter`：Spring AI `SafeGuardAdvisor` 只能替代部分注入拦截，不能替代敏感值掩码和流式恢复。

### 本轮保守收敛状态

- 已修复：`PromptSecurityFilter` 在路由和高置信快车道之前执行，快车道不再绕过提示词注入过滤和敏感值掩码。
- 已加固：`mall_create_order` 增加 Java 侧硬门禁，未通过二次确认门禁或缺少关键参数时不调用真实 MCP 工具。
- 已精简：商城简单任务快车道复用 Spring AI MCP `ToolCallback`，删除手写工具调用层。
- 已收敛：工具调用 info 日志只保留摘要，不输出完整工具入参和返回值。
