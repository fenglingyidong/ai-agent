# 电商导购工作台前端

这个目录是零依赖静态前端，用于联调多模态电商智能导购 Agent。

## 功能

- 连接 Spring Security Basic Auth 保护的后端接口
- 前端不直接管理商城 Token，后端通过商城适配层代登录并缓存商城 Token
- 调用 `POST /api/react` 发起 multipart 图文导购会话
- 支持上传商品图和填写图片 URL
- 展示导购聊天区、商品卡片、对比视图和购物车抽屉
- 商品卡片加购、数量调整和移除不再直连商城 REST，统一转成自然语言请求交给 Agent 工具处理
- 调用 `/api/rag/documents/products/import` 快速导入结构化商品知识
- 从 `/api/models/chat` 动态加载可选模型
- 把连接设置、聊天记录、商品卡片、对比项和购物车保存在浏览器本地存储

## 启动方式

1. 在项目根目录启动后端：

```powershell
mvn spring-boot:run
```

2. 另开终端启动前端静态服务器：

```powershell
cd frontend
node server.js 4173
```

3. 访问：

```text
http://localhost:4173
```

默认连接地址是 `http://localhost:18082`，默认账号是 `alice / demo123`。

## 当前约束

- 商品卡片和对比视图仍是前端工作台状态，用于演示导购操作台体验。
- 购物车状态以后端 Agent 工具回复为准，前端不再按 Session ID 直接拉取商城 REST。
- 图片上传已走 Spring AI `Media` 后端链路，但视觉识别效果取决于当前所选模型是否支持图片输入。
