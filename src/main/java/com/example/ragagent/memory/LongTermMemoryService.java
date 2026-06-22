package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import com.example.ragagent.config.MilvusVectorStoreConfiguration;
import com.example.ragagent.prompt.PromptTemplateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 负责长期记忆的召回、提示词渲染，以及把短期窗口淘汰内容总结后写入向量库。
 */
@Service
public class LongTermMemoryService {

    static final String LONG_TERM_MEMORY_TYPE = "summary";

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryService.class);
    private static final String LONG_TERM_MEMORY_PLACEHOLDER = "long_term_memory";
    private static final String DEFAULT_USER_ID = "anonymous";

    private final VectorStore vectorStore;
    private final HierarchicalMemoryProperties properties;
    private final Executor executor;
    private final ChatClient summarizerChatClient;
    private final PromptTemplateStore promptTemplateStore;
    private final String summarySystemPrompt;

    public LongTermMemoryService(@Qualifier(MilvusVectorStoreConfiguration.MEMORY_VECTOR_STORE) VectorStore vectorStore,
                                 ChatClient.Builder chatClientBuilder,
                                 HierarchicalMemoryProperties properties,
                                 @Qualifier("hierarchicalMemoryExecutor") Executor executor,
                                 PromptTemplateStore promptTemplateStore) {
        this.vectorStore = vectorStore;
        this.properties = properties;
        this.executor = executor;
        this.summarizerChatClient = chatClientBuilder == null ? null : chatClientBuilder.clone().build();
        this.promptTemplateStore = promptTemplateStore == null ? new PromptTemplateStore() : promptTemplateStore;
        this.summarySystemPrompt = this.promptTemplateStore.text("memory.summary.system");
    }

    /**
     * 按用户和当前输入从长期记忆向量库召回相关摘要。
     */
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

    /**
     * 将召回到的长期记忆渲染成可追加到系统提示词的片段。
     */
    public String renderLongTermMemoryPrompt(String longTermMemory) {
        return promptTemplateStore.render("memory.long-term.system", Map.of(LONG_TERM_MEMORY_PLACEHOLDER, longTermMemory));
    }

    /**
     * 异步调度短期记忆淘汰内容的摘要任务，空批次会直接跳过。
     */
    public CompletableFuture<Boolean> scheduleSummaryIfNeeded(String userId,
                                                              String conversationId,
                                                              List<ConversationMemoryEntry> evictedEntries) {
        if (evictedEntries == null || evictedEntries.isEmpty() || executor == null) {
            return CompletableFuture.completedFuture(false);
        }

        List<ConversationMemoryEntry> batch = List.copyOf(evictedEntries);
        return CompletableFuture.supplyAsync(() -> summarizeAndPersist(userId, conversationId, batch), executor)
                .exceptionally(ex -> {
                    log.warn("Failed to summarize evicted memory for user {} and conversation {}", userId, conversationId, ex);
                    return false;
                });
    }

    private boolean summarizeAndPersist(String userId,
                                        String conversationId,
                                        List<ConversationMemoryEntry> evictedEntries) {
        if (summarizerChatClient == null || vectorStore == null) {
            return false;
        }

        String transcript = evictedEntries.stream()
                .map(this::toTranscriptLine)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");

        if (!StringUtils.hasText(transcript)) {
            return false;
        }

        String summaryPrompt = """
                User ID: %s
                Conversation Key: %s
                请总结以下刚刚从短期窗口移出的消息：
                %s
                """.formatted(normalizeUserId(userId), conversationId, transcript);

        String summary = summarizerChatClient.prompt()
                .system(summarySystemPrompt)
                .user(summaryPrompt)
                .call()
                .content();

        if (!StringUtils.hasText(summary)) {
            return false;
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
                .id(buildMemoryDocumentId(conversationId, first.sequence(), last.sequence()))
                .text(summary)
                .metadata(metadata)
                .build();

        vectorStore.add(List.of(document));
        return true;
    }

    private org.springframework.ai.vectorstore.filter.Filter.Expression buildLongTermFilter(String userId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        return builder.and(
                        builder.eq("userId", normalizeUserId(userId)),
                        builder.eq("memoryType", LONG_TERM_MEMORY_TYPE)
                )
                .build();
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

    static String buildMemoryDocumentId(String conversationId, long fromSequence, long toSequence) {
        String seed = normalizeDocumentIdPart(conversationId) + ":" + fromSequence + ":" + toSequence;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizeDocumentIdPart(String value) {
        return StringUtils.hasText(value) ? value : DEFAULT_USER_ID;
    }
}
