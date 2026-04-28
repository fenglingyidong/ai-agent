package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class HierarchicalMemoryAdvisor implements BaseAdvisor {

    public static final String USER_ID_KEY = "chat_memory_user_id";
    public static final String SESSION_ID_KEY = "chat_memory_session_id";

    private static final Logger log = LoggerFactory.getLogger(HierarchicalMemoryAdvisor.class);
    private static final String LONG_TERM_MEMORY_PLACEHOLDER = "long_term_memory";
    private static final String LONG_TERM_MEMORY_TYPE = "summary";
    private static final String DEFAULT_USER_ID = "anonymous";
    private static final String DEFAULT_SESSION_ID = "default";

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

    private final RedisSlidingWindowMemoryStore shortTermMemoryStore;
    private final VectorStore vectorStore;
    private final ChatClient summarizerChatClient;
    private final HierarchicalMemoryProperties properties;
    private final Executor executor;

    public HierarchicalMemoryAdvisor(RedisSlidingWindowMemoryStore shortTermMemoryStore,
                                     VectorStore vectorStore,
                                     ChatClient.Builder chatClientBuilder,
                                     HierarchicalMemoryProperties properties,
                                     @Qualifier("hierarchicalMemoryExecutor") Executor executor) {
        this.shortTermMemoryStore = shortTermMemoryStore;
        this.vectorStore = vectorStore;
        this.summarizerChatClient = chatClientBuilder.clone().build();
        this.properties = properties;
        this.executor = executor;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String userId = resolveUserId(request.context());
        String sessionId = resolveSessionId(request.context());
        String shortTermKey = buildShortTermKey(userId, sessionId);

        RedisSlidingWindowMemoryStore.MemoryWindowSnapshot snapshot = shortTermMemoryStore.loadWindow(shortTermKey);
        scheduleSummaryIfNeeded(userId, shortTermKey, snapshot.evictedEntries());

        String currentUserText = extractCurrentUserText(request.prompt());
        String longTermMemory = retrieveLongTermMemory(userId, currentUserText);
        Prompt updatedPrompt = buildPrompt(request.prompt(), snapshot.retainedEntries(), longTermMemory);

        if (StringUtils.hasText(currentUserText)) {
            RedisSlidingWindowMemoryStore.MemoryWindowSnapshot afterUserAppend = shortTermMemoryStore.append(
                    shortTermKey,
                    UserMessage.builder()
                            .text(currentUserText)
                            .media(extractCurrentUserMedia(request.prompt()))
                            .build()
            );
            scheduleSummaryIfNeeded(userId, shortTermKey, afterUserAppend.evictedEntries());
        }

        return request.mutate().prompt(updatedPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse advisedResponse, AdvisorChain advisorChain) {
        String userId = resolveUserId(advisedResponse.context());
        String sessionId = resolveSessionId(advisedResponse.context());
        String shortTermKey = buildShortTermKey(userId, sessionId);

        List<Message> assistantMessages = advisedResponse.chatResponse().getResults().stream()
                .map(result -> (Message) result.getOutput())
                .toList();

        RedisSlidingWindowMemoryStore.MemoryWindowSnapshot snapshot =
                shortTermMemoryStore.append(shortTermKey, assistantMessages);
        scheduleSummaryIfNeeded(userId, shortTermKey, snapshot.evictedEntries());
        return advisedResponse;
    }

    @Override
    public String getName() {
        return "hierarchicalMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    public void rememberFinalTurn(String userId, String sessionId, String userText, String assistantText) {
        String effectiveUserId = normalizeUserId(userId);
        String shortTermKey = buildShortTermKey(effectiveUserId, sessionId);
        List<Message> finalTurn = new ArrayList<>();

        if (StringUtils.hasText(userText)) {
            finalTurn.add(new UserMessage(userText));
        }
        if (StringUtils.hasText(assistantText)) {
            finalTurn.add(new AssistantMessage(assistantText));
        }
        if (finalTurn.isEmpty()) {
            return;
        }

        RedisSlidingWindowMemoryStore.MemoryWindowSnapshot snapshot =
                shortTermMemoryStore.append(shortTermKey, finalTurn);
        scheduleSummaryIfNeeded(effectiveUserId, shortTermKey, snapshot.evictedEntries());
    }

    public MemoryContext loadMemoryContext(String userId, String sessionId, String currentUserText) {
        String effectiveUserId = normalizeUserId(userId);
        String shortTermKey = buildShortTermKey(effectiveUserId, sessionId);

        RedisSlidingWindowMemoryStore.MemoryWindowSnapshot snapshot = shortTermMemoryStore.loadWindow(shortTermKey);
        scheduleSummaryIfNeeded(effectiveUserId, shortTermKey, snapshot.evictedEntries());

        List<Message> shortTermMessages = snapshot.retainedEntries().stream()
                .map(ConversationMemoryEntry::toMessage)
                .toList();

        String longTermMemory = retrieveLongTermMemory(effectiveUserId, currentUserText);
        Message longTermMemoryMessage = StringUtils.hasText(longTermMemory)
                ? new SystemMessage(renderLongTermMemoryPrompt(longTermMemory))
                : null;

        return new MemoryContext(shortTermMessages, longTermMemory, longTermMemoryMessage);
    }

    public String buildShortTermKey(String userId, String sessionId) {
        return normalizeUserId(userId) + "::" + normalizeSessionId(sessionId);
    }

    private Prompt buildPrompt(Prompt originalPrompt,
                               List<ConversationMemoryEntry> retainedEntries,
                               String longTermMemory) {
        Prompt promptWithLongTerm = StringUtils.hasText(longTermMemory)
                ? originalPrompt.augmentSystemMessage(renderLongTermMemoryPrompt(longTermMemory))
                : originalPrompt;

        List<Message> promptMessages = new ArrayList<>(promptWithLongTerm.getInstructions());
        List<Message> shortTermMessages = retainedEntries.stream()
                .map(ConversationMemoryEntry::toMessage)
                .toList();

        if (!shortTermMessages.isEmpty()) {
            int insertIndex = findCurrentUserIndex(promptMessages);
            promptMessages.addAll(insertIndex, shortTermMessages);
        }

        return new Prompt(promptMessages, promptWithLongTerm.getOptions());
    }

    private int findCurrentUserIndex(List<Message> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage) {
                return index;
            }
        }
        return messages.size();
    }

    private String extractCurrentUserText(Prompt prompt) {
        UserMessage userMessage = prompt.getUserMessage();
        return userMessage == null ? "" : userMessage.getText();
    }

    private List<Media> extractCurrentUserMedia(Prompt prompt) {
        UserMessage userMessage = prompt.getUserMessage();
        return userMessage == null || userMessage.getMedia() == null ? List.of() : userMessage.getMedia();
    }

    private String resolveUserId(Map<String, Object> adviseContext) {
        Object userId = adviseContext.get(USER_ID_KEY);
        return userId instanceof String id && StringUtils.hasText(id) ? id : DEFAULT_USER_ID;
    }

    private String resolveSessionId(Map<String, Object> adviseContext) {
        Object sessionId = adviseContext.get(SESSION_ID_KEY);
        return sessionId instanceof String id && StringUtils.hasText(id) ? id : DEFAULT_SESSION_ID;
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId : DEFAULT_USER_ID;
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }

    private String retrieveLongTermMemory(String userId, String userText) {
        if (!StringUtils.hasText(userText) || properties.getLongTermTopK() <= 0) {
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

    private String buildLongTermFilter(String userId) {
        return "userId == '" + escapeFilterValue(normalizeUserId(userId))
                + "' && memoryType == '" + LONG_TERM_MEMORY_TYPE + "'";
    }

    private String escapeFilterValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private String renderLongTermMemoryPrompt(String longTermMemory) {
        return LONG_TERM_SYSTEM_PROMPT.replace("{" + LONG_TERM_MEMORY_PLACEHOLDER + "}", longTermMemory);
    }

    private void scheduleSummaryIfNeeded(String userId,
                                         String shortTermKey,
                                         List<ConversationMemoryEntry> evictedEntries) {
        if (evictedEntries == null || evictedEntries.isEmpty()) {
            return;
        }

        List<ConversationMemoryEntry> batch = List.copyOf(evictedEntries);
        CompletableFuture.runAsync(() -> summarizeAndPersist(userId, shortTermKey, batch), executor)
                .exceptionally(ex -> {
                    log.warn("Failed to summarize evicted memory for user {} and key {}", userId, shortTermKey, ex);
                    return null;
                });
    }

    private void summarizeAndPersist(String userId,
                                     String shortTermKey,
                                     List<ConversationMemoryEntry> evictedEntries) {
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
                """.formatted(normalizeUserId(userId), shortTermKey, transcript);

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
        metadata.put("conversationKey", shortTermKey);
        metadata.put("memoryType", LONG_TERM_MEMORY_TYPE);
        metadata.put("fromSequence", first.sequence());
        metadata.put("toSequence", last.sequence());
        metadata.put("fromTimestamp", first.timestampEpochMillis());
        metadata.put("toTimestamp", last.timestampEpochMillis());
        metadata.put("summarizedAt", Instant.now().toEpochMilli());

        Document document = Document.builder()
                .id(shortTermKey.replace("::", "-") + "-" + first.sequence() + "-" + last.sequence())
                .text(summary)
                .metadata(metadata)
                .build();

        vectorStore.add(List.of(document));
    }

    private String toTranscriptLine(ConversationMemoryEntry entry) {
        String role = switch (entry.messageType()) {
            case "USER" -> "USER";
            case "ASSISTANT" -> "ASSISTANT";
            default -> "OTHER";
        };
        return role + ": " + (StringUtils.hasText(entry.text()) ? entry.text() : "");
    }

    public record MemoryContext(
            List<Message> shortTermMessages,
            String longTermMemory,
            Message longTermMemoryMessage
    ) {
    }
}
