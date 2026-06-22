# 工具调用上下文窗口设计

## 目录

- [目标](#目标)
- [现状](#现状)
- [决策](#决策)
- [数据结构](#数据结构)
- [数据流](#数据流)
- [上下文渲染](#上下文渲染)
- [错误与安全](#错误与安全)
- [测试计划](#测试计划)
- [验收标准](#验收标准)

## 目标

本设计让对话过程中的工具调用结果进入 `SimpleTaskAgent` 和主 `ReActAgent` 的后续上下文。工具调用采用会话级统一时间线计数，RAG 检索也视为一次工具调用；不按工具名分别保留。

上下文窗口只保留最新 3 次工具调用的完整输入输出。更早的工具调用保留轻量折叠占位符，用于告诉模型“这里曾经调用过某个工具，但完整结果已被折叠”，避免旧结果挤占上下文，同时不破坏模型对工具调用历史的推理。

## 现状

`SimpleTaskAgent` 当前会拼入短期对话上下文、导购偏好和路由信息，但工具结果主要停留在 Spring AI 工具执行链内部。主 `ReActAgent` 依赖 `MessageChatMemoryAdvisor`、`ConversationMemoryService` 和 `trustedContext` 传递上下文，也没有稳定记录每次工具调用结果。

`LoggingToolCallback` 已经统一包裹主链路和简单商城任务的工具回调，并采集工具输入、输出和 tracing 信息。`BuiltInTools.searchProductKnowledge` 是 RAG 工具入口，RAG 内部还可能调用 `mall_get_product_detail` 做实时详情补强。

## 决策

推荐采用新增会话级工具调用记录器的方案。记录入口复用现有工具包装层，尽量少改调用方：

- `LoggingToolCallback` 在工具执行成功或失败后追加一条工具调用记录。
- `BuiltInTools.searchProductKnowledge` 通过工具回调执行时由 `LoggingToolCallback` 记录，因此 RAG 被计为工具调用。
- RAG 内部 `mall_get_product_detail` 补强也应走带记录能力的回调包装，作为独立工具调用进入同一时间线。
- `ConversationMemoryService.recentConversationContext(...)` 渲染短期对话上下文时追加工具调用历史片段。
- `ShoppingRouteExecutor`、`SimpleTaskAgent` 和主 `ReActAgent` 复用该上下文片段，不各自维护一套规则。

不采用“把工具结果塞进最终助手回答”的方案，因为模型输出不稳定且无法保证完整结果进入下一轮。不采用“把每次工具调用直接写成普通聊天消息”的方案，因为会污染用户/助手消息窗口，并使折叠规则难以控制。

## 数据结构

新增会话级记录对象，建议字段如下：

- `toolName`：工具名，例如 `mall_get_product_detail`、`searchProductKnowledge`。
- `input`：工具输入，按安全规则裁剪后保存。
- `output`：工具输出，按安全规则裁剪后保存。
- `status`：`OK` 或 `ERROR`。
- `errorType`：失败时记录异常类型，成功时为空。

存储位置优先复用短期记忆组件相同的会话键：`userId::sessionId`。实现上可在 `ConversationMemoryService` 内维护工具调用窗口接口，也可拆出 `ConversationToolCallMemoryService`，但对外应只暴露追加记录和渲染上下文两个简单方法。

## 数据流

一次工具调用完成后的流转如下：

1. Agent 准备本轮工具上下文，包含 `userId` 和 `sessionId`。
2. 工具由 `LoggingToolCallback` 执行。
3. 回调从 `ToolContext` 或构造参数解析会话身份。
4. 回调把工具名、输入、输出、状态追加到会话工具调用记录器。
5. 下一轮路由、简单任务或主链路构建 prompt 时，通过 `ConversationMemoryService.recentConversationContext(...)` 读取最近对话和工具调用历史。

RAG 的处理规则如下：

- 模型显式调用 `searchProductKnowledge` 时，记录一条 `searchProductKnowledge`。
- `searchProductKnowledge` 内部进行商城实时详情补强时，每次 `mall_get_product_detail` 也记录一条独立工具调用。
- 所有记录按会话内追加顺序进入同一时间线，因此 5 次任意工具调用只保留最后 3 次完整结果。

## 上下文渲染

工具历史片段放在最近对话上下文之后，标题固定为：

```text
最近工具调用上下文：
```

渲染规则：

- 统一按会话内追加顺序展示，便于模型理解先后关系。
- 若会话中工具调用总数不超过 3 条，全部展示完整输入输出。
- 若超过 3 条，旧条目只展示折叠占位符，最新 3 条展示完整输入输出。
- 折叠占位符必须说明工具名，并要求模型如需精确事实则重新调用工具。

示例：

```text
[工具调用 1] 已调用 mall_search_products，完整结果已折叠；如需精确事实请重新调用工具。
[工具调用 2] 已调用 mall_get_product_detail，完整结果已折叠；如需精确事实请重新调用工具。
[工具调用 3] mall_get_product_detail
输入：{"skuId":4056}
结果：婴儿湿巾 80抽 12包 SKU 4056，价格 99.00 元，库存 300 件。
```

工具输出会做单条长度上限控制，避免某次 RAG 结果过长。超过上限时在该条内部追加“内容已截断”说明；这不影响最新 3 条的完整保留语义，因为完整保留指保留该条可进入 prompt 的安全文本。

## 错误与安全

工具失败也记录一条工具调用，状态为 `ERROR`，内容只保留工具名、输入摘要、错误类型和通用失败说明。不要把 token、密码、Authorization、商城登录凭据或原始异常堆栈写入工具上下文。

记录器不可用时，工具调用仍按原逻辑执行，系统只损失后续上下文增强能力。工具结果记录失败不能影响用户本轮回答。

旧工具结果折叠后，模型不能把折叠结果当作当前事实来源。prompt 中应明确：精确价格、库存、购物车和订单状态仍以最新工具调用为准；若只看到折叠占位符，需要重新调用工具。

## 测试计划

先补单元测试，再实现生产代码：

- `ConversationMemoryServiceTest`：同一会话追加 5 次工具调用，渲染上下文时前 2 条为折叠占位符，后 3 条保留输入输出。
- `LoggingToolCallbackTest`：成功和失败工具调用都会写入记录，失败记录不暴露敏感值。
- `BuiltInToolsTest` 或集成测试：`searchProductKnowledge` 和 RAG 内部 `mall_get_product_detail` 进入同一时间线。
- `SimpleTaskAgentTest`：简单任务 prompt 包含最近工具调用上下文。
- `ReactStreamExecutorTest` 或 `ReActAgentTest`：主链路 prompt 或 trusted context 包含同一工具调用上下文。

## 验收标准

- 本轮或历史对话中的所有工具调用结果都可进入后续 `SimpleTaskAgent` 和主 `ReActAgent` 上下文。
- RAG 检索按工具调用计数，不单独分桶。
- 会话工具调用历史超过 3 条时，仅最新 3 条保留完整输入输出，旧条目替换为折叠占位符。
- 同一条时间线不按工具名分别计算；连续 5 次 `mall_get_product_detail` 时，前 2 次折叠，后 3 次完整保留。
- 工具记录失败不影响工具执行和本轮回答。
- 上下文不泄露认证 token、密码或原始异常堆栈。
