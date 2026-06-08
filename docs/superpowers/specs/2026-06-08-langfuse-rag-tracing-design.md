# Langfuse RAG 链路追踪设计

## 目录

- [目标](#目标)
- [范围](#范围)
- [推荐方案](#推荐方案)
- [本地部署](#本地部署)
- [Trace 结构](#trace-结构)
- [RAG Span 设计](#rag-span-设计)
- [数据边界](#数据边界)
- [配置](#配置)
- [验证](#验证)
- [后续扩展](#后续扩展)

## 目标

第一阶段接入本地自托管 Langfuse，目标是实现 `/api/react` 导购请求的链路追踪，并重点展开 RAG 内部细节。

一次 Trace 应能回答：

- 用户请求是否进入快车道还是 ReAct 主链路。
- RAG 检索在总耗时中占比多少。
- Dense、BM25、RRF、动态截断、父块回查各自耗时和产出数量。
- BM25 是否异常降级。
- 最终返回了哪些父文档元数据。

## 范围

本阶段覆盖：

- 本地 Langfuse 自托管运行说明。
- Java 应用通过 OpenTelemetry OTLP/HTTP 上报到 Langfuse。
- `ParentChildHybridDocumentRetriever` 内部手动 Span。
- 顶层 Agent 请求上下文属性。
- 工具调用和 RAG 检索的可观测元数据。

本阶段不覆盖：

- Langfuse Dataset、Experiment、Score 评测闭环。
- 大模型自动打分器。
- 全量 Prompt、完整商品文档正文、用户敏感信息落入 Langfuse。
- 替换现有 Prometheus/Grafana 指标体系。

## 推荐方案

采用 **OpenTelemetry 自动接入 Spring AI + 手动 Span 补齐 RAG 细节**。

理由：

- Langfuse 官方支持 OpenTelemetry 接入，Spring AI 链路可以复用 OTel 语义。
- 项目已有 Actuator、Micrometer、Prometheus，继续用标准可观测协议更稳。
- RAG 内部细节属于业务语义，必须在代码中手动埋 Span，自动探针无法知道 RRF、截断和父块回查含义。
- 不需要在业务方法之间显式传递 Langfuse Trace 对象，降低侵入。

备选方案暂不采用：

- 自封装 Langfuse Client：控制力强，但会把 trace 生命周期散落到业务代码。
- 仅日志加 Prometheus：成本低，但 Langfuse 里无法看到完整调用树。

## 本地部署

建议新增独立目录：

```text
observability/langfuse/
  docker-compose.yml
  README.md
```

本地 Langfuse 不并入主 `docker-compose.yml` 的默认依赖，避免普通开发启动 Redis、Milvus 时被 Langfuse 的 Postgres、ClickHouse 等服务拖慢。

Java 应用上报地址：

```text
http://localhost:3000/api/public/otel
```

认证使用 Langfuse 项目生成的 public key 和 secret key，通过环境变量注入，不写入仓库。

## Trace 结构

顶层 Trace 名称：

```text
POST /api/react
```

建议顶层属性：

| 属性 | 来源 | 说明 |
| --- | --- | --- |
| `app.user_id` | `ChatController` / `ReActAgent` | 当前登录用户或 `anonymous` |
| `app.session_id` | `ChatController` | 会话 ID |
| `app.model_id` | 请求参数 | 用户选择的模型 ID |
| `app.web_search_enabled` | 请求参数 | 是否启用 WebSearch MCP |
| `app.media_count` | `media.size()` | 上传或 URL 图片数量 |
| `app.route.short_circuit` | `RoutedAgentRequest` | 是否快车道短路 |
| `app.route.mall_tools_allowed` | `RoutedAgentRequest` | 主链路是否允许 mall 工具 |

主要子 Span：

```text
react.request
  security.prompt_filter
  shopping.route
  shopping.fast_lane 或 react.core
    llm.stream
    tool.searchProductKnowledge
      rag.hybrid.retrieve
        rag.dense.retrieve
        rag.bm25.retrieve
        rag.rrf.rank
        rag.dynamic_truncate
        rag.parent.load
    tool.mall_*
  conversation.log
```

第一版允许只确保 RAG 相关 Span 完整，其他 Span 可先记录关键属性和耗时。

## RAG Span 设计

核心落点是 `ParentChildHybridDocumentRetriever.retrieve(Query query)`。

### `rag.hybrid.retrieve`

父 Span，覆盖一次混合检索整体过程。

属性：

| 属性 | 说明 |
| --- | --- |
| `rag.query.length` | 规范化 query 字符长度 |
| `rag.dense.top_k` | `properties.getDenseChildTopK()` |
| `rag.bm25.top_k` | `properties.getBm25ChildTopK()` |
| `rag.rrf.k` | `properties.getRrfK()` |
| `rag.max_parent_results` | `properties.getMaxParentResults()` |
| `rag.result.parent_count` | 最终父文档数量 |

### `rag.dense.retrieve`

覆盖 `denseRetriever.retrieveChildDocuments(normalizedQuery)`。

属性：

- `rag.result.child_count`
- `rag.dense.similarity_threshold`
- `rag.dense.fallback_top_k`
- `rag.status=ok/error`

### `rag.bm25.retrieve`

覆盖 BM25 异步召回，包含 timeout 和降级结果。

属性：

- `rag.result.child_count`
- `rag.bm25.future_timeout_ms`
- `rag.bm25.rpc_deadline_ms`
- `rag.bm25.degraded=true/false`
- `rag.status=ok/timeout/error`

### `rag.rrf.rank`

覆盖 `rankWithReciprocalRankFusion(...)`。

属性：

- `rag.input.dense_child_count`
- `rag.input.bm25_child_count`
- `rag.result.fused_child_count`
- `rag.result.top_child_ids`

`top_child_ids` 最多记录前 10 个 child document id。

### `rag.dynamic_truncate`

覆盖 `truncateByLargestNormalizedGap(...)`。

属性：

- `rag.input.fused_child_count`
- `rag.result.truncated_child_count`
- `rag.truncate.min_child_results_to_keep`
- `rag.truncate.max_child_results_to_consider`
- `rag.truncate.min_normalized_gap`

如后续需要精细诊断，可额外记录最大 gap 和截断位置。

### `rag.parent.load`

覆盖 `denseRetriever.loadParentDocuments(fusedChildDocuments)`。

属性：

- `rag.input.child_count`
- `rag.result.parent_count`
- `rag.result.parents`

`rag.result.parents` 使用 JSON 字符串，最多记录前 10 条安全元数据：

```json
[
  {
    "rank": 1,
    "sourceId": "product-P1001",
    "title": "云跑 AirLite 缓震跑步鞋"
  }
]
```

## 数据边界

允许进入 Langfuse：

- 用户、会话、模型、路由、工具名等链路元数据。
- query 长度、图片数量、召回数量、耗时、降级状态。
- 文档 `sourceId`、`title`、rank、score。
- 工具输入输出长度。

默认不进入 Langfuse：

- 用户原始完整输入。
- 脱敏前敏感值。
- 图片二进制内容。
- 商品知识库完整正文。
- 商城 token、Basic Auth 密码、MCP context secret。
- 完整工具输入输出 JSON。

如后续确实需要调试 prompt，可增加独立开关 `app.observability.langfuse.capture-prompt`，默认必须为 `false`。

## 配置

建议新增应用配置：

```yaml
app:
  observability:
    langfuse:
      enabled: ${LANGFUSE_ENABLED:false}
      base-url: ${LANGFUSE_BASE_URL:http://localhost:3000}
      capture-prompt: ${LANGFUSE_CAPTURE_PROMPT:false}
```

建议运行时环境变量：

```powershell
$env:LANGFUSE_ENABLED="true"
$env:OTEL_SERVICE_NAME="rag-agent"
$env:OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:3000/api/public/otel"
$env:OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic <base64(publicKey:secretKey)>"
```

OpenTelemetry Java Agent 或 Spring Boot OTel starter 二选一。第一版推荐 Java Agent，减少项目依赖改动；如果后续需要更细的自动配置，再考虑引入 OTel Spring Boot starter。

## 验证

本地验证步骤：

1. 启动本地 Langfuse。
2. 在 Langfuse 创建项目，生成 public key 和 secret key。
3. 设置 OTLP 环境变量并启动 Spring Boot 应用。
4. 导入至少 2 条商品知识。
5. 调用 `/api/react`，问题中触发 `searchProductKnowledge`。
6. 在 Langfuse 中确认出现 `POST /api/react` Trace。
7. 确认 Trace 中包含：
   - `rag.hybrid.retrieve`
   - `rag.dense.retrieve`
   - `rag.bm25.retrieve`
   - `rag.rrf.rank`
   - `rag.dynamic_truncate`
   - `rag.parent.load`
8. 确认 `rag.parent.load` 能看到父文档 `sourceId` 和 `title`，但没有完整正文。
9. 人为关闭或破坏 BM25 依赖，确认 `rag.bm25.degraded=true` 且主链路继续返回。

自动化验证：

- 为 RAG tracing helper 增加单元测试，验证 span 属性构造、文档元数据裁剪和敏感字段不落入 attributes。
- 保留现有 `ParentChildHybridDocumentRetrieverTest`，确认接入 tracing 后召回行为不变。

## 后续扩展

链路追踪稳定后，再进入第二阶段评测体系：

- 把 DuReader Recall@K 结果写入 Langfuse Score。
- 新增 ESCI/SQID 导购评测 Dataset。
- 对 `tool.searchProductKnowledge` 增加 hit/miss 样本追踪。
- 对最终回答增加人工或模型打分：事实一致性、工具调用准确率、推荐可解释性。
- 将 Langfuse trace id 写入 `conversation_turns`，方便 MySQL 会话流水和 Langfuse 互跳。
