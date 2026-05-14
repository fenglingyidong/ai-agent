# RAGAgent 项目说明

## 1. 项目定位

这是一个基于 Spring Boot 与 Spring AI 的 Agent 原型项目，目标不是只做一个简单的聊天接口，而是把下面几类能力串成一条完整链路：

- ReAct 风格推理与工具调用
- 基于 Redis 的 RAG 检索
- 短期记忆 + 长期摘要记忆
- Prompt Injection 与敏感信息保护
- 可选的 MCP 联网搜索
- 一个可直接联调的零依赖静态前端

从代码现状来看，这个项目更准确地说是一个“带知识库、记忆、安全防护和工具系统的 ReAct Agent Demo”，而不是一个通用的企业级完整产品。

## 2. 技术栈

| 类别 | 技术 |
| --- | --- |
| 后端框架 | Spring Boot 3.4.1 |
| Java 版本 | Java 17 |
| AI 框架 | Spring AI 1.1.4 |
| 模型接入 | OpenAI 兼容协议，当前接到阿里云 DashScope |
| 向量存储 | Redis Vector Store |
| 全文检索 | Redis Stack / RediSearch `FT.SEARCH` |
| Redis 客户端 | Jedis |
| 安全 | Spring Security Basic Auth + Form Login |
| 前端 | 原生 HTML / CSS / JavaScript |

## 3. 目录结构

```text
RAGAgent
├─ src/main/java/com/example/ragagent
│  ├─ config        配置类
│  ├─ controller    HTTP 接口
│  ├─ memory        分层记忆
│  ├─ rag           RAG 常量与实现
│  ├─ security      Prompt 安全处理
│  ├─ service       Agent 与模型注册
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

- `GET /api/react`
  作用：执行 ReAct Agent 的流式推理
  参数：`message`、可选 `modelId`、可选 `webSearchEnabled`
- `GET /api/models/chat`
  作用：返回当前可选模型列表和默认模型

`/api/react` 返回的是流式纯文本，控制器通过 `StreamingResponseBody` 持续把模型生成的内容刷给前端。

#### `RagDocumentController`

对外暴露：

- `POST /api/rag/documents/import`

这个接口接收一篇原始文档的 `sourceId`、`title`、`content`，然后调用索引器做父子分块入库，并返回：

- 生成了多少个父块
- 生成了多少个子块
- 对应的 `parentIds`
- 对应的 `childIds`

这说明当前知识库是“手动导入”的，不是扫描目录或自动同步型知识库。

### 4.3 Agent 核心：`ReActAgent`

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

1. 用户请求进入 `runStream(...)`
2. `PromptSecurityFilter.secure(...)` 对输入做注入过滤和敏感信息脱敏
3. `HierarchicalMemoryAdvisor.loadMemoryContext(...)` 加载记忆
4. 拼接消息历史：长期记忆系统消息 + 短期消息 + 当前用户消息
5. 根据 `webSearchEnabled` 决定是否把 MCP 外部工具暴露给模型
6. 根据 `modelId` 从 `ChatModelRegistry` 选择模型参数
7. 通过 Spring AI `ChatClient` 发起流式调用
8. 输出时把类似 `[[PHONE_1]]` 这样的占位符恢复成原值
9. 调用 `rememberFinalTurn(...)` 写回本轮问答

这个类虽然名为 ReAct，但实现方式并不是自己手写 Thought/Action/Observation 循环，而是借助 Spring AI 的工具调用能力，让模型在系统提示词约束下自动调用工具。

### 4.4 内置工具：`BuiltInTools`

当前内置了 3 个工具：

- `getWeather`
  返回模拟天气数据
- `calculator`
  使用 SpEL 计算简单表达式
- `searchKnowledgeBase`
  调用 `DocumentRetriever` 做知识库检索

其中最重要的是 `searchKnowledgeBase`。因为项目里把 `ParentChildHybridDocumentRetriever` 标成了 `@Primary`，所以这个工具默认走的是“父子块 + dense + BM25 混合召回”的知识库检索链路。

### 4.5 RAG 模块

RAG 模块是本项目的另一条主线，主要由下面几个类组成。

#### `ParentChildDocumentIndexer`

职责是导入文档并建立父子分块索引。

处理方式：

- 先把整篇文档切成较大的父块
- 再把每个父块切成更小的子块
- 父块全文单独保存到 Redis
- 子块写入向量库

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

#### `RedisBm25ChildChunkRetriever`

职责是稀疏检索路径：

- 直接对 Redis Stack 发 `FT.SEARCH`
- 按 BM25 思路做全文召回
- 返回子块文档

这个类说明项目并不满足于“只有向量检索”，而是主动把 Redis 原生全文检索能力也接进来了。

#### `ParentChildHybridDocumentRetriever`

职责是融合两路召回结果：

- dense 子块召回
- BM25 子块召回

融合方式：

- 用 RRF（Reciprocal Rank Fusion）合并两路排序
- 在前 10 个候选里找“最大归一化分差”来截断结果
- 再让 `ParentChildDocumentRetriever` 统一回查父块

这意味着项目的知识库检索不是简单 `topK`，而是做了一个轻量但很实用的结果裁剪策略，目的是减少噪声上下文。

### 4.6 分层记忆：`HierarchicalMemoryAdvisor`

这个模块把记忆分成两层：

- 短期记忆
- 长期记忆

#### 短期记忆

短期记忆由 `RedisSlidingWindowMemoryStore` 管理，存放在 Redis 列表中。

特点：

- 每条消息都有递增序号
- 有时间窗口限制
- 有最大消息数限制
- 超出窗口的旧消息会被淘汰

当前配置：

- `max-recent-messages: 12`
- `max-recent-age: 3650d`

也就是说当前更偏向“按消息数控制窗口”，时间淘汰实际上非常宽松。

#### 长期记忆

当短期窗口淘汰旧消息时，系统不会直接丢弃，而是：

1. 异步把淘汰消息拼成 transcript
2. 调用模型做摘要
3. 把摘要作为 `memoryType=summary` 的文档写入向量库
4. 未来用户再次提问时，按语义相似度把这些摘要捞回来

因此，这套记忆不是简单聊天历史，而是“短期保细节，长期保摘要”的结构。

### 4.7 安全模块：`PromptSecurityFilter`

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

### 4.8 模型注册：`ChatModelRegistry`

这个类把“前端传来的模型 ID”映射成“底层真正调用的模型名”。

当前配置了 3 个模型：

- `qwen` -> `qwen-plus-2025-07-28`
- `deepseek` -> `deepseek-v3.2`
- `kimi-thinking` -> `kimi-k2-thinking`

默认模型是 `qwen`。

### 4.9 安全配置：`SecurityConfig`

当前后端所有接口都需要认证，只有 `/error` 放行。

认证方式：

- HTTP Basic Auth
- Form Login

默认演示账号：

- 用户名：`demo`
- 密码：`demo123`

另外还开放了本地 CORS：

- `http://localhost:*`
- `http://127.0.0.1:*`

这显然是为本地调试准备的，不是生产级安全收口策略。

### 4.10 前端

`frontend/` 目录是一个独立静态前端，不依赖额外打包工具。

主要能力：

- 保存后端地址、账号密码、会话 ID
- 拉取模型列表并切换模型
- 发起流式聊天请求
- 可切换是否启用联网搜索
- 导入 RAG 文档
- 在浏览器本地保存聊天记录和导入记录

前端默认请求地址是 `http://localhost:8080`。

## 5. 关键请求链路

### 5.1 ReAct 对话链路

```text
用户请求
-> ChatController(/api/react)
-> ReActAgent
-> PromptSecurityFilter
-> HierarchicalMemoryAdvisor
-> 内置工具 + 可选 MCP 工具
-> LLM 流式输出
-> 恢复敏感占位符
-> 记忆写回
-> 返回前端
```

### 5.2 文档导入链路

```text
原始文档
-> RagDocumentController(/api/rag/documents/import)
-> ParentChildDocumentIndexer
-> 父块写 Redis
-> 子块写 Vector Store
-> 后续 searchKnowledgeBase 可检索
```

### 5.3 知识库查询链路

```text
模型调用 searchKnowledgeBase
-> ParentChildHybridDocumentRetriever
-> dense 子块召回
-> BM25 子块召回
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
- 向量库中的子块
  索引名默认是 `rag-index`
- 向量库中的长期记忆摘要
  元数据里带 `memoryType=summary`

### 6.2 向量库元数据

项目给 Redis Vector Store 显式注册了不少元数据字段，主要包括：

- `docType`
- `parentId`
- `sourceId`
- `title`
- `childIndex`
- `parentIndex`
- `documentHash`
- `userId`
- `conversationKey`
- `memoryType`
- 时间与序号相关字段

这使得同一个 Redis 向量索引里，可以同时放：

- RAG 子块
- 长期记忆摘要

然后再通过过滤条件把它们隔离开。

## 7. 配置说明

主要配置都在 `src/main/resources/application.yml`。

### 7.1 基础配置

- 服务端口：`8080`
- Redis：`localhost:6379`
- Vector Store 索引：`rag-index`
- Redis key 前缀：`rag:`

### 7.2 模型配置

当前模型走 OpenAI 兼容协议，但实际 base URL 指向：

- `https://dashscope.aliyuncs.com/compatible-mode/v1`

需要环境变量：

- `DASHSCOPE_API_KEY`

Embedding 模型当前是：

- `text-embedding-v4`

### 7.3 MCP 联网搜索

项目已经接入 Spring AI MCP Client，并给 DashScope WebSearch MCP 做了授权头补充。

启用条件：

- `webSearchEnabled=true`
- `app.mcp.websearch.api-key` 有值
- 外部 MCP tool callback 能正常加载

## 8. 当前可用接口

### 8.1 获取模型列表

```http
GET /api/models/chat
Authorization: Basic ...
```

### 8.2 ReAct 对话

```http
GET /api/react?message=你好&modelId=qwen&webSearchEnabled=false
Authorization: Basic ...
```

### 8.3 导入知识库文档

```http
POST /api/rag/documents/import
Authorization: Basic ...
Content-Type: application/json

{
  "sourceId": "employee-handbook-2026",
  "title": "员工手册",
  "content": "这里是文档正文"
}
```

## 9. 启动方式

### 9.1 启动后端

```powershell
mvn spring-boot:run
```

### 9.2 启动前端

```powershell
cd frontend
node server.js 4173
```

然后访问：

```text
http://localhost:4173
```

## 10. 测试覆盖情况

当前重点覆盖了这些模块：

- `ReActAgentTest`
  验证工具注册、模型切换、记忆预加载、外部工具合并、脱敏恢复
- `PromptSecurityFilterTest`
  验证注入过滤与敏感值恢复
- `ParentChildDocumentIndexerTest`
  验证父子块切分与入库元数据
- `ParentChildDocumentRetrieverTest`
  验证子块命中后回查父块
- `ParentChildHybridDocumentRetrieverTest`
  验证 dense + BM25 融合与截断
- `HierarchicalMemoryAdvisorTest`
  验证记忆写入与消息结构

