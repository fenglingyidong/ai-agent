# 电商智能导购前端

## 目录

- [能力](#能力)
- [启动](#启动)
- [验证](#验证)
- [约束](#约束)

## 能力

- Vue3 + Element Plus 导购聊天工作台。
- Spring Security Basic Auth 登录。
- 当前用户真实会话列表、切换和删除。
- `/api/react` 图文流式输出。
- 模型选择和联网搜索开关。

## 启动

```powershell
npm install
npm run dev
```

访问 `http://localhost:4173`，默认后端地址为 `http://localhost:18082`。

## 验证

```powershell
npm test
npm run build
```

## 约束

- 首版不展示旧工作台中的商品卡片、对比、购物车和知识导入面板。
- Basic Auth 凭据保存在浏览器本地存储，仅适合本地原型和受控环境。
- 历史会话只恢复文本，不恢复已上传图片。
