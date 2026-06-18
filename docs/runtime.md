# 运行说明

本文档描述如何在本地把 `rag-agent` 跑起来。架构与模块职责见 [架构说明](architecture.md)。

## 目录

- [前置依赖](#前置依赖)
- [环境变量](#环境变量)
- [本地启动](#本地启动)
- [Docker 依赖](#docker-依赖)
- [常见问题](#常见问题)
- [健康检查](#健康检查)

## 前置依赖

- JDK 17
- Maven 3.x
- Docker Desktop（用于启动 Redis、Milvus 及监控）
- 可访问的 MySQL 实例：`rag-agent` 默认连接 `localhost:3307/rag_agent` 存储会话流水
- 独立的 `mall-mcp` 服务（MCP endpoint `http://localhost:8120/mcp`），商城侧 `mall-mysql` 由 `mall-mcp` 项目维护
- 有效的 `DASHSCOPE_API_KEY`（DashScope 兼容 OpenAI 协议）

## 环境变量

后端必填：

```powershell
$env:DASHSCOPE_API_KEY="<your-key>"
```

后端常用可选项（均有默认值）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `18082` | 后端监听端口 |
| `SPRING_MVC_ASYNC_REQUEST_TIMEOUT` | `180s` | 流式请求超时 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | 普通 Redis 7.x，无需 Redis Stack |
| `REDIS_PASSWORD` | 空 | Redis 密码 |
| `MILVUS_HOST` / `MILVUS_PORT` | `localhost` / `19530` | Milvus 2.5+ |
| `MYSQL_URL` | `jdbc:mysql://localhost:3307/rag_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true` | rag-agent 自身的会话流水库 |
| `MYSQL_USERNAME` / `MYSQL_PASSWORD` | `root` / `root` | 同上 |
| `MALL_BASE_URL` | `http://localhost:8100` | 商城网关地址（仅 token 透传用） |
| `MALL_MCP_BASE_URL` | `http://localhost:8120` | `mall-mcp` 服务地址 |
| `MCP_CONTEXT_SECRET` | `mall-mcp-dev-secret` | rag-agent ↔ mall-mcp 上下文接口共享密钥 |
| `SHOPPING_PREFERENCE_TTL` | `7d` | 短期偏好 Hash 当前状态和 List 最近变化的滑动 TTL |
| `MEMORY_SHORT_TERM_TTL` | `30d` | 短期记忆窗口 TTL |
| `INTENT_ROUTER_CONFIDENCE_THRESHOLD` | `0.7` | 快车道路由置信度阈值 |
| `QWEN_VL_ROUTER_MODEL` | `qwen3-vl-8b-instruct` | 意图路由模型 |
| `QWEN_CHAT_MODEL` | `qwen3.5-omni-plus-2026-03-15` | 主模型 |

RAG 召回参数：`RAG_DENSE_CHILD_TOP_K=24`、`RAG_BM25_CHILD_TOP_K=8`、`RAG_MAX_PARENT_RESULTS=6`。完整配置以 `src/main/resources/application.yml` 为准。

## 本地启动

后端启动前需确认 MySQL 可用：默认 `localhost:3307/rag_agent`，账号 `root/root`；非默认环境可设置 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。

终端 1：启动后端。

```powershell
docker compose up -d redis etcd minio milvus
$env:DASHSCOPE_API_KEY="<your-key>"
mvn spring-boot:run
```

终端 2：启动前端。

```powershell
cd frontend
npm install
npm run dev
```

访问 `http://localhost:4173`。本项目 Vite 开发服务器固定监听 `4173`；前端登录页默认连接 `http://localhost:18082`。后端唯一对话入口为 `POST /api/react`。

如果 Windows PowerShell 拦截 `npm.ps1`，使用 `npm.cmd install` 和 `npm.cmd run dev`。如果 `4173` 已被旧前端进程占用，先停止占用进程：

```powershell
$owners = Get-NetTCPConnection -LocalPort 4173 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
$owners | ForEach-Object { Stop-Process -Id $_ }
```

## Docker 依赖

`docker-compose.yml` 提供以下服务：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| `redis` | 6379 | 普通 Redis 7.4 |
| `etcd` / `minio` / `milvus` | 19530（Milvus gRPC）、9091（Milvus metrics）、9000/9001（MinIO） | Milvus standalone 套件 |
| `prometheus` | 9090 | 可选监控 |
| `grafana` | 3000 | 可选监控，默认 `admin/admin` |

按需启动监控：

```powershell
docker compose up -d prometheus grafana
```

`rag-agent` 自身的 MySQL、`mall-mcp` 服务和商城侧 `mall-mysql` 数据库不在本仓库 compose 文件中；`mall-mcp` 与商城库请按 `mall-mcp` 项目自身的运行说明启动。

## 常见问题

- **`DASHSCOPE_API_KEY` 未设置**：启动时 Spring AI 客户端报缺失 key，先 `echo $env:DASHSCOPE_API_KEY` 检查。
- **Milvus collection 不存在**：首次启动会按 `MILVUS_INITIALIZE_SCHEMA=true` 自动建集合，确认 `MILVUS_HOST/PORT` 可达且账号有建库权限。
- **MySQL 连接失败**：默认端口 `3307`（非 3306），如本机 MySQL 装在 3306，需用 `MYSQL_URL` 覆盖。
- **`mall_*` 工具不可用**：检查 `MALL_MCP_BASE_URL` 指向的 `mall-mcp` 服务和 `MCP_CONTEXT_SECRET` 是否与 `mall-mcp` 侧一致；`mall-mcp` 未启动时商城 B 类快车道会直接降级。
- **`mall_create_order` 被拒绝**：这是 Java 侧硬门禁，需要路由意图为 `CART_CONFIRMATION`、路由标记 `need_confirm=true`、本轮文本有明确下单语义，且参数包含有效 `confirmationId` 与 `userConfirmed=true`，详见 [architecture.md#mcp-边界](architecture.md#mcp-边界)。
- **前端启动提示 `EADDRINUSE: 4173`**：已有旧前端或 Vite 进程占用端口，按上方端口释放命令停止后重新执行 `npm run dev`。
- **流式请求中途断开**：调大 `SPRING_MVC_ASYNC_REQUEST_TIMEOUT`。

## 健康检查

```powershell
# Spring Boot Actuator
curl http://localhost:18082/actuator/health

# Redis
Test-NetConnection -ComputerName localhost -Port 6379

# Milvus gRPC
Test-NetConnection -ComputerName localhost -Port 19530

# mall-mcp
curl http://localhost:8120/mcp
```

测试命令见 [TESTING.md](../TESTING.md)。
