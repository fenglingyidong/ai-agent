package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LongTermMemoryAdvisorTest {

    @Test
    void beforeShouldInjectLongTermMemorySystemMessageWhenRelevantMemoryExists() {
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(longTermMemoryService);
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(
                        new SystemMessage("base system"),
                        new UserMessage("hello")
                )))
                .context(Map.of(LongTermMemoryAdvisor.USER_ID_KEY, "user-1"))
                .build();

        when(longTermMemoryService.retrieveLongTermMemory("user-1", "hello")).thenReturn("user prefers Java");
        when(longTermMemoryService.renderLongTermMemoryPrompt("user prefers Java"))
                .thenReturn("LONG_TERM_MEMORY: user prefers Java");

        ChatClientRequest updated = advisor.before(request, mock(AdvisorChain.class));

        List<Message> messages = updated.prompt().getInstructions();
        assertTrue(messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(message -> ((SystemMessage) message).getText())
                .anyMatch(text -> text.contains("LONG_TERM_MEMORY: user prefers Java")));
        assertTrue(messages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(message -> ((SystemMessage) message).getText())
                .anyMatch(text -> text.contains("base system")));
    }

    @Test
    void beforeShouldKeepPromptUnchangedWhenNoLongTermMemoryExists() {
        LongTermMemoryService longTermMemoryService = mock(LongTermMemoryService.class);
        LongTermMemoryAdvisor advisor = new LongTermMemoryAdvisor(longTermMemoryService);
        Prompt prompt = new Prompt(List.of(new UserMessage("hello")));
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(prompt)
                .context(Map.of(LongTermMemoryAdvisor.USER_ID_KEY, "user-1"))
                .build();

        when(longTermMemoryService.retrieveLongTermMemory("user-1", "hello")).thenReturn("");

        ChatClientRequest updated = advisor.before(request, mock(AdvisorChain.class));

        assertSame(prompt, updated.prompt());
        assertEquals(request, updated);
    }
}
