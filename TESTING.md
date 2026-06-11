# 测试计划

## 目录

- [1. 测试范围](#1-测试范围)
- [2. 前置条件](#2-前置条件)
- [3. 自动化测试](#3-自动化测试)
- [4. Langfuse RAG Tracing 回归](#4-langfuse-rag-tracing-回归)
- [5. 接口冒烟测试](#5-接口冒烟测试)
- [6. 购物车闭环测试](#6-购物车闭环测试)
- [7. 导购场景验收用例](#7-导购场景验收用例)
- [8. 前端手工测试](#8-前端手工测试)

## 1. 测试范围

当前测试围绕多模态电商导购 Agent 的主链路展开：

- ReAct 导购 Agent：模型选择、流式输出、工具调用、记忆装载、敏感信息脱敏恢复、模型连接中断降级。
- 商城商品链路：通过 Agent 工具完成商品搜索、商品详情、价格库存展示和相似款推荐。
- 购物车链路：通过 Agent 工具完成查看、加购、修改数量、移除商品。
- RAG 知识库：普通文档导入、结构化商品知识导入、父子块检索、dense + BM25 混合召回。
- 安全链路：Basic 登录、商城 token 透传、Prompt Injection 过滤。
- 前端导购工作台：Basic Auth 登录、真实会话列表、图文流式输出、模型选择、联网搜索开关和降级提示。

## 2. 前置条件

本地测试默认依赖以下服务：

- `rag-agent` 后端：`http://localhost:18082`，唯一对话入口 `POST /api/react`
- 普通 Redis 7.x：`localhost:6379`（无需 Redis Stack）
- Milvus 2.5+：`localhost:19530`
- MySQL：`localhost:3307`，rag-agent 自身的会话流水库
- `mall-mcp` 服务：`http://localhost:8120/mcp`，商城业务遵循当前仓库里的 `mall-mysql` 约定
- DashScope 兼容 OpenAI 接口：需要有效 `DASHSCOPE_API_KEY`

完整的环境变量、端口、Docker 依赖和健康检查命令见 [docs/runtime.md](docs/runtime.md)。最简启动：

后端启动前需准备 `localhost:3307/rag_agent`；非默认环境可设置 `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。

```powershell
docker compose up -d redis etcd minio milvus
$env:DASHSCOPE_API_KEY="你的 DashScope Key"
mvn spring-boot:run
```

## 3. 自动化测试

先跑编译：

```powershell
mvn -q -DskipTests compile
```

再跑核心单测：

```powershell
mvn -q -Dtest=ReActAgentTest test
mvn -q -Dtest=BuiltInToolsTest test
mvn -q -Dtest=ChatControllerTest test
mvn "-Dtest=ConversationLogServiceTest,ConversationControllerTest" test
Push-Location frontend
npm test
npm run build
Pop-Location
```

新增保守收敛覆盖：

- 快车道安全过滤：验证路由和简单任务收到的是已过滤、已掩码的用户输入。
- 订单创建门禁：验证 `mall_create_order` 在路由未放行、缺少 `confirmationId` 或 `userConfirmed=false` 时不会调用真实 MCP 工具。
- 工具日志脱敏：验证 info 日志不包含完整工具入参、工具返回值或敏感 token。
- 商城 MCP 快车道：验证 B 类简单任务只暴露限定的 `mall_*` MCP 工具，且不暴露 `mall_create_order`。

最后跑全量测试：

```powershell
mvn -q test
```

当前期望：全量测试通过。若失败，优先查看 `target/surefire-reports` 中对应测试类报告。

## 4. Langfuse RAG Tracing 回归

运行单元测试：

```powershell
mvn -Dtest=LangfusePropertiesTest,RagTracingTest,ParentChildHybridDocumentRetrieverTest,LoggingToolCallbackTest test
```

运行核心回归：

```powershell
mvn test
```

人工验证：

1. 按 [Langfuse 本地可观测环境](observability/langfuse/README.md) 启动 `observability/langfuse/docker-compose.yml`。
2. 使用 OpenTelemetry Java Agent 启动应用。
3. 导入至少 2 条商品知识。
4. 调用 `/api/react`，问题触发 `searchProductKnowledge`。
5. 在 Langfuse 中确认 `POST /api/react` trace 和全部 `rag.*` span，span 清单见 [验证 Trace](observability/langfuse/README.md#验证-trace)。
6. 确认 `rag.result.parents` 只有 `rank`、`sourceId`、`title`。

## 5. 接口冒烟测试

以下命令中的 `alice:密码` 需要替换成商城用户表中可登录的账号密码。

获取模型列表：

```powershell
curl -u alice:密码 "http://localhost:18082/api/models/chat"
```

文本 ReAct 对话：

```powershell
curl -u alice:密码 -F "message=你好" -F "modelId=qwen" -F "webSearchEnabled=false" http://localhost:18082/api/react
```

商品查询会话：

```powershell
curl -u alice:密码 -F "message=儿童积木套装 300 片要多少钱" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

期望：正常连通时回答包含真实价格；DashScope 或商城连接中断时返回中文降级提示。

图片上传会话：

```powershell
curl -u alice:密码 -F "message=帮我找相似款，预算 500 以内" -F "image=@shoe.png" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

## 6. 购物车闭环测试

购物车不再暴露独立 REST 接口，全部通过 Agent 入口验证：

加购：

```powershell
curl -u alice:密码 -F "message=把 SKU 3020 加入购物车，买 1 件" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

查看：

```powershell
curl -u alice:密码 -F "message=看一下我的购物车" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

修改数量：

```powershell
curl -u alice:密码 -F "message=把购物车里 SKU 3020 的数量改成 2 件" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

移除：

```powershell
curl -u alice:密码 -F "message=从购物车移除 SKU 3020" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

## 7. 导购场景验收用例

优先验证这些自然语言问题：

- `儿童积木套装 300 片要多少钱`
- `推荐一个不锈钢保温杯`
- `帮我找 SKU 3020 的相似款`
- `把儿童积木套装 300 片加入购物车，买 1 件`
- `看一下我的购物车`
- `我想买儿童玩具，预算 200 以内`

验收重点：

- 推荐商品前应调用商品搜索或知识库检索工具。
- 价格、库存、SKU 不应由模型编造。
- 加购前应能解析真实 SKU，并校验库存。
- 同一 `sessionId` 下购物车状态应保持一致。
- 商城服务不可用时应返回降级提示。
- DashScope 连接中断时不应抛出空白响应，应返回中文降级提示。

## 8. 前端手工测试

启动前端：

```powershell
cd frontend
npm install
npm run dev
```

访问：

```text
http://localhost:4173
```

手工检查：

- Basic Auth 登录成功和失败提示。
- 真实会话列表、新建、切换、删除。
- 文本和图片请求流式输出。
- 模型选择和联网搜索参数。
- 流式期间会话操作禁用。
- 停止生成后保留已收到文本。
