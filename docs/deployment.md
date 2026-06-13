# 本地完整部署教程

本文档用于在 Windows 本机完整启动电商后端、`mall-mcp`、`rag-agent`、Vue3 前端和可选 Langfuse。本教程面向本地联调与演示，不是生产部署手册；生产环境还需要补充反向代理、HTTPS、密钥托管、数据备份、日志采集和访问控制。

## 目录

- [部署拓扑](#部署拓扑)
- [前置准备](#前置准备)
- [数据库脚本](#数据库脚本)
- [启动商城后端](#启动商城后端)
- [启动 RAGAgent](#启动-ragagent)
- [启动前端](#启动前端)
- [可选：启动 Langfuse](#可选启动-langfuse)
- [健康检查](#健康检查)
- [常见问题](#常见问题)

## 部署拓扑

| 模块 | 默认地址 | 说明 |
| --- | --- | --- |
| 商城网关 | `http://localhost:8100` | `mallbackend` 的 `mall-gateway` |
| 商城 MCP | `http://localhost:8120/mcp` | `mallbackend` 的 `mall-mcp` |
| RAGAgent 后端 | `http://localhost:18082` | 本仓库 Spring Boot 应用 |
| RAGAgent 前端 | `http://localhost:4173` | 本仓库 Vue3 / Vite 应用 |
| MySQL | `localhost:3307` | `mall` 与 `rag_agent` 两个库 |
| RAGAgent Redis | `localhost:6379` | 本仓库 `docker-compose.yml` |
| 商城 Redis | `localhost:6380` | `mallbackend/docker-compose.yml` |
| Milvus | `localhost:19530` | 本仓库 `docker-compose.yml` |
| Langfuse | `http://localhost:3001` | 可选观测环境 |

## 前置准备

- JDK 17
- Maven 3.x
- Node.js 与 npm
- Docker Desktop
- DashScope API Key：`DASHSCOPE_API_KEY`
- 商城项目路径：`D:\mycodes\mallbackend`
- 本项目路径：`D:\mycodes\RAGAgent`

PowerShell 中建议使用 `mvn.cmd`、`npm.cmd`，避免脚本执行策略拦截。

## 数据库脚本

本仓库 `scripts/` 下保存了当前联调数据库脚本：

| 文件 | 数据库 | 用途 |
| --- | --- | --- |
| `scripts/mall.sql` | `mall` | 商城商品、用户、购物车、订单等演示数据 |
| `scripts/rag_agent.sql` | `rag_agent` | RAGAgent 会话流水、RAG 父文档缓存等数据 |

导入会先按脚本中的 `DROP TABLE IF EXISTS` 重建表，请只在本地演示库或可重建环境中执行。导入前确认 `mall-mysql` 已启动并映射到 `localhost:3307`。

```powershell
cd D:\mycodes\RAGAgent

docker exec -i mall-mysql mysql --default-character-set=utf8mb4 -uroot -proot -e "CREATE DATABASE IF NOT EXISTS mall DEFAULT CHARACTER SET utf8mb4;"
cmd /c "docker exec -i mall-mysql mysql --default-character-set=utf8mb4 -uroot -proot mall < scripts\mall.sql"

docker exec -i mall-mysql mysql --default-character-set=utf8mb4 -uroot -proot -e "CREATE DATABASE IF NOT EXISTS rag_agent DEFAULT CHARACTER SET utf8mb4;"
cmd /c "docker exec -i mall-mysql mysql --default-character-set=utf8mb4 -uroot -proot rag_agent < scripts\rag_agent.sql"
```

这里用 `cmd /c` 执行 SQL 文件输入重定向，是为了避开 Windows PowerShell 对 `<` 的限制，同时避免 `Get-Content | docker exec` 造成中文编码损坏。

## 启动商城后端

终端 1：启动商城基础设施。

```powershell
cd D:\mycodes\mallbackend
docker compose up -d nginx mysql redis rabbitmq nacos sentinel seata canal
```

如果首次启动后需要覆盖成当前数据库快照，按 [数据库脚本](#数据库脚本) 导入 `mall.sql` 和 `rag_agent.sql`。

终端 2：编译商城业务服务。

```powershell
cd D:\mycodes\mallbackend
mvn.cmd clean package -DskipTests
```

随后用 IDE Run Config 或多个独立终端启动业务服务。建议先启动基础服务，再启动网关；每条 `java -jar` 命令都会占用当前终端。

```powershell
java -jar mall-auth\target\mall-auth-0.0.1-SNAPSHOT.jar
java -jar mall-product\target\mall-product-0.0.1-SNAPSHOT.jar
java -jar mall-review\target\mall-review-0.0.1-SNAPSHOT.jar
java -jar mall-coupon\target\mall-coupon-0.0.1-SNAPSHOT.jar
java -jar mall-cart\target\mall-cart-0.0.1-SNAPSHOT.jar
java -jar mall-order\target\mall-order-0.0.1-SNAPSHOT.jar
java -jar mall-seckill\target\mall-seckill-0.0.1-SNAPSHOT.jar
java -jar mall-gateway\target\mall-gateway-0.0.1-SNAPSHOT.jar
```

终端 3：启动 `mall-mcp`。

```powershell
cd D:\mycodes\mallbackend
mvn.cmd -pl mall-mcp spring-boot:run
```

## 启动 RAGAgent

终端 4：启动 RAGAgent 依赖。

```powershell
cd D:\mycodes\RAGAgent
docker compose up -d redis etcd minio milvus
```

终端 5：启动 RAGAgent 后端。

```powershell
cd D:\mycodes\RAGAgent
$env:DASHSCOPE_API_KEY="<your-key>"
$env:MYSQL_URL="jdbc:mysql://localhost:3307/rag_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&createDatabaseIfNotExist=true"
$env:MYSQL_USERNAME="root"
$env:MYSQL_PASSWORD="root"
$env:MALL_BASE_URL="http://localhost:8100"
$env:MALL_MCP_BASE_URL="http://localhost:8120"
$env:MCP_CONTEXT_SECRET="mall-mcp-dev-secret"
mvn.cmd spring-boot:run
```

合并或拉取新代码后，必须重启 RAGAgent 后端；否则前端可能会调用到旧进程，出现新接口 404，例如 `GET /api/conversations`。

## 启动前端

终端 6：启动 Vue3 前端。

```powershell
cd D:\mycodes\RAGAgent\frontend
npm.cmd install
npm.cmd run dev
```

访问 `http://localhost:4173`。登录页默认后端地址为 `http://localhost:18082`。

## 可选：启动 Langfuse

Langfuse 只用于本地观测，默认 secret 不适合共享环境或生产环境。

```powershell
cd D:\mycodes\RAGAgent
docker compose -f observability/langfuse/docker-compose.yml up -d
```

打开 `http://localhost:3001` 初始化项目，并在项目设置中生成 public key 和 secret key。

```powershell
$pair = "pk-lf-xxx:sk-lf-xxx"
$auth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))

$env:LANGFUSE_ENABLED="true"
$env:LANGFUSE_BASE_URL="http://localhost:3001"
$env:OTEL_SERVICE_NAME="rag-agent"
$env:OTEL_TRACES_EXPORTER="otlp"
$env:OTEL_METRICS_EXPORTER="none"
$env:OTEL_LOGS_EXPORTER="none"
$env:OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
$env:OTEL_EXPORTER_OTLP_ENDPOINT="http://localhost:3001/api/public/otel"
$env:OTEL_EXPORTER_OTLP_HEADERS="Authorization=Basic $auth,x-langfuse-ingestion-version=4"

mvn.cmd spring-boot:run "-Dspring-boot.run.jvmArguments=-javaagent:D:/tools/opentelemetry-javaagent.jar"
```

## 健康检查

```powershell
# 商城网关
curl.exe http://localhost:8100/api/product/1001

# mall-mcp
curl.exe http://localhost:8120/mcp

# RAGAgent
curl.exe http://localhost:18082/actuator/health
curl.exe -u alice:demo123 "http://localhost:18082/api/models/chat"
curl.exe -u alice:demo123 "http://localhost:18082/api/conversations?limit=50"

# 前端
curl.exe http://localhost:4173
```

浏览器联调账号可使用 `alice / demo123`。

## 常见问题

- **`GET /api/conversations` 返回 404**：RAGAgent 后端进程是旧版本。停止占用 `18082` 的 Java 进程后重新执行 `mvn.cmd spring-boot:run`。
- **`4173` 端口占用**：停止旧前端或 Vite 进程。

```powershell
$owners = Get-NetTCPConnection -LocalPort 4173 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique
$owners | ForEach-Object { Stop-Process -Id $_ }
```

- **RAGAgent Redis 容器名冲突**：已有 `rag-agent-redis` 容器在运行。若是同一项目旧容器，可执行 `docker compose up -d redis` 复用；若要重建，先确认数据可丢弃，再执行 `docker rm -f rag-agent-redis`。
- **MySQL 连接失败**：确认 `mall-mysql` 在运行，且 `3307` 映射正常。
- **商城工具不可用**：确认 `mall-gateway` 在 `8100`，`mall-mcp` 在 `8120`，并且 `MCP_CONTEXT_SECRET` 两边一致。
- **Langfuse 页面打不开**：确认 `langfuse-langfuse-web-1` 已启动，且 `observability/langfuse/docker-compose.yml` 中 Web 服务监听 `0.0.0.0`。
