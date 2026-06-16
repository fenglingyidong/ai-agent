# Langfuse 本地可观测环境

## 目录

- [启动](#启动)
- [停止和清理](#停止和清理)
- [创建项目密钥](#创建项目密钥)
- [启动 Java 应用](#启动-java-应用)
- [开发调试内容捕获](#开发调试内容捕获)
- [验证 Trace](#验证-trace)
- [数据边界](#数据边界)

## 启动

本目录使用 Langfuse 官方 self-host Docker Compose 的本地开发配置，独立于仓库根目录的 `docker-compose.yml`，避免拖慢普通开发启动。

以下命令默认从仓库根目录执行：

```powershell
docker compose -f observability/langfuse/docker-compose.yml up -d
```

打开 `http://localhost:3001`，完成初始化。

当前 compose 中的默认 secret 仅用于本地开发。不要把这些默认值用于共享环境、测试环境或生产环境。

`TELEMETRY_ENABLED` 是 Langfuse 自身遥测开关；敏感场景可在启动前设为 `false`。

如果已经进入 `observability/langfuse` 目录，也可以直接启动：

```powershell
docker compose up -d
```

## 停止和清理

以下命令默认从仓库根目录执行。

停止本地 Langfuse：

```powershell
docker compose -f observability/langfuse/docker-compose.yml down
```

如需同时清理本地数据卷：

```powershell
docker compose -f observability/langfuse/docker-compose.yml down -v
```

## 创建项目密钥

在 Langfuse 项目设置中创建 public key 和 secret key。

生成 Basic Auth：

```powershell
$pair = "pk-lf-xxx:sk-lf-xxx"
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
```

## 启动 Java 应用

`LANGFUSE_ENABLED=true` 开启应用侧手动 RAG/tool tracing；OTel Java Agent 是否上报仍由 `OTEL_EXPORTER_OTLP_*` 控制。

当前 Java Agent 上报以 `OTEL_EXPORTER_OTLP_*` 为准，`LANGFUSE_BASE_URL` 暂不参与 Java Agent 上报；如果后续使用应用侧 Langfuse 链接或客户端配置，本地 self-host 应设为 `http://localhost:3001`。

环境变量清单：

```text
LANGFUSE_ENABLED=true
LANGFUSE_BASE_URL=http://localhost:3001
LANGFUSE_CAPTURE_PROMPT=false
LANGFUSE_CAPTURE_TOOL_PAYLOAD=false
LANGFUSE_CAPTURE_RAG_CONTENT=false
LANGFUSE_MAX_CAPTURE_CHARS=8000
OTEL_SERVICE_NAME=rag-agent
OTEL_TRACES_EXPORTER=otlp
OTEL_METRICS_EXPORTER=none
OTEL_LOGS_EXPORTER=none
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:3001/api/public/otel
OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic <base64(publicKey:secretKey)>,x-langfuse-ingestion-version=4
```

下载 OpenTelemetry Java Agent 后，使用：

```powershell
$env:LANGFUSE_ENABLED="true"
$env:LANGFUSE_BASE_URL="http://localhost:3001"
$env:LANGFUSE_CAPTURE_PROMPT="false"
$env:LANGFUSE_CAPTURE_TOOL_PAYLOAD="false"
$env:LANGFUSE_CAPTURE_RAG_CONTENT="false"
$env:LANGFUSE_MAX_CAPTURE_CHARS="8000"
$env:OTEL_SERVICE_NAME="rag-agent"
$env:OTEL_TRACES_EXPORTER="otlp"
$env:OTEL_METRICS_EXPORTER="none"
$env:OTEL_LOGS_EXPORTER="none"
$env:OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
$env:OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:3001/api/public/otel"
$env:OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic $auth,x-langfuse-ingestion-version=4"
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-javaagent:D:/tools/opentelemetry-javaagent.jar"
```

如果使用 trace 专用 endpoint，则改用：

```powershell
$env:OTEL_EXPORTER_OTLP_TRACES_ENDPOINT="http://localhost:3001/api/public/otel/v1/traces"
$env:OTEL_EXPORTER_OTLP_TRACES_HEADERS="Authorization=Basic $auth,x-langfuse-ingestion-version=4"
```

## 开发调试内容捕获

默认配置只记录链路结构、工具名、输入输出长度和 RAG 召回摘要。个人开发或评测阶段如需定位“工具到底拿到了什么、RAG 到底召回了什么”，可以临时开启内容捕获：

```powershell
$env:LANGFUSE_CAPTURE_PROMPT="true"
$env:LANGFUSE_CAPTURE_TOOL_PAYLOAD="true"
$env:LANGFUSE_CAPTURE_RAG_CONTENT="true"
$env:LANGFUSE_MAX_CAPTURE_CHARS="20000"
```

开启后会额外写入：

- `llm.react.input`、`llm.react.output`：ReAct 输入和输出文本，受 `LANGFUSE_CAPTURE_PROMPT` 控制。
- `tool.input`、`tool.output`：RAG 工具和 MCP 工具的输入输出，受 `LANGFUSE_CAPTURE_TOOL_PAYLOAD` 控制。
- `rag.result.parents.debug`：RAG 父文档调试信息，包含 `sourceId`、`title`、`productId`、`skuId`、`brand`、`category` 和正文片段，受 `LANGFUSE_CAPTURE_RAG_CONTENT` 控制。

这些字段只会在对应链路真的执行时出现。例如模型没有调用 `searchProductKnowledge`，就不会有 `rag.hybrid.retrieve`、`rag.parent.load` 或 `rag.result.parents.debug`。

以上捕获会执行基础脱敏，手机号、`password`、`token`、`api_key`、`Authorization: Bearer ...` 等会被替换；但仍建议只在本地开发和评测阶段开启。

## 验证 Trace

调用 `/api/react` 并触发 `searchProductKnowledge` 后，在 Langfuse 中确认出现 `POST /api/react` trace，并包含：

- `rag.hybrid.retrieve`
- `rag.dense.retrieve`
- `rag.bm25.retrieve`
- `rag.rrf.rank`
- `rag.dynamic_truncate`
- `rag.parent.load`

如果开启了开发调试内容捕获，还应在对应 span 中看到 `tool.input`、`tool.output` 或 `rag.result.parents.debug`。如果只看到 Milvus gRPC span，而没有 `tool.*` / `rag.*` 业务 span，说明当前请求没有走商品 RAG 工具。

关闭或破坏 BM25 依赖后，再调用一次，确认主链路继续返回，且 RAG span 中能看到 BM25 降级属性。

## 数据边界

默认情况下，Langfuse 只记录用户、会话、模型、路由、工具名、输入输出长度、召回数量、耗时、父文档 `sourceId` 与 `title`。

默认情况下，Langfuse 不记录完整用户输入、完整 prompt、图片内容、商品正文、商城 token、Basic Auth 密码、MCP context secret。

开启 `LANGFUSE_CAPTURE_PROMPT`、`LANGFUSE_CAPTURE_TOOL_PAYLOAD` 或 `LANGFUSE_CAPTURE_RAG_CONTENT` 后，Langfuse 会记录对应的调试文本和 RAG 正文片段。该模式用于个人本地开发和评测优化，禁止用于共享环境或生产环境。
