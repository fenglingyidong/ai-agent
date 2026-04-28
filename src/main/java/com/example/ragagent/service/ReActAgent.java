package com.example.ragagent.service;

import com.example.ragagent.memory.HierarchicalMemoryAdvisor;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.tools.BuiltInTools;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReActAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    static final String DEFAULT_USER_ID = "anonymous";
    static final String DEFAULT_SESSION_ID = "default";

    private static final String REACT_SYSTEM_PROMPT_TEMPLATE = """
            你是一个采用 ReAct 风格工作的 AI 助手。
            你需要先在内部完成分步推理，并在需要时主动调用工具。
            不要向用户暴露内部推理过程、工具调用参数、JSON 结构或中间 Observation，只输出最终答案。

            可用工具：
            %s

            规则：
            1. 能直接回答时直接回答。
            2. 当答案依赖知识库事实、内部文档、政策或说明时，优先使用 searchKnowledgeBase。
            3. %s
            4. 如果工具返回的信息不足，请明确说明不确定性，不要编造。
            """;

    private final ChatClient reactChatClient;
    private final BuiltInTools builtInTools;
    private final HierarchicalMemoryAdvisor hierarchicalMemoryAdvisor;
    private final PromptSecurityFilter promptSecurityFilter;
    private final ChatModelRegistry chatModelRegistry;
    private final Map<String, ToolDefinitionEntry> builtInToolDefinitions;
    private final List<ToolCallbackProvider> externalToolCallbackProviders;

    @Autowired
    public ReActAgent(ChatClient.Builder builder,
                      BuiltInTools builtInTools,
                      HierarchicalMemoryAdvisor hierarchicalMemoryAdvisor,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry,
                      List<ToolCallbackProvider> externalToolCallbackProviders) {
        this(
                builder.clone().build(),
                builtInTools,
                hierarchicalMemoryAdvisor,
                promptSecurityFilter,
                chatModelRegistry,
                externalToolCallbackProviders
        );
    }

    public ReActAgent(ChatClient reactChatClient,
                      ChatClient finalAnswerChatClient,
                      BuiltInTools builtInTools,
                      HierarchicalMemoryAdvisor hierarchicalMemoryAdvisor) {
        this(
                reactChatClient,
                builtInTools,
                hierarchicalMemoryAdvisor,
                new PromptSecurityFilter(),
                null,
                List.of()
        );
    }

    public ReActAgent(ChatClient reactChatClient,
                      ChatClient finalAnswerChatClient,
                      BuiltInTools builtInTools,
                      HierarchicalMemoryAdvisor hierarchicalMemoryAdvisor,
                      PromptSecurityFilter promptSecurityFilter,
                      ChatModelRegistry chatModelRegistry) {
        this(
                reactChatClient,
                builtInTools,
                hierarchicalMemoryAdvisor,
                promptSecurityFilter,
                chatModelRegistry,
                List.of()
        );
    }

    private ReActAgent(ChatClient reactChatClient,
                       BuiltInTools builtInTools,
                       HierarchicalMemoryAdvisor hierarchicalMemoryAdvisor,
                       PromptSecurityFilter promptSecurityFilter,
                       ChatModelRegistry chatModelRegistry,
                       List<ToolCallbackProvider> externalToolCallbackProviders) {
        this.reactChatClient = reactChatClient;
        this.builtInTools = builtInTools;
        this.hierarchicalMemoryAdvisor = hierarchicalMemoryAdvisor;
        this.promptSecurityFilter = promptSecurityFilter;
        this.chatModelRegistry = chatModelRegistry;
        this.externalToolCallbackProviders = externalToolCallbackProviders == null
                ? List.of()
                : List.copyOf(externalToolCallbackProviders);
        this.builtInToolDefinitions = loadBuiltInToolDefinitions();
    }

    public String run(String userMessage) {
        return join(runStream(resolveCurrentUserId(), resolveCurrentSessionId(), userMessage));
    }

    public Flux<String> runStream(String userMessage) {
        return runStream(resolveCurrentUserId(), resolveCurrentSessionId(), null, userMessage, false);
    }

    public Flux<String> runStream(String userMessage, String modelId) {
        return runStream(resolveCurrentUserId(), resolveCurrentSessionId(), modelId, userMessage, false);
    }

    public Flux<String> runStream(String userMessage, String modelId, boolean webSearchEnabled) {
        return runStream(resolveCurrentUserId(), resolveCurrentSessionId(), modelId, userMessage, webSearchEnabled);
    }

    public String run(String userId, String sessionId, String userMessage) {
        return join(runStream(userId, sessionId, null, userMessage, false));
    }

    public Flux<String> runStream(String userId, String sessionId, String userMessage) {
        return runStream(userId, sessionId, null, userMessage, false);
    }

    public Flux<String> runStream(String userId, String sessionId, String modelId, String userMessage) {
        return runStream(userId, sessionId, modelId, userMessage, false);
    }

    public Flux<String> runStream(String userId,
                                  String sessionId,
                                  String modelId,
                                  String userMessage,
                                  boolean webSearchEnabled) {
        PromptSecurityFilter.SecuredPrompt securedPrompt = promptSecurityFilter.secure(userMessage);
        logRequestTransformation(userId, sessionId, modelId, webSearchEnabled, securedPrompt);

        Map<String, ToolDefinitionEntry> activeToolDefinitions =
                resolveActiveToolDefinitions(userId, sessionId, webSearchEnabled);
        List<Message> history = buildConversationHistory(
                userId,
                sessionId,
                securedPrompt.safeInput(),
                securedPrompt.modelInput(),
                activeToolDefinitions,
                webSearchEnabled
        );

        return streamLlmResponse(
                userId,
                sessionId,
                modelId,
                history,
                activeToolDefinitions,
                webSearchEnabled,
                securedPrompt
        );
    }

    private List<Message> buildConversationHistory(String userId,
                                                   String sessionId,
                                                   String memoryLookupText,
                                                   String modelUserMessage,
                                                   Map<String, ToolDefinitionEntry> activeToolDefinitions,
                                                   boolean webSearchEnabled) {
        List<Message> history = new ArrayList<>();
        HierarchicalMemoryAdvisor.MemoryContext memoryContext =
                hierarchicalMemoryAdvisor.loadMemoryContext(userId, sessionId, memoryLookupText);

        if (memoryContext.longTermMemoryMessage() != null) {
            history.add(memoryContext.longTermMemoryMessage());
        }
        history.addAll(memoryContext.shortTermMessages());
        history.add(new UserMessage(modelUserMessage));

        log.info("""
                ReAct 记忆装载：
                userId={}
                sessionId={}
                webSearchEnabled={}
                longTermMemoryLoaded={}
                shortTermMessageCount={}
                memoryLookupText={}
                modelUserMessage={}
                tools={}
                """,
                userId,
                sessionId,
                webSearchEnabled,
                memoryContext.longTermMemoryMessage() != null,
                memoryContext.shortTermMessages().size(),
                formatForLog(memoryLookupText),
                formatForLog(modelUserMessage),
                String.join(", ", activeToolDefinitions.keySet()));

        return history;
    }

    private Flux<String> streamLlmResponse(String userId,
                                           String sessionId,
                                           String modelId,
                                           List<Message> history,
                                           Map<String, ToolDefinitionEntry> activeToolDefinitions,
                                           boolean webSearchEnabled,
                                           PromptSecurityFilter.SecuredPrompt securedPrompt) {
        ChatClient.ChatClientRequestSpec requestSpec = applyModelOptions(reactChatClient.prompt(), modelId);
        ChatClient.ChatClientRequestSpec requestWithSystem =
                requestSpec.system(buildReactSystemPrompt(activeToolDefinitions, webSearchEnabled));
        if (requestWithSystem == null) {
            requestWithSystem = requestSpec;
        }

        List<ToolCallback> activeToolCallbacks = activeToolDefinitions.values().stream()
                .map(ToolDefinitionEntry::callback)
                .toList();

        ChatClient.ChatClientRequestSpec requestWithTools = requestWithSystem.toolCallbacks(activeToolCallbacks);
        if (requestWithTools == null) {
            requestWithTools = requestWithSystem;
        }
        ChatClient.ChatClientRequestSpec finalRequestWithTools = requestWithTools;

        return Flux.defer(() -> {
            StreamingSensitiveValueRestorer restorer =
                    new StreamingSensitiveValueRestorer(securedPrompt.sensitiveValues());
            StringBuilder rawAnswerBuilder = new StringBuilder();
            StringBuilder restoredAnswerBuilder = new StringBuilder();

            Flux<String> restoredStream = finalRequestWithTools
                    .messages(history)
                    .stream()
                    .content()
                    .handle((chunk, sink) -> {
                        rawAnswerBuilder.append(chunk);
                        String restoredChunk = restorer.accept(chunk);
                        if (restoredChunk != null && !restoredChunk.isEmpty()) {
                            restoredAnswerBuilder.append(restoredChunk);
                            sink.next(restoredChunk);
                        }
                    })
                    .concatWith(Mono.fromSupplier(restorer::flush)
                            .flatMapMany(remaining -> {
                                if (remaining == null || remaining.isEmpty()) {
                                    return Flux.empty();
                                }
                                restoredAnswerBuilder.append(remaining);
                                return Flux.just(remaining);
                            }))
                    .cast(String.class);

            return restoredStream.doOnComplete(() -> finishStreamingResponse(
                    userId,
                    sessionId,
                    modelId,
                    securedPrompt,
                    rawAnswerBuilder.toString().trim(),
                    restoredAnswerBuilder.toString()
            ));
        });
    }

    private String join(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private String resolveCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && StringUtils.hasText(authentication.getName())
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return DEFAULT_USER_ID;
    }

    private String resolveCurrentSessionId() {
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletRequestAttributes) {
            HttpServletRequest request = servletRequestAttributes.getRequest();
            if (request != null && request.getSession(false) != null) {
                return request.getSession(false).getId();
            }
        }
        return DEFAULT_SESSION_ID;
    }

    private ChatClient.ChatClientRequestSpec applyModelOptions(ChatClient.ChatClientRequestSpec requestSpec, String modelId) {
        if (chatModelRegistry == null) {
            return requestSpec;
        }
        OpenAiChatOptions options = chatModelRegistry.createOptions(modelId);
        return options == null ? requestSpec : requestSpec.options(options);
    }

    private void logRequestTransformation(String userId,
                                          String sessionId,
                                          String modelId,
                                          boolean webSearchEnabled,
                                          PromptSecurityFilter.SecuredPrompt securedPrompt) {
        log.info("""
                ReAct 输入转换：
                userId={}
                sessionId={}
                modelId={}
                webSearchEnabled={}
                originalInput={}
                safeInput={}
                modelInput={}
                """,
                userId,
                sessionId,
                modelId,
                webSearchEnabled,
                formatForLog(securedPrompt.originalInput()),
                formatForLog(securedPrompt.safeInput()),
                formatForLog(securedPrompt.modelInput()));
    }

    private String restoreAndLogFinalAnswer(String userId,
                                            String sessionId,
                                            String modelId,
                                            String sourceStage,
                                            String finalAnswer,
                                            PromptSecurityFilter.SecuredPrompt securedPrompt) {
        String restoredFinalAnswer = promptSecurityFilter.restoreSensitiveValues(finalAnswer, securedPrompt);
        log.info("""
                ReAct 输出转换：
                userId={}
                sessionId={}
                modelId={}
                sourceStage={}
                outputBeforeRestore={}
                outputAfterRestore={}
                """,
                userId,
                sessionId,
                modelId,
                sourceStage,
                formatForLog(finalAnswer),
                formatForLog(restoredFinalAnswer));
        return restoredFinalAnswer;
    }

    private void finishStreamingResponse(String userId,
                                         String sessionId,
                                         String modelId,
                                         PromptSecurityFilter.SecuredPrompt securedPrompt,
                                         String rawFinalAnswer,
                                         String restoredFinalAnswer) {
        log.info("""
                ReAct 原生工具调用完成：
                userId={}
                sessionId={}
                modelId={}
                finalAnswerBeforeRestore={}
                """,
                userId,
                sessionId,
                modelId,
                formatForLog(rawFinalAnswer));

        hierarchicalMemoryAdvisor.rememberFinalTurn(
                userId,
                sessionId,
                securedPrompt.modelInput(),
                rawFinalAnswer
        );

        log.info("""
                ReAct 输出转换：
                userId={}
                sessionId={}
                modelId={}
                sourceStage={}
                outputBeforeRestore={}
                outputAfterRestore={}
                """,
                userId,
                sessionId,
                modelId,
                "native-tool-calling",
                formatForLog(rawFinalAnswer),
                formatForLog(restoredFinalAnswer));
    }

    private String formatForLog(String value) {
        return StringUtils.hasText(value) ? value : "<empty>";
    }

    private Map<String, ToolDefinitionEntry> loadBuiltInToolDefinitions() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(builtInTools)
                .build();

        Map<String, ToolDefinitionEntry> toolDefinitions = new LinkedHashMap<>();
        for (ToolCallback callback : provider.getToolCallbacks()) {
            ToolDefinitionEntry entry = buildToolDefinitionEntry(callback, false);
            if (entry != null) {
                toolDefinitions.put(entry.name(), entry);
            }
        }
        return Collections.unmodifiableMap(toolDefinitions);
    }

    private Map<String, ToolDefinitionEntry> resolveActiveToolDefinitions(String userId,
                                                                          String sessionId,
                                                                          boolean webSearchEnabled) {
        Map<String, ToolDefinitionEntry> activeToolDefinitions = new LinkedHashMap<>();
        builtInToolDefinitions.values().forEach(entry ->
                activeToolDefinitions.put(entry.name(), wrapToolDefinitionEntry(entry, userId, sessionId)));

        if (!webSearchEnabled) {
            return Collections.unmodifiableMap(activeToolDefinitions);
        }

        for (ToolCallbackProvider provider : externalToolCallbackProviders) {
            try {
                ToolCallback[] callbacks = provider.getToolCallbacks();
                if (callbacks == null) {
                    continue;
                }

                for (ToolCallback callback : callbacks) {
                    ToolDefinitionEntry entry = buildToolDefinitionEntry(callback, true);
                    if (entry == null || activeToolDefinitions.containsKey(entry.name())) {
                        continue;
                    }
                    activeToolDefinitions.put(entry.name(), wrapToolDefinitionEntry(entry, userId, sessionId));
                }
            }
            catch (RuntimeException ex) {
                log.warn("Failed to resolve MCP tool callbacks, web search will be skipped for this request", ex);
            }
        }

        return Collections.unmodifiableMap(activeToolDefinitions);
    }

    private ToolDefinitionEntry buildToolDefinitionEntry(ToolCallback callback, boolean external) {
        if (callback == null || callback.getToolDefinition() == null) {
            return null;
        }

        String toolName = callback.getToolDefinition().name();
        if (!StringUtils.hasText(toolName)) {
            return null;
        }

        return new ToolDefinitionEntry(
                toolName,
                callback.getToolDefinition().description(),
                buildInputHint(callback),
                external,
                callback
        );
    }

    private ToolDefinitionEntry wrapToolDefinitionEntry(ToolDefinitionEntry entry, String userId, String sessionId) {
        return new ToolDefinitionEntry(
                entry.name(),
                entry.description(),
                entry.inputHint(),
                entry.external(),
                new LoggingToolCallback(entry.callback(), userId, sessionId)
        );
    }

    private String buildInputHint(ToolCallback callback) {
        String inputSchema = callback.getToolDefinition().inputSchema();
        if (!StringUtils.hasText(inputSchema)) {
            return "";
        }

        String compactSchema = inputSchema.replaceAll("\\s+", " ").trim();
        if (compactSchema.length() <= 220) {
            return compactSchema;
        }
        return compactSchema.substring(0, 220) + "...";
    }

    private String buildReactSystemPrompt(Map<String, ToolDefinitionEntry> toolDefinitions, boolean webSearchEnabled) {
        String networkRule = webSearchEnabled && hasExternalTools(toolDefinitions)
                ? "对于需要最新公开互联网信息的问题，优先使用联网搜索相关工具。"
                : "当前未启用联网搜索工具，不要假设自己能够访问互联网。";
        return REACT_SYSTEM_PROMPT_TEMPLATE.formatted(renderToolList(toolDefinitions), networkRule);
    }

    private boolean hasExternalTools(Map<String, ToolDefinitionEntry> toolDefinitions) {
        return toolDefinitions.values().stream().anyMatch(ToolDefinitionEntry::external);
    }

    private String renderToolList(Map<String, ToolDefinitionEntry> toolDefinitions) {
        return toolDefinitions.values().stream()
                .map(tool -> {
                    StringBuilder line = new StringBuilder("- ")
                            .append(tool.name())
                            .append(": ")
                            .append(tool.description());
                    if (StringUtils.hasText(tool.inputHint())) {
                        line.append(" Input schema: ").append(tool.inputHint());
                    }
                    return line.toString();
                })
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("- 无");
    }

    private record ToolDefinitionEntry(
            String name,
            String description,
            String inputHint,
            boolean external,
            ToolCallback callback
    ) {
    }

    private static final class LoggingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final String userId;
        private final String sessionId;

        private LoggingToolCallback(ToolCallback delegate, String userId, String sessionId) {
            this.delegate = delegate;
            this.userId = userId;
            this.sessionId = sessionId;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String input) {
            logToolInput(input);
            try {
                String result = delegate.call(input);
                logToolOutput(result);
                return result;
            }
            catch (RuntimeException ex) {
                logToolError(ex);
                throw ex;
            }
        }

        @Override
        public String call(String input, ToolContext toolContext) {
            logToolInput(input);
            try {
                String result = delegate.call(input, toolContext);
                logToolOutput(result);
                return result;
            }
            catch (RuntimeException ex) {
                logToolError(ex);
                throw ex;
            }
        }

        private void logToolInput(String input) {
            log.info("""
                    ReAct 工具调用开始：
                    userId={}
                    sessionId={}
                    toolName={}
                    toolInput={}
                    """,
                    userId,
                    sessionId,
                    getToolDefinition().name(),
                    StringUtils.hasText(input) ? input : "<empty>");
        }

        private void logToolOutput(String result) {
            log.info("""
                    ReAct 工具调用完成：
                    userId={}
                    sessionId={}
                    toolName={}
                    toolOutput={}
                    """,
                    userId,
                    sessionId,
                    getToolDefinition().name(),
                    StringUtils.hasText(result) ? result : "<empty>");
        }

        private void logToolError(RuntimeException ex) {
            log.warn("""
                    ReAct 工具调用失败：
                    userId={}
                    sessionId={}
                    toolName={}
                    error={}
                    """,
                    userId,
                    sessionId,
                    getToolDefinition().name(),
                    ex.getMessage(),
                    ex);
        }
    }

    private static final class StreamingSensitiveValueRestorer {

        private final Map<String, String> sensitiveValues;
        private final StringBuilder pending = new StringBuilder();

        private StreamingSensitiveValueRestorer(Map<String, String> sensitiveValues) {
            this.sensitiveValues = sensitiveValues == null ? Map.of() : sensitiveValues;
        }

        private String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return "";
            }

            pending.append(chunk);
            int keepFrom = findIncompletePlaceholderStart(pending);
            String readyText = pending.substring(0, keepFrom);
            String remaining = pending.substring(keepFrom);
            pending.setLength(0);
            pending.append(remaining);
            return restore(readyText);
        }

        private String flush() {
            String all = pending.toString();
            pending.setLength(0);
            return restore(all);
        }

        private String restore(String text) {
            String restored = text;
            for (Map.Entry<String, String> entry : sensitiveValues.entrySet()) {
                restored = restored.replace(entry.getKey(), entry.getValue());
            }
            return restored;
        }

        private int findIncompletePlaceholderStart(CharSequence text) {
            for (int i = text.length() - 2; i >= 0; i--) {
                if (text.charAt(i) == '[' && text.charAt(i + 1) == '[') {
                    if (!containsPlaceholderEnd(text, i + 2)) {
                        return i;
                    }
                    break;
                }
            }
            return text.length();
        }

        private boolean containsPlaceholderEnd(CharSequence text, int start) {
            for (int i = start; i < text.length() - 1; i++) {
                if (text.charAt(i) == ']' && text.charAt(i + 1) == ']') {
                    return true;
                }
            }
            return false;
        }
    }
}
