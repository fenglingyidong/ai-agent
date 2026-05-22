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

## Spring AI 框架 API 精简替换计划

目标：把项目中已经由 Spring AI 1.1.4 提供的通用框架能力交回框架，保留电商导购特有的业务边界，减少自研胶水代码和维护面。

### 高优先级替换

1. 结构化路由输出
   - 当前位置：`ShoppingIntentRouter`
   - 当前做法：`call().content()` 后手写 `extractJsonObject()`，再用 `ObjectMapper.readValue(...)` 解析。
   - 替换方案：使用 `ChatClient.call().entity(ShoppingIntentRoute.class)` 或 `BeanOutputConverter<ShoppingIntentRoute>`。
   - 收益：删除手写 JSON 截取和解析逻辑，路由输出失败时直接进入现有 fallback。

2. 简单任务/内置工具注册
   - 当前位置：`SimpleTaskAgent`、`ReActAgent`
   - 当前做法：为内部工具类手写 `MethodToolCallbackProvider`，再传 `toolCallbacks(...)` 或读取工具定义。
   - 替换方案：`SimpleTaskAgent` 直接使用 `ChatClient.tools(...)`；`ReActAgent` 使用 `ToolCallbacks.from(...)` 从 `@Tool` 方法生成工具定义。
   - 收益：减少工具注册样板代码，工具类仍保留限定工具集合。

3. 工具执行上下文
   - 当前位置：`LoggingToolCallback`、`ToolExecutionContext`、`BuiltInTools`
   - 当前做法：用 ThreadLocal 向工具传 `userId/sessionId/mallToken`。
   - 替换方案：使用 Spring AI `toolContext(...)`，工具方法通过 `ToolContext` 参数读取上下文。
   - 收益：删除自研 ThreadLocal 依赖，避免流式/异步环境下上下文泄漏风险。

4. 商城 MCP 调用
   - 当前位置：`MallMcpClient`、`MallMcpOperations`、`MallMcpToolCallback`
   - 当前做法：自研 MCP initialize、JSON-RPC `tools/call`、结果提取和失败包装。
   - 替换方案：优先使用 Spring AI MCP client / `McpSyncClient` / MCP ToolCallback；保留上下文注册、`sessionId` 注入和“商城 MCP 调用失败”统一失败语义。
   - 收益：减少协议层代码，把 MCP 会话、协议版本和工具调用交给 Spring AI。

### 暂不替换

- `ParentChildHybridDocumentRetriever`：RRF + dense/BM25 融合是业务检索策略，Spring AI 没有等价直接替换。
- `RedisBm25ChildChunkRetriever`：直接使用 RediSearch BM25，超出 VectorStore 通用 API。
- `ParentChildDocumentIndexer` 的 child overlap 滑窗：当前 Spring AI `TokenTextSplitter` 不直接覆盖该滑窗参数组合。
- `PromptSecurityFilter`：Spring AI `SafeGuardAdvisor` 只能替代部分注入拦截，不能替代敏感值掩码和流式恢复。

### 实施顺序

1. 先替换结构化路由输出。
2. 再替换简单任务工具注册。
3. 再用 `ToolContext` 替换 ThreadLocal 上下文。
4. 最后替换 MCP 协议层，确保上下文注册、`sessionId` 注入和失败文案不变。
5. 每步完成后运行相关单元测试，最后更新 README 的当前实现状态。

### 本轮实施状态

- 已完成：`ShoppingIntentRouter` 路由结果改为 `ChatClient.call().entity(ShoppingIntentRoute.class)`，删除手写 JSON 截取/解析。
- 已完成：`SimpleTaskAgent` 简单任务工具注册改为 `ChatClient.tools(...)`，保留 A/B 类限定工具集合。
- 已完成：`ReActAgent` 内置工具定义生成改为 `ToolCallbacks.from(builtInTools)`，删除 `MethodToolCallbackProvider` 样板代码。
- 已完成：主 Agent 通过 `toolContext(...)` 传递 `userId/sessionId/mallToken/mallUsername/mallPassword`；`BuiltInTools.updateShoppingPreference` 直接读取 Spring AI `ToolContext`；已删除自研 `ToolExecutionContext`。
- 已完成：`MallMcpClient` 改为 `McpSyncClient + WebClientStreamableHttpTransport`，不再手写 MCP initialize、JSON-RPC 请求和 SSE/text 解析。
- 已完成：`MallMcpToolCallback` 改为基于 Spring AI `FunctionToolCallback` 构造 `mall_*` 工具，仍保留上下文注册、`sessionId` 注入和统一失败包装。
- 已验证：`ReActAgentTest,SimpleTaskAgentTest,ShoppingIntentRouterTest,BuiltInToolsTest` 针对性测试通过；完整 `mvn -q test` 通过。

### 本轮复查追加清理

- 已删除：`ShoppingIntentRouter` 构造器中上一轮结构化输出替换后遗留的 `ObjectMapper` 参数。
- 已删除：`MallTokenFilter` / `MallTokenContext`，商城 token 只通过 `ChatController -> ReActAgent.toolContext(...) -> MallMcpToolCallback` 显式传递。
- 已精简：`BuiltInTools.updateShoppingPreference` 只读取 Spring AI `ToolContext`，不再从 `SecurityContextHolder` / `RequestContextHolder` 推断用户和会话。
- 已删除：自研 `ToolDefinitionEntry` 和主 Agent prompt 里的工具 schema 手写渲染；工具名、描述和参数 schema 以 Spring AI `ToolCallback` 注册给模型的元数据为准。
- 已验证：针对性测试 `ReActAgentTest,ShoppingIntentRouterTest,BuiltInToolsTest,SimpleTaskAgentTest,ChatControllerTest` 通过；完整 `mvn -q test` 通过。
