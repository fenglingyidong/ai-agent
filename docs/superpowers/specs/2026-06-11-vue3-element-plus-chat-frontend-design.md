# Vue3 + Element Plus 导购聊天前端重构设计

## 目录

- [背景](#背景)
- [目标](#目标)
- [非目标](#非目标)
- [已确认决策](#已确认决策)
- [推荐方案](#推荐方案)
- [总体架构](#总体架构)
- [前端结构](#前端结构)
- [后端接口](#后端接口)
- [数据流](#数据流)
- [错误处理与边界](#错误处理与边界)
- [测试验收](#测试验收)
- [风险与约束](#风险与约束)

## 背景

当前 `frontend/` 是零依赖原生 HTML/CSS/JavaScript 工作台，已经能通过 Spring Security Basic Auth 调用 `POST /api/react`，并支持流式读取文本、图片上传、图片 URL、模型选择和联网搜索参数。

后端已有：

- `POST /api/react`：图文导购流式对话入口。
- `GET /api/models/chat`：模型列表。
- `GET /api/conversations/{sessionId}/turns`：按会话读取最近对话轮次。
- `DELETE /api/conversations/{sessionId}`：删除指定会话。
- `conversation_sessions` / `conversation_turns`：MySQL 原文会话流水。

缺口是前端仍是演示型页面，缺少正式登录页和真实会话列表；后端也缺少“列出当前用户所有会话”的接口。

## 目标

- 将 `frontend/` 原地升级为 Vite + Vue3 + Element Plus SPA。
- 支持用户登录，认证方式沿用当前 Basic Auth。
- 支持真实会话列表、创建新会话、切换会话、删除会话。
- 支持 `/api/react` 的流式输出，保留图片上传、图片 URL、模型选择和联网搜索开关。
- 后端补齐会话列表接口，并在首次提问时生成会话标题。
- 保持实现简洁，遵循现有后端接口和会话存储模型。

## 非目标

- 不迁移旧前端中的商品卡片、商品对比、购物车抽屉和商品知识快速导入面板。
- 不替换后端认证体系，不新增 JWT 或独立 token 登录。
- 不修改 `/api/react` 的 multipart 请求协议。
- 不重做短期记忆、长期记忆、RAG 检索或 MCP 商城工具链路。
- 不删除后端已有导购、RAG、商城工具接口。

## 已确认决策

- 会话切换采用真实后端会话列表，而不是只维护前端本地 `sessionId`。
- 登录沿用 Basic Auth，由 Vue 前端统一附带 `Authorization` 请求头。
- 首版只保留核心导购功能：登录、会话列表/切换、流式图文聊天、模型选择、联网搜索开关。
- 会话标题由后端自动取首条用户问题截断生成。
- 前端布局采用“会话侧栏 + 聊天主区”。
- 实施路径采用方案 1：直接把 `frontend/` 改成 Vite + Vue3 + Element Plus SPA。

## 推荐方案

采用原地重构：

```text
frontend/
  package.json
  vite.config.js
  index.html
  src/
    main.js
    App.vue
    styles.css
    api/
      http.js
      chat.js
    stores/
      appStore.js
    components/
      LoginView.vue
      ChatWorkbench.vue
      SessionSidebar.vue
      ChatHeader.vue
      MessageList.vue
      ChatComposer.vue
```

这个方案让启动入口、文档和维护路径保持单一。旧的原生 `app.js` / `styles.css` / `server.js` 会由 Vite 工程替代，避免长期并存两套前端。

## 总体架构

前端是一个单页应用：

- 未登录时显示 `LoginView`。
- 登录成功后显示 `ChatWorkbench`。
- 所有 API 调用经 `api/http.js` 注入 `apiBase` 和 Basic Auth。
- 工作台左侧显示当前用户、新会话按钮、真实会话列表和删除操作。
- 工作台右侧顶部显示模型选择、联网搜索开关和退出。
- 中间显示消息流，底部显示图文输入区。

后端在现有 `ConversationController` 和 `ConversationLogService` 上增量扩展：

- 新增 `GET /api/conversations`，返回当前登录用户的最近会话列表。
- `ConversationLogService.beginTurn(...)` 首次创建会话时，若标题为空，则用首条用户问题生成标题。
- 已有按会话读取 turns 和删除会话接口继续复用。

## 前端结构

### App.vue

负责登录态分流。初始化时从本地存储恢复 `apiBase`、用户名、密码和模型设置；如果凭据存在，尝试加载模型和会话列表，成功则进入工作台，失败则回到登录页。

### LoginView.vue

包含 `apiBase`、用户名和密码输入。提交后调用 `GET /api/models/chat` 验证认证信息。成功后持久化登录态；失败时展示错误提示。

### ChatWorkbench.vue

承载主布局，组合侧栏、头部、消息列表和输入区。负责协调当前会话、新会话、加载历史、发送消息、停止请求和删除会话。

### SessionSidebar.vue

展示会话标题、更新时间和最近文本摘要。支持新会话、切换会话和删除会话。删除操作使用 Element Plus 确认框。

### ChatHeader.vue

展示模型选择、联网搜索开关和退出按钮。模型列表来自 `GET /api/models/chat`；联网搜索状态随请求提交。

### MessageList.vue

展示用户和助手消息。用户消息支持图片缩略图；助手消息支持流式 pending 状态、失败状态和停止状态。消息内容以纯文本展示，保留换行，不渲染 HTML。

### ChatComposer.vue

支持文本输入、最多 4 张图片上传、一个图片 URL、发送和停止。图片 URL 只允许 `http` 或 `https`。发送时构造 multipart 表单，字段与现有 `/api/react` 保持一致。

### appStore.js

使用 Vue 组合式状态管理，不引入 Pinia。状态包含：

- `auth`：`apiBase`、`username`、`password`。
- `models`：默认模型、模型列表、当前模型。
- `sessions`：会话列表、当前 `sessionId`。
- `messages`：当前会话消息。
- `ui`：加载状态、流式请求状态、错误信息、联网搜索开关。

## 后端接口

### GET /api/conversations

返回当前登录用户最近会话列表。

请求参数：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `limit` | `50` | 返回数量，后端限制在 `1..100`。 |

响应结构：

```json
{
  "items": [
    {
      "sessionId": "shopping-abcd1234",
      "title": "帮我推荐一双500元以内的通勤慢跑鞋",
      "createdAt": 1781140000000,
      "updatedAt": 1781140300000,
      "turnCount": 3,
      "latestUserText": "再对比一下黑色和灰色",
      "latestAssistantText": "黑色更耐脏，灰色更轻便..."
    }
  ]
}
```

查询按 `conversation_sessions.updated_at DESC` 排序，仅返回当前认证用户的数据。

### 会话标题生成

`ConversationLogService.beginTurn(...)` 调用 `upsertSession` 后，如果当前会话标题为空，则生成标题：

- 文本消息：去掉连续空白和换行，截断到 40 个字符。
- 图片-only 消息：使用 `图片导购咨询`。
- 已存在标题时不覆盖。

### 既有接口复用

- `GET /api/conversations/{sessionId}/turns?limit=50` 用于切换会话时加载历史。
- `DELETE /api/conversations/{sessionId}` 用于删除会话。
- `POST /api/react` 继续承担流式对话。

## 数据流

### 登录

1. 用户输入 `apiBase`、用户名和密码。
2. 前端调用 `GET /api/models/chat`。
3. 成功后保存认证信息，加载模型列表和会话列表。
4. 失败时停留登录页并展示错误。

### 新会话

1. 前端生成 `shopping-xxxxxxxx` 作为当前 `sessionId`。
2. 消息列表清空。
3. 用户发送第一条消息前，不向后端创建空会话。
4. 首条消息发送后，后端创建会话并生成标题。
5. 响应完成后前端刷新会话列表。

### 切换会话

1. 用户点击侧栏会话。
2. 前端调用 `GET /api/conversations/{sessionId}/turns?limit=50`。
3. 每个 turn 转换为用户消息和助手消息。
4. `PROCESSING`、`PARTIAL`、`FAILED` 状态映射为对应 UI 状态。

### 发送消息

1. 前端校验文本、图片或图片 URL 至少存在一项。
2. 前端追加用户消息和空助手消息。
3. multipart 调用 `POST /api/react`，携带 `message`、`sessionId`、`modelId`、`webSearchEnabled`、`image`、`imageUrl`。
4. 通过 `response.body.getReader()` 按 UTF-8 chunk 追加助手消息内容。
5. 完成后标记助手消息完成，刷新会话列表。

### 停止请求

1. 前端调用 `AbortController.abort()`。
2. 当前助手消息保留已收到内容，并标记为已停止。
3. 下一次加载历史时，以后端数据库内容为准。

### 删除会话

1. 前端调用 `DELETE /api/conversations/{sessionId}`。
2. 成功后刷新会话列表。
3. 若删除的是当前会话，则切换到最新会话；没有会话时创建空白新会话。

## 错误处理与边界

- `401` / `403`：提示账号或密码无效，清理登录态并回到登录页。
- 网络不可达：提示检查后端地址和服务状态。
- 流式非 2xx 响应：当前助手消息显示错误文本，发送按钮恢复可用。
- 流式中断：保留已收到文本，消息标记为已停止或失败。
- 空会话列表：显示空状态和新会话入口。
- 图片数量：前端限制最多 4 张，与后端一致。
- 图片 URL：前端只允许 `http` / `https`，后端继续保留校验。
- 消息内容：按纯文本显示，避免把模型输出作为 HTML 注入页面。
- Basic Auth 凭据：当前原型继续保存在浏览器本地存储；退出登录时清除。

## 测试验收

### 后端自动化测试

- `ConversationControllerTest` 覆盖 `GET /api/conversations` 使用当前认证用户。
- 覆盖会话列表按更新时间倒序返回。
- 覆盖 `limit` 参数上下限。
- `ConversationLogServiceTest` 覆盖首次提问生成标题。
- 覆盖已有标题不被覆盖。
- 覆盖图片-only 默认标题。

### 前端自动化测试

若引入前端测试依赖，使用 Vitest 覆盖：

- turns 转 messages。
- 流式 chunk 追加到助手消息。
- 删除当前会话后的切换逻辑。
- Basic Auth 请求头生成。

若保持依赖极简，至少以 `npm run build` 作为前端构建验证。

### 人工验收

- 启动后端和 Vue 前端。
- 使用 `alice / demo123` 登录。
- 发送文本消息，助手响应能逐字或分块流式显示。
- 上传图片或填写图片 URL 后能发送图文请求。
- 新建两个会话后，左侧列表显示标题并可切换历史。
- 删除当前会话后 UI 不报错，并切换到最新会话或空白新会话。
- 模型选择和联网搜索开关随 `/api/react` 请求提交。

## 风险与约束

- Basic Auth 凭据保存在浏览器本地存储，只适合当前本地原型和受控环境；正式部署应改为更安全的会话机制。
- 前端删除旧演示面板后，商品卡片、对比和购物车不再在 Vue 首版暴露，但后端能力仍保留。
- 后端会话列表依赖 MySQL 会话流水开启；如果 `conversation.log.enabled=false`，列表和历史会话为空。
- 流式请求被浏览器主动中断时，后端记录可能是 partial 或 failed，前端重新加载历史时以后端状态为准。
