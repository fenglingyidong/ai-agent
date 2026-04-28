package com.example.ragagent.memory;

import com.example.ragagent.config.HierarchicalMemoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HierarchicalMemoryAdvisorTest {

    @Test
    void beforeShouldAppendUserMessageWithEmptyMediaWhenPromptContainsPlainTextUserMessage() {
        RedisSlidingWindowMemoryStore shortTermMemoryStore = mock(RedisSlidingWindowMemoryStore.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        ChatClient summarizerChatClient = mock(ChatClient.class);
        HierarchicalMemoryProperties properties = new HierarchicalMemoryProperties();
        Executor executor = Runnable::run;

        properties.setLongTermTopK(0);
        when(chatClientBuilder.clone()).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(summarizerChatClient);

        RedisSlidingWindowMemoryStore.MemoryWindowSnapshot emptySnapshot =
                new RedisSlidingWindowMemoryStore.MemoryWindowSnapshot(List.of(), List.of());
        when(shortTermMemoryStore.loadWindow("user-1::session-1")).thenReturn(emptySnapshot);
        when(shortTermMemoryStore.append(eq("user-1::session-1"), any(Message.class))).thenReturn(emptySnapshot);

        HierarchicalMemoryAdvisor advisor = new HierarchicalMemoryAdvisor(
                shortTermMemoryStore,
                vectorStore,
                chatClientBuilder,
                properties,
                executor
        );

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new UserMessage("hello"))))
                .context(Map.of(
                        HierarchicalMemoryAdvisor.USER_ID_KEY, "user-1",
                        HierarchicalMemoryAdvisor.SESSION_ID_KEY, "session-1"
                ))
                .build();

        advisor.before(request, mock(AdvisorChain.class));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(shortTermMemoryStore).append(eq("user-1::session-1"), messageCaptor.capture());
        UserMessage userMessage = assertInstanceOf(UserMessage.class, messageCaptor.getValue());
        assertNotNull(userMessage.getMedia());
        assertTrue(userMessage.getMedia().isEmpty());
    }
}
