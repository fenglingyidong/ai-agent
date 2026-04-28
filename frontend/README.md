# Frontend

这个目录是给当前 Spring Boot RAG 项目单独做的静态前端，不依赖额外打包工具。

## 功能

- 连接受 Spring Security Basic Auth 保护的后端接口
- 调用 `/api/chat` 发起带 `sessionId` 的 RAG 对话
- 调用 `/api/react` 演示 ReAct Agent
- 调用 `/api/rag/documents/import` 导入原始文档
- 把连接设置、聊天记录、导入记录保存在浏览器本地存储

## 启动方式

1. 在项目根目录启动后端：

```powershell
mvn spring-boot:run
```

2. 另开一个终端，进入前端目录并启动静态服务器：

```powershell
cd frontend
node server.js 4173
```

3. 打开浏览器访问：

```text
http://localhost:4173
```

默认连接地址是 `http://localhost:8080`，默认账号是 `demo / demo123`。

## 说明

- 这是一个零依赖静态页面，核心文件是 `index.html`、`styles.css`、`app.js` 和一个本地启动用的 `server.js`
- 后端已补充跨域配置，允许本地独立前端目录直接访问 API
- 如果你想后续升级成 Vue 或 React，可以直接把这个目录作为原型继续演进
