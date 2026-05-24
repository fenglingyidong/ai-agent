# 多模态电商智能导购 Agent 项目说明

## 目录

- [0. 当前改造进度](#0-当前改造进度)
- [1. 项目定位](#1-项目定位)
- [2. 技术栈](#2-技术栈)
- [3. 目录结构](#3-目录结构)
- [4. 核心模块拆解](#4-核心模块拆解)
- [5. 关键请求链路](#5-关键请求链路)
- [6. 数据存储设计](#6-数据存储设计)
- [7. 配置说明](#7-配置说明)
- [8. 当前可用接口](#8-当前可用接口)
- [9. 启动方式](#9-启动方式)
- [10. 测试覆盖情况](#10-测试覆盖情况)
- [11. 下一步计划](#11-下一步计划)

## 0. 当前改造进度

本项目正在从通用 RAG/ReAct Agent 改造为“多模态电商智能导购 Agent”。当前已完成的关键链路：

- Agent 系统提示词已收敛到电商导购场景，约束选品、追问、推荐、查库存和加购流程。
- 内置工具已收敛为商品知识库检索和短期偏好更新；实时商品、购物车和普通订单链路统一改为 `mall_*` MCP 工具。
- 新增三段式路由：`ShoppingIntentRouter` 在 `ReActAgent.runStream(...)` 起点用小模型做一次 JSON 路由，A 类 FAQ/简单查询进入 `SimpleTaskAgent` 知识库快车道，B 类单步商城任务进入 `SimpleTaskAgent` 商城 MCP 快车道，C 类复杂推荐/对比/规划再进入主模型 ReAct 链路。
- 新增商城 MCP 适配层 `mall/`，支持通过 `X-Mall-Authorization` 透传商城 Token，或把 Basic 登录信息注册到 `mall-mcp` 上下文接口。
- `POST /api/react` 是唯一 ReActAgent 对话入口，支持 multipart 文本、图片、图片 URL、模型选择、联网开关和会话参数。
- 前端已改为导购工作台，包含聊天区、商品卡片、图片上传、对比视图和购物车抽屉；前端不直接管理商城 Token。
- 前端不再直连商城 REST；商品查询、加购、查购物车和普通订单动作都通过 `/api/react` 进入 Agent，再由 `mall_*` MCP 工具调用 `mall-mcp`。
- `ReActAgent` 已收敛为单一构造函数和单一对外 `runStream(...)` 入口；Controller 显式传入用户、会话、模型、媒体和商城上下文。
- Spring AI 框架 API 已进一步收敛：路由输出用 `ChatClient.entity(ShoppingIntentRoute.class)` 解析，简单任务工具用 `ChatClient.tools(...)` 注册，主 Agent 内置工具定义用 `ToolCallbacks.from(...)` 生成，工具名/描述/schema 由 `ToolCallback` 元数据交给模型，工具上下文用 `toolContext(...)` 透传，商城 MCP 协议调用改为 `McpSyncClient`，主 Agent 的 `mall_*` 工具注册交给 `SyncMcpToolCallbackProvider`。
- 安全过滤已前置到路由和快车道之前：进入 `ShoppingIntentRouter`、`SimpleTaskAgent` 和主 Agent 的文本都会先经过 `PromptSecurityFilter` 过滤提示词注入片段并掩码敏感值。
- `mall_create_order` 增加 Java 侧硬门禁：只有路由明确为 `CREATE_ORDER` 且工具参数包含有效 `confirmationId` 与 `userConfirmed=true` 时才会放行。
- 商城快车道已复用 Spring AI MCP `ToolCallback`，不再维护单独的 `MallMcpOperations` 手写工具调用层；`sessionId` 由薄包装 `MallSessionToolCallback` 注入。
- 工具调用 info 日志只记录工具名、输入长度、输出长度和错误摘要，不记录完整工具入参或工具返回值。
- `ReActAgent` 日志统一使用 SLF4J，已压缩为 `start / memory / finish / error` 结构化短日志，不再在 info 级别记录完整 prompt 或完整输出。
- 测试已同步到导购工具集合、商城登录和统一 Agent 入口；手工测试计划见 [TESTING.md](TESTING.md)。

当前约束与假设：

- 商品、库存价格、购物车和普通订单运行链路只走 `mall-mcp` 暴露的商城真实业务数据；`mall-mcp` 未启动或调用失败时直接返回“商城 MCP 调用失败”。
- 图文多模态链路已打通到前端和后端；购物聊天会先走小模型意图路由，只有复杂、低置信或槽位不足时才把请求交给主 Agent。
- 普通订单只开放二阶段链路：先 `mall_prepare_order`，再由用户明确确认后 `mall_create_order`；不开放秒杀和支付。
- 商城业务 MCP 默认入口是 `http://localhost:8120/mcp`，上下文接口默认是 `http://localhost:8120/internal/mcp/mall/context`；Agent 侧可通过 `app.mall.mcp.*` 调整。`app.mall.base-url` 仅保留给 Basic 登录认证。
- DuReader 评测只能证明通用 RAG 检索能力，电商导购效果需要继续补 ESCI/SQID 评测。

## 1. 项目定位

这是一个基于 Spring Boot 与 Spring AI 的多模态电商智能导购 Agent 原型项目，目标不是只做一个简单聊天接口，而是把用户选品、商品召回、图文咨询、偏好记忆、安全调用和可控工具编排串成一条完整链路：

- ReAct 风格推理与 Spring AI 工具调用
- 商品检索、价格库存展示、购物车操作和普通订单 MCP 工具
- 基于 Milvus 的商品知识库 RAG 检索，商品索引和长期记忆索引独立拆分
- 短期导购偏好状态 + 长期摘要记忆
- Prompt Injection 与敏感信息保护
- 可选的 MCP 联网搜索
- 一个可直接联调的零依赖静态前端

从代码现状来看，这个项目更准确地说是一个“电商导购 Agent 后端核心链路原型”，重点验证 Agent loop、工具边界、商品 RAG、偏好记忆和 Prompt 安全，而不是完整电商交易系统。

## 2. 技术栈

| 类别 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.4.1 |
| Java 版本 | Java 17 |
| AI 框架 | Spring AI 1.1.4 |
| 模型接入 | OpenAI 兼容协议，当前接到阿里云 DashScope |
| 向量存储 | Milvus 2.5+ / Spring AI Milvus VectorStore |
| 全文检索 | Milvus Sparse-BM25 |
| Redis | 短期记忆、父块缓存、会话状态、商城 token 缓存 |
| 安全 | Spring Security Basic Auth + Form Login |
| 前端 | 原生 HTML / CSS / JavaScript |

## 3. 目录结构

```text
RAGAgent
├─ src/main/java/com/example/ragagent
│  ├─ config        配置类
│  ├─ controller    HTTP 接口
│  ├─ commerce      导购偏好状态
│  ├─ memory        分层记忆
│  ├─ rag           RAG 常量与实现
│  ├─ security      Prompt 安全处理
│  ├─ service       Agent、意图路由与模型注册
│  └─ tools         内置工具
├─ src/main/resources
│  └─ application.yml
├─ src/test/java/com/example/ragagent
│  ├─ agent
│  ├─ memory
│  ├─ rag
│  └─ security
├─ frontend         独立静态前端
├─ pom.xml
└─ 项目说明.md
```

## 4. 核心模块拆解

### 4.1 应用入口

入口类是 `RagAgentApplication`，职责很简单，只负责启动 Spring Boot，并启用 `@ConfigurationPropertiesScan` 让配置类自动绑定。

### 4.2 控制层

#### `ChatController`

对外暴露两个接口：

- `POST /api/react`
  唯一 ReActAgent 流式对话入口，使用 `multipart/form-data` 接收文本、图片、图片 URL、模型、联网开关和会话参数。
- `GET /api/models/chat`
  返回当前可选模型列表和默认模型。

`/api/react` 返回流式纯文本。控制器只负责收参、把图片转换为 Spring AI `Media`、解析用户会话和商城登录上下文，然后统一调用 `ReActAgent.runStream(...)`。小模型路由、`mall-mcp` 上下文预检和主模型兜底都在 Agent 层完成。

#### `RagDocumentController`

对外暴露：

- `POST /api/rag/documents/import`
- `POST /api/rag/documents/products/import`

`/import` 接收一篇原始文档的 `sourceId`、`title`、`content`，也支持附带 `productId`、`skuId`、`category`、`brand`、`price`、`stock`、`imageUrl` 等商品元数据，然后调用索引器做父子分块入库，并返回：

- 生成了多少个父块
- 生成了多少个子块
- 对应的 `parentIds`
- 对应的 `childIds`
- 写入的商品元数据

`/products/import` 接收结构化商品详情，会把商品标题、品牌、类目、价格、库存、商品描述、评价摘要、导购话术和规格参数拼成可检索知识文档，再写入父子索引。当前知识库仍是“手动导入”，不是扫描目录或自动同步型知识库。

### 4.3 小模型三段式路由：`ShoppingIntentRouter` / `ShoppingRouteExecutor`

`ShoppingIntentRouter` 负责调用路由小模型，并通过 Spring AI `ChatClient.entity(ShoppingIntentRoute.class)` 直接获得结构化路由结果；`ShoppingRouteExecutor` 负责把路由结果转换为主 Agent 输入、执行高置信简单任务快车道，并在商城相关意图进入主模型前注册 `mall-mcp` 上下文。`ReActAgent.runStream(...)` 会在 Prompt 安全过滤和主模型调用之前先执行这一步；`ReActAgent` 对外只保留这一个完整参数入口，默认值由 Controller 或测试显式传入。

路由模型默认使用：

- `qwen3-vl-8b-instruct`
- OpenAI 兼容协议
- `response_format=json_object`
- 置信度阈值 `0.7`

它对所有 `POST /api/react` 对话请求生效，并把请求分为三类：

- `A_FAQ_SIMPLE_QUERY`：FAQ、商品知识库事实、简单解释类问题，进入 `SimpleTaskAgent` 知识库快车道；快车道只暴露 `searchProductKnowledge` 工具，不进入主 ReAct。
- `B_SIMPLE_SHOPPING_TOOL`：查价格、查库存、查商品详情、查购物车、明确加购和确认订单摘要等单步商城任务，进入 `SimpleTaskAgent` 商城快车道；快车道只暴露 `queryRealtimeProduct`、`addToCart`、`viewCart`、`prepareOrder`，工具内部调用 `mall-mcp`，不进入主 ReAct。
- `C_COMPLEX_REACT`：复杂推荐、商品对比、多步规划、图片不确定和最终创建订单，进入默认 Qwen 主模型 ReAct 链路。

它对请求内容的处理方式：

- 文本-only 请求：只传用户文本给路由模型
- 图文请求：传用户文本 + Spring AI `Media`
- 路由模型必须输出 `task_type`、`intent`、`visual_context`、`text_slots`、`task_policies`、`missing_slots`、`tool_candidates`、`need_confirm`、`risk_level`、`route_to_core`、`confidence`、`reason`

导购流程没有引入重量级 Skill 框架。项目中使用的是轻量 `ShoppingTaskPolicy`：每个策略由 prompt 片段、适用意图、缺失槽位、受控工具集合和确认要求组成。`ShoppingIntentRouter` 作为 Planner 输出候选策略，`ShoppingTaskPolicyRegistry` 做归一化兜底，`ReActAgent` 在系统提示词中组合策略约束，并对商品知识库、偏好更新和 `mall_*` MCP 这类导购受控工具做策略级过滤。

分发规则：

- A 类高置信请求先走 `SimpleTaskAgent` 知识库快车道；RAG 检索为空或异常时降级主模型。
- B 类高置信请求先注册 `mall-mcp` 上下文；注册失败直接返回商城 MCP 调用失败。随后由 `SimpleTaskAgent` 调用简单任务小模型，并通过 Spring AI `ChatClient.tools(...)` 只暴露限定的商城快车道工具；工具内部调用 `mall-mcp`。MCP 不可用直接返回失败，槽位不足、多候选或小模型异常时降级主模型。
- C 类、低置信、解析失败、槽位不足、多商品候选、最终创建订单或快车道降级时，进入 `ReActAgent` 主模型链路。

当高置信图文请求需要主 Agent 处理时，`ShoppingRouteExecutor` 会把 `visual_context` 转成纯文本上下文注入主 Agent，默认不再透传原图；当路由低置信时才把原图完整透传给主 Agent 兜底。

### 4.4 Agent 核心：`ReActAgent`

这是整个项目最核心的类，职责包括：

- 接收用户输入
- 做 Prompt 安全处理
- 装载短期/长期记忆
- 选择模型
- 组装系统提示词
- 装配工具
- 发起流式推理
- 在输出阶段恢复被脱敏的敏感值
- 将本轮对话写回记忆

它的处理流程可以概括成：

1. 用户请求进入唯一对外 `runStream(...)`
2. `ShoppingRouteExecutor` 先执行小模型路由，商城相关意图先预检 `mall-mcp` 上下文
3. 高置信简单意图由 `SimpleTaskAgent` 调用简单任务小模型，并通过限定工具访问 RAG 或 `mall-mcp` 后直接返回
4. 未短路的请求进入 `PromptSecurityFilter.secure(...)`，对输入做注入过滤和敏感信息脱敏
5. 通过 `LongTermMemoryAdvisor` 召回并注入长期摘要记忆
6. 通过 Spring AI `MessageChatMemoryAdvisor` 自动装载短期对话窗口
7. 默认注册 `mall_*` MCP 工具，根据 `webSearchEnabled` 决定是否额外暴露 WebSearch MCP 工具；若路由选择了 `ShoppingTaskPolicy`，则只对商品知识库、偏好更新和 `mall_*` 等导购受控工具做策略级过滤
8. 根据 `modelId` 从 `ChatModelRegistry` 选择模型参数
9. 通过 Spring AI `ChatClient` 发起流式调用
10. 输出时把类似 `[[PHONE_1]]` 这样的占位符恢复成原值
11. 正常响应由 Spring AI 记忆 advisor 自动写回；短路和 fallback 响应由 `ConversationMemoryService` 显式写回

这个类虽然名为 ReAct，但实现方式并不是自己手写 Thought/Action/Observation 循环，而是借助 Spring AI 的工具调用能力，让模型在系统提示词约束下自动调用工具。

### 4.5 内置工具：`BuiltInTools`

当前内置工具只保留不直连商城业务的本地能力：

- `searchProductKnowledge`
  检索商品详情、规格参数、评价摘要和导购话术知识库
- `updateShoppingPreference`
  维护品类、预算、品牌、尺码、颜色、风格和使用场景等短期偏好状态

实时商品搜索、商品详情、加购物车、查购物车、确认订单和创建订单已经从 `BuiltInTools` 删除，统一由 `mall-mcp` 提供的 `mall_*` MCP 工具承担。其中 `searchProductKnowledge` 仍走“父子块 + dense + BM25 混合召回”的商品知识库检索链路。

### 4.6 RAG 模块

RAG 模块是本项目的另一条主线，主要由下面几个类组成。

#### `ParentChildDocumentIndexer`

职责是导入文档并建立父子分块索引。

处理方式：

- 先把整篇文档切成较大的父块
- 再把每个父块切成更小的子块
- 父块全文单独保存到 Redis
- 子块写入 Milvus `product_index`

关键细节：

- 父块切分用 `TokenTextSplitter`
- 子块切分使用 token 滑窗
  当前参数是 `chunkSize=120`、`overlap=12`
- 同一个 `sourceId` 重复导入时，会先删除旧索引再写新索引
- 父块 ID 由 `sourceId + parentIndex + hash` 组成，保证同内容下较稳定

#### `ParentDocumentStore`

职责是把父块持久化到 Redis，并维护 `sourceId -> parentIds` 的映射。

它保存的不是向量，而是完整父块正文。这样查询时可以先命中更小的子块，再回查对应父块，把更完整的上下文交给模型。

#### `ParentChildDocumentRetriever`

职责是密集检索路径：

- 先在向量库里检索子块
- 再从命中的子块里读出 `parentId`
- 再回 Redis 加载父块
- 最终只把父块返回给上层

这本质上就是 Small-to-Big Retrieval。

它还有一个容错逻辑：

- 优先走 `VectorStoreDocumentRetriever`
- 如果过滤检索失败，再回退到普通向量相似度搜索
- 回退后会在应用层自行过滤，只保留 `docType=rag-child` 的子块

#### `MilvusBm25ChildChunkRetriever`

职责是稀疏检索路径：

- 通过 Milvus 2.5+ BM25 Function 生成的 `sparse_vector` 做全文召回
- Spring AI 1.1.4 尚未直接暴露 Milvus BM25 Function，因此这里保留一层很薄的 Milvus 原生 v2 client 适配
- 返回子块文档

这个类只负责补齐 Spring AI 当前版本未覆盖的 Sparse-BM25 查询；dense 写入和 dense 检索仍交给 Spring AI `MilvusVectorStore` 与 `VectorStoreDocumentRetriever`。BM25 Function 直接基于 Milvus collection 的 `content` 字段工作，`content` 字段使用 Milvus Chinese analyzer 适配中文商品文本；项目不再维护 `bm25Text` 元数据、IK Analyzer 预分词或 Redis BM25 双写链路。

#### `ParentChildHybridDocumentRetriever`

职责是融合两路召回结果：

- dense 子块召回
- BM25 子块召回

融合方式：

- 用 RRF（Reciprocal Rank Fusion）合并两路排序
- 在前 10 个候选里找“最大归一化分差”来截断结果
- 再让 `ParentChildDocumentRetriever` 统一回查父块

这意味着项目的知识库检索不是简单 `topK`，而是做了一个轻量但很实用的结果裁剪策略，目的是减少噪声上下文。

### 4.7 分层记忆：Spring AI ChatMemory + 长期摘要

这个模块把记忆分成两层：

- 短期记忆
- 长期记忆

#### 短期记忆

短期记忆由 Spring AI `MessageWindowChatMemory` 管理窗口，由自研 `RedisChatMemoryRepository` 持久化到 Redis 列表中。

特点：

- 每条消息都有递增序号
- 有时间窗口限制
- 有最大消息数限制，数量裁剪交给 Spring AI `MessageWindowChatMemory`
- 超出窗口的旧消息会被淘汰，并交给长期摘要服务处理
- Redis TTL 只作为清理兜底；空闲会话会在 TTL 前由定时任务做最终摘要，避免未淘汰但有价值的短期消息直接过期丢失

当前配置：

- `max-recent-messages: 12`
- `max-recent-age: 3650d`
- `short-term-ttl: 30d`
- `idle-summary-age: 1d`
- `idle-summary-scan-interval: 15m`

也就是说当前更偏向“按消息数控制窗口”，时间淘汰实际上非常宽松。

#### 长期记忆

长期记忆由 `LongTermMemoryAdvisor` 和 `LongTermMemoryService` 保留自研实现。当短期窗口淘汰旧消息时，系统不会直接丢弃，而是：

1. 异步把淘汰消息拼成 transcript
2. 调用模型做摘要
3. 把摘要作为 `memoryType=summary` 的文档写入 Milvus `memory_index`
4. 未来用户再次提问时，按语义相似度把这些摘要捞回来

因此，这套记忆不是简单聊天历史，而是“短期保细节，长期保摘要”的结构。

### 4.8 安全模块：`PromptSecurityFilter`

这个类解决两类问题：

- Prompt Injection
- 敏感信息泄露

处理方式：

- 用正则过滤高风险注入指令
  例如 `ignore all instructions`、`<execute>`、`reveal system prompt`
- 识别并掩码敏感值
  包括 API Key、密码、邮箱、身份证号、银行卡号、手机号
- 把处理后的用户输入放进 `<user_input>` 边界中，再交给模型
- 如果输出里出现了占位符，再在返回用户前恢复

这套做法的核心思想是：

- 用户输入是数据，不是系统指令
- 模型看到的是经过隔离和脱敏后的内容
- 用户最终看到的仍然是恢复后的自然结果

### 4.9 模型注册：`ChatModelRegistry`

这个类把“前端传来的模型 ID”映射成“底层真正调用的模型名”。

当前配置了 3 个模型：

- `qwen` -> `qwen-plus-2025-07-28`
- `deepseek` -> `deepseek-v3.2`
- `kimi-thinking` -> `kimi-k2-thinking`

默认模型是 `qwen`。

### 4.10 安全配置：`SecurityConfig`

当前后端大部分接口都需要认证，`/error`、`/actuator/health/**` 和 `/actuator/prometheus` 放行。

认证方式：

- HTTP Basic Auth
- Form Login

认证提供者使用商城登录接口：前端或调用方提交用户名和密码后，`MallAuthenticationProvider` 调用商城 `mall-auth` 登录，成功后把商城 token 写入 Redis TTL 缓存。后续 `/api/react` 如果没有显式传入 `X-Mall-Authorization`，会用当前登录用户的缓存 token 注册 `mall-mcp` 工具上下文。当前商城侧仍是明文密码匹配，生产化需要接入密码加密和统一用户中心。

另外还开放了本地 CORS：

- `http://localhost:*`
- `http://127.0.0.1:*`

这显然是为本地调试准备的，不是生产级安全收口策略。

### 4.11 前端

`frontend/` 目录是一个独立静态前端，不依赖额外打包工具。

主要能力：

- 保存后端地址、账号密码、会话 ID
- 拉取模型列表并切换模型
- 发起流式聊天请求
- 可切换是否启用联网搜索
- 导入 RAG 文档
- 在浏览器本地保存聊天记录和导入记录

前端默认请求地址是 `http://localhost:18082`。

## 5. 关键请求链路

### 5.1 ReAct 对话链路

```text
用户请求
-> ChatController(/api/react)
-> ReActAgent
-> PromptSecurityFilter
-> LongTermMemoryAdvisor + MessageChatMemoryAdvisor
-> 内置工具 + mall-mcp 工具 + 可选 WebSearch MCP 工具
-> LLM 流式输出
-> 恢复敏感占位符
-> 成功响应由 MessageChatMemoryAdvisor 写回；短路/fallback 由 ConversationMemoryService 写回
-> 返回前端
```

### 5.2 购物聊天路由链路

```text
用户请求
-> ChatController(/api/react)
-> ReActAgent.runStream
-> ShoppingRouteExecutor
-> ShoppingIntentRouter(qwen3-vl-8b-instruct)
-> 商城相关意图：注册 mall-mcp 上下文；失败则直接返回商城 MCP 调用失败
-> 高置信简单请求：SimpleTaskAgent 用简单任务小模型 + 限定工具完成；商城工具内部调用 mall-mcp
-> 复杂、低置信、槽位不足或最终下单：进入 ReActAgent 主模型链路，由模型调用 mall_* MCP 工具
-> 高置信图文复杂请求：注入 visual_context 文本，不传原图
-> 低置信图文请求：原图透传给主 Agent 兜底
-> 返回前端
```

### 5.3 文档导入链路

```text
原始文档
-> RagDocumentController(/api/rag/documents/import)
-> ParentChildDocumentIndexer
-> 父块写 Redis
-> 子块写 Milvus product_index
-> 后续 searchProductKnowledge 可检索
```

### 5.4 知识库查询链路

```text
模型调用 searchProductKnowledge
-> ParentChildHybridDocumentRetriever
-> Milvus dense 子块召回
-> Milvus Sparse-BM25 子块召回
-> RRF 融合 + 截断
-> 回查父块
-> 返回完整上下文给模型
```

## 6. 数据存储设计

### 6.1 Redis 中的几类数据

- 父文档正文
  Key 前缀：`rag:parent:`
- `sourceId -> parentIds` 映射
  Key 前缀：`rag:source:`
- 短期记忆消息列表
  Key 前缀：`memory:short:`
- 短期记忆状态
  Key 后缀：`:state`，记录 `lastTouchedAt`、`lastSummarizedSequence` 和 `lastSummaryAttemptAt`
- 活跃短期会话集合
  Key：`memory:short:conversations`，供空闲摘要扫描使用，避免用 Redis `KEYS memory:short:*:messages` 扫描线上 keyspace
- 导购偏好状态
  Key 前缀：`shopping:preference:`，默认 TTL 为 `app.shopping.preference-ttl`
- 商城登录 token 缓存
  Key 前缀：`mall:auth:`，默认 TTL 为 `app.mall.auth-cache-ttl`

Redis 不再承载商品向量、长期记忆向量或本地购物车；购物车真实数据只走 `mall-mcp`。Redis 只保留短期窗口、父块缓存、导购偏好状态和商城 token 缓存。

### 6.2 Milvus 索引拆分

项目将向量索引拆成两个 Milvus collection：

- `product_index`
  商品知识库子块，承载 dense vector 和 Sparse-BM25。
- `memory_index`
  长期记忆摘要，承载 `memoryType=summary` 的用户长期摘要。

`product_index` 主要元数据包括：

- `docType`
- `parentId`
- `sourceId`
- `title`
- `childIndex`
- `parentIndex`
- `documentHash`
- `productId`
- `skuId`
- `category`
- `brand`
- `price`
- `stock`
- `imageUrl`
- `attributes`
  结构化规格属性，来自 `/products/import` 的 `attributes` map；空 key/value 会被清理，同时规格参数仍会保留在正文中供语义和 BM25 召回使用。

`product_index.content` 用于商品标题、描述、卖点等正文召回，schema 初始化时启用 Milvus Chinese analyzer。`brand`、`category`、`productId`、`attributes` 等商品结构化信息继续保留在 metadata 中，不额外展开新的检索字段。

`memory_index` 主要元数据包括：

- `userId`
- `conversationKey`
- `memoryType`
- 时间与序号相关字段

`memory_index` 的 Milvus 文档主键使用稳定 UUID；原始会话标识和摘要覆盖的序列范围通过 `conversationKey`、`fromSequence`、`toSequence` 等 metadata 保留。

商品知识和长期记忆不再混放到同一个 `rag-index`，避免元数据 schema 演进、过滤条件和容量规划互相影响。

## 7. 配置说明

主要配置都在 `src/main/resources/application.yml`。

### 7.1 基础配置

- 服务端口：`18082`，可通过 `SERVER_PORT` 覆盖
- Redis：默认 `localhost:6379`
- Milvus：默认 `localhost:19530`
- 商品向量 collection：默认 `product_index`
- 长期记忆 collection：默认 `memory_index`
- `/api/react` 流式接口异步超时：默认 `180s`
- 导购偏好状态 TTL：默认 `7d`
- 商城登录接口超时：默认 `5s`
- 商城 token 缓存 TTL：默认 `2h`

这些配置可以用环境变量覆盖：

```powershell
$env:REDIS_HOST="localhost"
$env:REDIS_PORT="6379"
$env:REDIS_PASSWORD=""
$env:MILVUS_HOST="localhost"
$env:MILVUS_PORT="19530"
$env:PRODUCT_VECTOR_COLLECTION="product_index"
$env:MEMORY_VECTOR_COLLECTION="memory_index"
$env:PRODUCT_BM25_ENABLED="true"
$env:RAG_DENSE_CHILD_TOP_K="24"
$env:RAG_BM25_CHILD_TOP_K="8"
$env:RAG_MAX_PARENT_RESULTS="6"
$env:SPRING_MVC_ASYNC_REQUEST_TIMEOUT="180s"
$env:SHOPPING_PREFERENCE_TTL="7d"
$env:MALL_REQUEST_TIMEOUT="5s"
$env:MALL_AUTH_CACHE_TTL="2h"
```

注意：Redis 不再要求 Redis Stack；普通 Redis 即可。商品 dense 检索和 Sparse-BM25 都在 Milvus 中完成。`SPRING_MVC_ASYNC_REQUEST_TIMEOUT` 用于避免模型慢响应时流式 `/api/react` 过早触发 Spring MVC 默认异步超时。

### 7.2 RAG 召回配置

商品知识库召回参数集中在 `app.rag.retrieval`，由 `RagRetrievalProperties` 绑定：

- `dense-child-top-k`：dense 子块召回数量，默认 `24`
- `dense-similarity-threshold`：dense 相似度阈值，默认 `0.2`
- `dense-fallback-top-k`：过滤检索失败后的 dense 兜底召回数量，默认 `32`
- `bm25-child-top-k`：Sparse-BM25 子块召回数量，默认 `8`
- `rrf-k`：RRF 融合平滑参数，默认 `60`
- `min-child-results-to-keep`：动态截断至少保留的子块数，默认 `4`
- `max-child-results-to-consider`：动态截断最多观察的融合候选数，默认 `12`
- `max-parent-results`：最终回查父块数量上限，默认 `6`
- `min-normalized-gap-to-truncate`：动态截断最小归一化分差，默认 `0.0`，表示保持原有最大分差截断行为

这些参数可通过 `RAG_DENSE_CHILD_TOP_K`、`RAG_BM25_CHILD_TOP_K`、`RAG_MAX_PARENT_RESULTS` 等环境变量覆盖。

### 7.3 模型配置

当前模型走 OpenAI 兼容协议，但实际 base URL 指向：

- `https://dashscope.aliyuncs.com/compatible-mode/v1`

需要环境变量：

- `DASHSCOPE_API_KEY`

购物意图路由模型默认配置为：

- `app.ai.intent-router.enabled=true`
- `app.ai.intent-router.model=${QWEN_VL_ROUTER_MODEL:qwen3-vl-8b-instruct}`
- `app.ai.intent-router.confidence-threshold=0.7`

路由配置保持在 `application.yml`，Java 侧直接用 `@Value` 读取，不再单独维护 `IntentRouterProperties`。路由模型不出现在 `/api/models/chat`，前端模型列表只用于选择主 Agent 回答模型。

Embedding 模型当前是：

- `text-embedding-v3`

### 7.4 MCP 联网搜索

项目已经接入 Spring AI MCP Client，并给 DashScope WebSearch MCP 做了授权头补充。

启用条件：

- `webSearchEnabled=true`
- `app.mcp.websearch.api-key` 有值
- 外部 MCP tool callback 能正常加载

### 7.5 商城 MCP 工具

商城商品、购物车和普通订单不再通过 RAGAgent 内置 function calling 直连商城 REST，而是通过独立 `mall-mcp` 服务接入。主 Agent 通过 Spring AI `SyncMcpToolCallbackProvider` 从 `mall-mcp` 发现并注册 `mall_*` 工具，当前约定工具包括：

- `mall_search_products`
- `mall_get_product_detail`
- `mall_add_to_cart`
- `mall_view_cart`
- `mall_prepare_order`
- `mall_create_order`

当路由判断本轮需要实时商城能力时，RAGAgent 会在进入主 Agent 或简单任务前先调用：

```http
POST /internal/mcp/mall/context
X-Mcp-Context-Secret: <secret>
```

把当前 `sessionId`、`X-Mall-Authorization`、Redis 中缓存的商城 token，或 Basic 登录信息注册给 `mall-mcp`。主 Agent 的工具定义和调用委托给 Spring AI MCP 的 `SyncMcpToolCallbackProvider` / `SyncMcpToolCallback`，底层仍由 `McpSyncClient` 走 Streamable HTTP MCP endpoint `/mcp`；RAGAgent 不再改写工具参数和返回值，`toolContext` 只保留 `userId/sessionId`。如果本轮需要商城工具但工具发现失败，会直接返回“商城 MCP 调用失败”。

图文低置信请求默认不会强行注册商城工具；只有文本里明确出现实时价格、库存、相似款、加购、购物车或订单等实时商城意图时，才会注册 `mall_*` 工具。这样普通“看图给搭配/场景建议”不会因为商城 MCP 不可用而失败。

### 7.6 接口耗时监控

项目已接入 Spring Boot Actuator、Micrometer Prometheus、Prometheus 和 Grafana。

后端暴露：

- `GET /actuator/health`
- `GET /actuator/prometheus`
- `GET /actuator/metrics`，需要登录

Prometheus 默认抓取 Docker 容器视角下的 `host.docker.internal:18082/actuator/prometheus`，也就是宿主机运行的 RAGAgent 后端服务。Grafana 会自动加载 Prometheus 数据源和 `RAGAgent HTTP 接口耗时` 仪表盘。

常用 PromQL：

```promql
histogram_quantile(0.95, sum by (le, uri, method) (rate(http_server_requests_seconds_bucket{application="rag-agent"}[5m])))
```

```promql
sum by (uri, method) (rate(http_server_requests_seconds_sum{application="rag-agent"}[5m]))
/
sum by (uri, method) (rate(http_server_requests_seconds_count{application="rag-agent"}[5m]))
```

注意：`/api/react` 是流式接口，Actuator 的 HTTP 耗时统计的是整个请求完成时间。如果要看首 token 时间，需要在 Agent 内部再加自定义 `Timer`。

## 8. 当前可用接口

### 8.1 获取模型列表

```http
GET /api/models/chat
Authorization: Basic ...
```

### 8.2 ReAct 图文对话

该接口是唯一 Agent loop 入口，会把文本和图片交给 `ReActAgent.runStream(...)`。Agent 起点再经过 `ShoppingRouteExecutor` 与 `ShoppingIntentRouter`：即使不上传图片，也会先用小模型判断意图；只有复杂、低置信、槽位不足或路由失败时才进入主模型链路。

```http
POST /api/react
Authorization: Basic ...
Content-Type: multipart/form-data

message=帮我找这双鞋的相似款，预算500以内
image=@shoe.png
sessionId=shopping-demo
modelId=qwen
webSearchEnabled=false
```

也可以只传文本或传图片 URL：

```http
POST /api/react
Authorization: Basic ...
Content-Type: multipart/form-data

message=按这张图推荐通勤可穿的款式
imageUrl=https://example.com/images/shoe.png
sessionId=shopping-demo
```

商城业务不再暴露直连 REST。查商品、查购物车、加购、确认订单和普通下单都通过自然语言进入 `/api/react`，由 Agent 调用 `mall-mcp` 暴露的 `mall_*` MCP 工具。`MallApiClient` 仅保留登录认证用途，不再承载商品、购物车或订单 function calling。

完整手工测试步骤见 [TESTING.md](TESTING.md)。

### 8.3 导入知识库文档

```http
POST /api/rag/documents/import
Authorization: Basic ...
Content-Type: application/json

{
  "sourceId": "product-P1001",
  "title": "云跑 AirLite 缓震跑步鞋",
  "content": "商品描述、规格参数、评价摘要和导购话术正文",
  "productId": "P1001",
  "skuId": "SKU-P1001-BLK-42",
  "category": "运动鞋",
  "brand": "Stride",
  "price": 499,
  "stock": 38,
  "imageUrl": "https://example.com/images/p1001.jpg"
}
```

### 8.4 导入结构化商品知识

```http
POST /api/rag/documents/products/import
Authorization: Basic ...
Content-Type: application/json

{
  "productId": "P1001",
  "skuId": "SKU-P1001-BLK-42",
  "title": "云跑 AirLite 缓震跑步鞋",
  "brand": "Stride",
  "category": "运动鞋",
  "price": 499,
  "stock": 38,
  "imageUrl": "https://example.com/images/p1001.jpg",
  "description": "轻量中底和透气鞋面，适合日常慢跑与城市通勤。",
  "reviewSummary": "用户普遍反馈脚感轻、缓震明显，但雨天防滑一般。",
  "guideText": "适合预算 500 元左右、需要兼顾通勤和慢跑的人群。",
  "attributes": {
    "颜色": "黑色",
    "尺码": "40-44"
  }
}
```

## 9. 启动方式

### 9.1 启动 Redis + Milvus

如果本机没有 Redis 和 Milvus，先启动 Docker Desktop，然后在项目根目录执行：

```powershell
docker compose up -d redis etcd minio milvus
```

确认端口连通：

```powershell
Test-NetConnection -ComputerName localhost -Port 6379
Test-NetConnection -ComputerName localhost -Port 19530
```

两个端口都看到 `TcpTestSucceeded : True` 后再启动后端。

如果不用 Docker，也可以自行启动 Redis 和 Milvus，只要保证：

- Redis 服务监听在 `REDIS_HOST:REDIS_PORT`
- Milvus 服务监听在 `MILVUS_HOST:MILVUS_PORT`
- Milvus 版本为 2.5+，商品 Sparse-BM25 依赖 Milvus BM25 Function
- Redis 不需要 RediSearch 或 Redis Stack 模块

### 9.2 启动后端

```powershell
mvn spring-boot:run
```

如果启动时报：

```text
Failed to connect to localhost:6379
```

说明后端无法连接 Redis。优先检查：

- Docker Desktop 是否已启动
- `docker compose ps` 里 `redis` 是否为 running
- `Test-NetConnection localhost -Port 6379` 是否成功
- `REDIS_HOST` / `REDIS_PORT` 是否指向了实际 Redis

如果启动时报 Milvus 连接或 collection 初始化异常，优先检查：

- `docker compose ps` 里 `etcd`、`minio`、`milvus` 是否为 running
- `Test-NetConnection localhost -Port 19530` 是否成功
- `MILVUS_HOST` / `MILVUS_PORT` 是否指向了实际 Milvus
- 已存在的 `product_index` collection 是否是旧 schema；本项目不会自动迁移旧 Redis `rag-index` 或旧 Milvus collection
- 如果 `/api/react` 长回答在约 30 秒后断开，检查 `spring.mvc.async.request-timeout` 是否仍是默认值；当前推荐保持 `180s`

### 9.3 启动前端

```powershell
cd frontend
node server.js 4173
```

然后访问：

```text
http://localhost:4173
```

### 9.4 启动监控

先启动后端，再启动 Prometheus 和 Grafana：

```powershell
docker compose up -d prometheus grafana
```

访问地址：

- Prometheus：`http://localhost:9090`
- Grafana：`http://localhost:3000`
- Grafana 默认账号：`admin`
- Grafana 默认密码：`admin`

Grafana 打开后进入 `Dashboards -> Rag Agent -> RAGAgent HTTP 接口耗时`。如果 Prometheus `Status -> Targets` 里 `rag-agent` 是 down，先确认后端已经在宿主机 `18082` 端口启动，并且 `http://localhost:18082/actuator/prometheus` 能访问。

## 10. 测试覆盖情况

当前重点覆盖了这些模块：

- `ReActAgentTest`
  验证导购工具注册、`mall_*` MCP 工具默认可用、模型切换、ChatClient 记忆 advisor 接入、外部工具合并、脱敏恢复、模型连接中断降级，以及 Agent 起点的小模型路由预检/回退
- `BuiltInToolsTest`
  验证商品知识库检索渲染和短期偏好更新
- `ChatControllerTest`
  验证统一 `/api/react` 入口的 multipart 图文输入、流式输出、模型参数和商城 Token/Basic Auth 透传
- `ShoppingIntentRouterTest`
  验证路由 JSON 解析、非法 JSON 回退和图文 media 透传到路由模型
- `ShoppingRouteExecutorTest` / `SimpleTaskAgentTest`
  验证高置信简单请求短路、限定工具注册、MCP 调用、低置信回退、多候选不自动加购和 MCP 不可用失败返回
- `PromptSecurityFilterTest`
  验证注入过滤与敏感值恢复
- `ParentChildDocumentIndexerTest`
  验证父子块切分、商品元数据透传与商品向量入库元数据
- `ParentChildDocumentRetrieverTest`
  验证商品子块命中后回查父块
- `ParentChildHybridDocumentRetrieverTest`
  验证 Milvus dense + Sparse-BM25 融合与截断
- `LongTermMemoryAdvisorTest` / `RedisChatMemoryRepositoryTest`
  验证长期记忆注入、Redis 短期消息读写、TTL 刷新、数量/时间淘汰摘要触发、空闲会话最终摘要和重复扫描去重

自动化与手工测试命令集中维护在 [TESTING.md](TESTING.md)。

## 11. 下一步计划

- 补充 `mall-mcp` 不可用场景的前端端到端验证，确保商品卡片、购物车和订单链路都稳定显示“商城 MCP 调用失败”。
- 新增 ESCI/SQID 子集评测入口，使用 `Recall@K`、`NDCG@10`、`MRR` 衡量商品检索和排序效果。
- 视需要再补一个商城登录辅助页；默认保持前端账号密码登录导购后端、后端代理登录商城并缓存 Token 的方案。
