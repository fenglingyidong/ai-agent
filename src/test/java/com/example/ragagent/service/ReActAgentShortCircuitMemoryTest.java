package com.example.ragagent.service;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.mall.MallMcpContextClient;
import com.example.ragagent.memory.ConversationMemoryService;
import com.example.ragagent.memory.LongTermMemoryAdvisor;
import com.example.ragagent.security.PromptSecurityFilter;
import com.example.ragagent.tools.BuiltInTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReActAgentShortCircuitMemoryTest {

    @Test
    void runStreamShouldRememberShortCircuitFastLaneTurn() {
        ChatClient reactChatClient = mock(ChatClient.class);
        BuiltInTools builtInTools = mock(BuiltInTools.class);
        LongTermMemoryAdvisor longTermMemoryAdvisor = mock(LongTermMemoryAdvisor.class);
        MessageChatMemoryAdvisor messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new InMemoryChatMemoryRepository())
                        .build()
        ).build();
        ConversationMemoryService conversationMemoryService = mock(ConversationMemoryService.class);
        ShoppingIntentRouter intentRouter = mock(ShoppingIntentRouter.class);
        MallMcpContextClient mallMcpContextClient = mock(MallMcpContextClient.class);
        SimpleTaskAgent simpleTaskAgent = mock(SimpleTaskAgent.class);
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "PRICE_STOCK_QUERY",
                Map.of(),
                Map.of("product_name", "blocks"),
                false,
                0.95,
                "price query"
        );

        when(intentRouter.route(eq("How much are blocks?"), eq(List.of()), anyString())).thenReturn(route);
        when(mallMcpContextClient.register("user-fast", "session-fast", "", "", ""))
                .thenReturn(MallMcpContextClient.MallMcpContextRegistration.success());
        when(simpleTaskAgent.tryRun(route, "How much are blocks?", "session-fast", 0.7, ""))
                .thenReturn(FastLaneResult.handled(Flux.just("Blocks cost ", "49 yuan")));

        ReActAgent agent = new ReActAgent(
                builderFor(reactChatClient),
                builtInTools,
                longTermMemoryAdvisor,
                messageChatMemoryAdvisor,
                conversationMemoryService,
                new PromptSecurityFilter(),
                null,
                new ShoppingRouteExecutor(intentRouter, mallMcpContextClient, simpleTaskAgent),
                List.of(),
                mock(ConversationLogService.class)
        );

        String result = collect(agent.runStream(
                "user-fast",
                "session-fast",
                null,
                "How much are blocks?",
                false,
                List.of(),
                "",
                "",
                ""
        ));

        assertEquals("Blocks cost 49 yuan", result);
        verify(conversationMemoryService).rememberTurn(
                "user-fast",
                "session-fast",
                secure("How much are blocks?").modelInput(),
                "Blocks cost 49 yuan"
        );
        verifyNoInteractions(reactChatClient);
    }

    private ChatClient.Builder builderFor(ChatClient reactChatClient) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.clone()).thenReturn(builder);
        when(builder.build()).thenReturn(reactChatClient);
        return builder;
    }

    private String collect(Flux<String> stream) {
        List<String> chunks = stream.collectList().block();
        return chunks == null ? "" : String.join("", chunks);
    }

    private PromptSecurityFilter.SecuredPrompt secure(String userText) {
        return new PromptSecurityFilter().secure(userText);
    }
}
