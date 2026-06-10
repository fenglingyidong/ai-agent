# 短期记忆 Redis 操作优化设计

## 目录

- [背景](#背景)
- [目标](#目标)
- [非目标](#非目标)
- [现状](#现状)
- [推荐方案](#推荐方案)
- [数据流](#数据流)
- [兼容与风险](#兼容与风险)
- [测试验收](#测试验收)

## 背景

`RedisChatMemoryRepository` 是 Spring AI `MessageChatMemoryAdvisor` 的短期记忆仓库实现。接入 Langfuse/OpenTelemetry 后，单次 `/api/react` 请求会展示多条 Redis span。排查结果显示这些 span 主要来自真实业务 Redis 命令，而非 Langfuse 额外写入。

当前问题不是功能错误，而是短期记忆仓库在常规读写路径上执行了过多 Redis 操作，增加 trace 噪声和 Redis RTT。

## 目标

- 降低常规对话轮次中的 Redis 命令数量。
- 保持 `ChatMemoryRepository` 对外语义不变。
- 保留长期摘要触发能力。
- 保持现有 key 结构和已有 Redis 数据兼容。

## 非目标

- 不替换 Spring AI `MessageChatMemoryAdvisor`。
- 不重做短期/长期记忆架构。
- 不修改 Redis key 前缀、序列化结构或会话 ID 规则。
- 不以关闭 OTel Redis instrumentation 作为业务优化手段。

## 现状

`findByConversationId(conversationId)` 当前流程：

```text
touch
readAllEntries
compactByAge
summarizeEvicted
return messages
```

`touch` 会执行：

```text
SADD memory:short:conversations
HSET memory:short:{conversation}:state lastTouchedAt
EXPIRE messages
EXPIRE sequence
EXPIRE state
```

`saveAll(conversationId, messages)` 当前流程：

```text
touch
readAllEntries
compactByAge
mergeSavedMessages
rewriteWindow
summarizeEvicted
```

`rewriteWindow` 会执行：

```text
DEL messages
RPUSHALL messages
EXPIRE messages
EXPIRE sequence
EXPIRE state
```

在流式模型调用中，`MessageChatMemoryAdvisor` 通常会先读历史，再写用户消息，最后写助手消息。因此同一请求会多次触发上述流程。

## 推荐方案

采用保守增量优化：在 `RedisChatMemoryRepository` 内增加追加快路径，复杂情况回退到现有整窗合并逻辑。

### 1. 读路径减负

`findByConversationId` 不再无条件 `touch`。读路径只做：

```text
readAllEntries
compactByAge
summarizeEvicted
return messages
```

如果年龄压缩确实重写窗口，则由重写逻辑负责刷新 TTL。普通读取不刷新 `lastTouchedAt`，会话活跃时间由写路径维护。

### 2. 写路径合并触摸和 TTL

新增 `markTouched(conversationId)` 或等价私有方法，只负责：

```text
SADD conversations
HSET state lastTouchedAt
```

TTL 刷新集中到写路径末尾执行一次：

```text
EXPIRE messages
EXPIRE sequence
EXPIRE state
```

避免 `touch`、`compactByAge`、`rewriteWindow` 多处重复刷新。

### 3. 新增追加快路径

`saveAll` 读取当前窗口后，先判断传入 `messages` 是否是当前窗口的简单追加：

```text
current messages 是 saved messages 的前缀
saved messages 比 current messages 多出 1 条或多条
当前窗口没有年龄淘汰
新增后不需要复杂重排
```

此外，`MessageWindowChatMemory` 在窗口已满时会把传入窗口裁成“当前窗口后缀 + 新消息”。这类安全的后缀重叠追加也可以走快路径：当前窗口的尾部必须与 `saved messages` 的头部连续匹配，新增消息只来自 `saved messages` 的剩余尾部。

满足条件时不执行整窗 `DEL + RPUSHALL`，改为：

```text
为每条新增消息 INCR sequence
RPUSH 新增 entry
LTRIM messages -savedMessages.size() -1
markTouched
refreshTtlOnce
```

`LTRIM` 使用传入保存窗口的长度，而不是直接使用仓库 `maxRecentMessages`。这样可以保持 `ChatMemoryRepository.saveAll(messages)` 的外部语义：最终 Redis 窗口与调用方传入的保存窗口一致。常规 `MessageWindowChatMemory(maxMessages = maxRecentMessages)` 路径下，保存窗口长度通常等于 `maxRecentMessages`，因此仍会按窗口上限淘汰旧消息。

被 `LTRIM` 淘汰的旧消息仍需进入长期摘要。为避免额外 Redis 读，快路径使用本次已读取的 `currentEntries` 与本次追加的 entries 计算实际被淘汰的消息，并调用 `summarizeEvicted`。

### 4. 回退整窗路径

以下情况继续使用现有合并重写逻辑：

- `saved messages` 既不是当前窗口的前缀追加，也不是安全的后缀重叠滑动追加。
- 年龄压缩淘汰了旧消息。
- 当前窗口存在系统消息去重、顺序调整等复杂情况。
- 后续测试发现 Spring AI 传入模式不满足追加快路径假设。

回退路径保留现有行为，只合并重复 TTL 刷新。

## 数据流

常规主链路一轮对话预期如下：

```text
读历史:
  LRANGE messages

写用户消息:
  LRANGE messages
  INCR sequence
  RPUSH messages
  LTRIM messages -savedMessages.size() -1
  SADD conversations
  HSET state lastTouchedAt
  EXPIRE messages / sequence / state

写助手消息:
  LRANGE messages
  INCR sequence
  RPUSH messages
  LTRIM messages -savedMessages.size() -1
  SADD conversations
  HSET state lastTouchedAt
  EXPIRE messages / sequence / state
```

相比现状，常规写路径去掉整窗 `DEL + RPUSHALL`，并减少重复 `EXPIRE`。

## 兼容与风险

- key 结构不变，已有 Redis 数据可继续读取。
- 序列号仍由 Redis `INCR` 分配，保持单会话内单调递增。
- 普通读不刷新 TTL，极端情况下只有读取没有写入的会话可能更早过期；对 `/api/react` 常规请求可接受，因为模型请求会写入当前轮消息。
- `LTRIM` 的淘汰摘要依赖本次读取的 `currentEntries` 和本次追加的 entries 计算，必须用测试覆盖窗口溢出场景。
- 快路径必须保证 `RPUSH + LTRIM` 后的 Redis 窗口等价于传入的 `saved messages`。如果无法证明等价，必须回退整窗重写。
- `saveAll` 如果先发生年龄淘汰，年龄压缩重写路径仍会刷新 TTL；常规无年龄淘汰写路径只在末尾刷新一次 TTL。
- 若快路径判断失败，必须回退旧逻辑，不能丢消息或改变顺序。

## 测试验收

新增或更新单元测试覆盖：

- `findByConversationId` 普通读取不调用 `touch`，只在年龄压缩时重写窗口并刷新 TTL。
- `saveAll` 简单追加单条用户消息时使用 `RPUSH` / `LTRIM`，不调用 `delete(messages)`。
- `saveAll` 简单追加助手消息时保持同样快路径。
- 窗口超过 `maxRecentMessages` 时，旧消息被识别为 evicted 并触发长期摘要。
- `MessageWindowChatMemory` 滑动窗口产生后缀重叠追加时，使用 `RPUSH` / `LTRIM`，并摘要被移出保存窗口的旧消息。
- 传入保存窗口长度大于仓库 `maxRecentMessages` 时，快路径按传入窗口长度裁剪，避免改变 `saveAll(messages)` 语义。
- 非前缀追加或复杂重排时回退整窗重写。
- 现有短期记忆测试全部通过。

人工验收：

- 运行 `/api/react` 后，Langfuse 中 Redis span 数量明显减少。
- 最近对话上下文仍能正确带入下一轮。
- 长期摘要相关测试不回退。
