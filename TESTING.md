# 测试计划

## 1. 测试范围

当前测试围绕多模态电商导购 Agent 的主链路展开：

- ReAct 导购 Agent：模型选择、流式输出、工具调用、记忆装载、敏感信息脱敏恢复、模型连接中断降级。
- 商城商品链路：通过 Agent 工具完成商品搜索、商品详情、价格库存展示和相似款推荐。
- 购物车链路：通过 Agent 工具完成查看、加购、修改数量、移除商品。
- RAG 知识库：普通文档导入、结构化商品知识导入、父子块检索、dense + BM25 混合召回。
- 安全链路：Basic 登录、商城 token 透传、Prompt Injection 过滤。
- 前端导购工作台：聊天、商品卡片、图片上传、对比视图、购物车抽屉和降级提示。

## 2. 前置条件

本地测试默认依赖以下服务：

- `rag-agent` 后端：`http://localhost:18082`
- Redis Stack：`localhost:6379`
- 商城网关或商城服务入口：`http://localhost:8100`
- DashScope 兼容 OpenAI 接口：需要有效 `DASHSCOPE_API_KEY`

启动 Redis Stack：

```powershell
docker compose up -d redis-stack
Test-NetConnection -ComputerName localhost -Port 6379
```

启动后端前建议设置：

```powershell
$env:DASHSCOPE_API_KEY="你的 DashScope Key"
$env:MALL_BASE_URL="http://localhost:8100"
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

## 4. 接口冒烟测试

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
curl -u alice:密码 -F "message=儿童积木套装 300片要多少钱" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

期望：正常连通时回答包含真实价格；DashScope 或商城连接中断时返回中文降级提示。

图片上传会话：

```powershell
curl -u alice:密码 -F "message=帮我找相似款，预算 500 以内" -F "image=@shoe.png" -F "sessionId=test-001" -F "modelId=qwen" http://localhost:18082/api/react
```

## 5. 购物车闭环测试

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

## 6. 导购场景验收用例

优先验证这些自然语言问题：

- `儿童积木套装 300片要多少钱`
- `推荐一个不锈钢保温杯`
- `帮我找 SKU 3020 的相似款`
- `把儿童积木套装 300片加入购物车，买 1 件`
- `看一下我的购物车`
- `我想买儿童玩具，预算 200 以内`

验收重点：

- 推荐商品前应调用商品搜索或知识库检索工具。
- 价格、库存、SKU 不应由模型编造。
- 加购前应能解析真实 SKU，并校验库存。
- 同一 `sessionId` 下购物车状态应保持一致。
- 商城服务不可用时应返回降级提示。
- DashScope 连接中断时不应抛出空白响应，应返回中文降级提示。

## 7. 前端手工测试

启动前端：

```powershell
cd frontend
node server.js 4173
```

访问：

```text
http://localhost:4173
```

手工检查：

- 登录后能进入导购工作台。
- 文本导购能触发流式回答。
- 图片上传或图片 URL 能进入后端 `POST /api/react`。
- 商品卡片不直连商城 REST，导入知识后可用于追问、对比和向 Agent 发起加购请求。
- 购物车相关按钮通过 Agent 对话完成查看、加购、改数量和删除。
- 商城服务或模型服务异常时，前端展示降级提示而不是卡死。
