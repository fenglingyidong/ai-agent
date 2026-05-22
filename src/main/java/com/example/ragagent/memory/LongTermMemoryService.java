package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class LongTermMemoryService {

    static final String LONG_TERM_MEMORY_TYPE = "summary";

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final String LONG_TERM_MEMORY_PLACEHOLDER = "long_term_memory";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String LONG_TERM_SYSTEM_PROMPT = """
            当以下长期记忆与当前请求相关时，请使用它。
            将其视为持久的用户上下文，例如偏好、约束、稳定事实，
            以及此前对话中尚未完成的话题。
            ---------------------
            LONG_TERM_MEMORY:
            {long_term_memory}
            ---------------------
            """;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是一个对话记忆压缩器。
            请将提供的聊天记录整理成一个紧凑摘要，便于未来对话继续使用。
            只保留具有持续价值的信息，例如：
            1. 长期目标或反复出现的意图
            2. 已确认的偏好、约束和背景事实
            3. 已经得出的重要结论
            4. 之后还应继续推进的未完成话题

            排除问候语、重复内容，以及对未来没有价值的临时细节。
            输出格式：
            Intent: ...
            Key Facts:
            - ...
            Open Threads:
            - ...
            """;

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder;
    private final HierarchicalMemoryProperties properties;
    private final Executor executor;

    private ChatClient summarizerChatClient;

    public LongTermMemoryService(VectorStore vectorStore,
                                 ChatClient.Builder chatClientBuilder,
                                 HierarchicalMemoryProperties properties,
                                 @Qualifier("hierarchicalMemoryExecutor") Executor executor) {
        this.vectorStore = vectorStore;
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
        this.executor = executor;
        init();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (this.summarizerChatClient == null && this.chatClientBuilder != null) {
            this.summarizerChatClient = this.chatClientBuilder.clone().build();
        }
    }

    public String retrieveLongTermMemory(String userId, String userText) {
        if (!StringUtils.hasText(userText) || properties.getLongTermTopK() <= 0 || vectorStore == null) {
            return "";
        }

        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(userText)
                .topK(properties.getLongTermTopK())
                .filterExpression(buildLongTermFilter(userId));

        if (properties.getLongTermSimilarityThreshold() > 0) {
            searchBuilder.similarityThreshold(properties.getLongTermSimilarityThreshold());
        }

        List<Document> documents = vectorStore.similaritySearch(searchBuilder.build());
        return documents.stream()
                .map(Document::getText)
                .filter(StringUtils::hasText)
                .distinct()
                .reduce((left, right) -> left + System.lineSeparator() + System.lineSeparator() + right)
                .orElse("");
    }

    public String renderLongTermMemoryPrompt(String longTermMemory) {
        return LONG_TERM_SYSTEM_PROMPT.replace("{" + LONG_TERM_MEMORY_PLACEHOLDER + "}", longTermMemory);
    }

    public void scheduleSummaryIfNeeded(String userId,
                                        String conversationId,
                                        List<ConversationMemoryEntry> evictedEntries) {
        if (evictedEntries == null || evictedEntries.isEmpty() || executor == null) {
            return;
        }

        List<ConversationMemoryEntry> batch = List.copyOf(evictedEntries);
        CompletableFuture.runAsync(() -> summarizeAndPersist(userId, conversationId, batch), executor)
                .exceptionally(ex -> {
                    log.warn("Failed to summarize evicted memory for user {} and conversation {}", userId, conversationId, ex);
                    return null;
                });
    }

    private void summarizeAndPersist(String userId,
                                     String conversationId,
                                     List<ConversationMemoryEntry> evictedEntries) {
        if (summarizerChatClient == null || vectorStore == null) {
            return;
        }

        String transcript = evictedEntries.stream()
                .map(this::toTranscriptLine)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");

        if (!StringUtils.hasText(transcript)) {
            return;
        }

        String summaryPrompt = """
                User ID: %s
                Conversation Key: %s
                请总结以下刚刚从短期窗口移出的消息：
                %s
                """.formatted(normalizeUserId(userId), conversationId, transcript);

        String summary = summarizerChatClient.prompt()
                .system(SUMMARY_SYSTEM_PROMPT)
                .user(summaryPrompt)
                .call()
                .content();

        if (!StringUtils.hasText(summary)) {
            return;
        }

        ConversationMemoryEntry first = evictedEntries.get(0);
        ConversationMemoryEntry last = evictedEntries.get(evictedEntries.size() - 1);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("userId", normalizeUserId(userId));
        metadata.put("conversationKey", conversationId);
        metadata.put("memoryType", LONG_TERM_MEMORY_TYPE);
        metadata.put("fromSequence", first.sequence());
        metadata.put("toSequence", last.sequence());
        metadata.put("fromTimestamp", first.timestampEpochMillis());
        metadata.put("toTimestamp", last.timestampEpochMillis());
        metadata.put("summarizedAt", Instant.now().toEpochMilli());

        Document document = Document.builder()
                .id(conversationId.replace("::", "-") + "-" + first.sequence() + "-" + last.sequence())
                .text(summary)
                .metadata(metadata)
                .build();

        vectorStore.add(List.of(document));
    }

    private String buildLongTermFilter(String userId) {
        return "userId == '" + escapeFilterValue(normalizeUserId(userId))
                + "' && memoryType == '" + LONG_TERM_MEMORY_TYPE + "'";
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String toTranscriptLine(ConversationMemoryEntry entry) {
        String role = switch (entry.messageType()) {
            case "USER" -> "USER";
            case "ASSISTANT" -> "ASSISTANT";
            case "SYSTEM" -> "SYSTEM";
            default -> "OTHER";
        };
        return role + ": " + (StringUtils.hasText(entry.text()) ? entry.text() : "");
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }
}
