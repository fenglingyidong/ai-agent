package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryEntryTest {

    @Test
    void toMessageShouldUseEmptyMediaListWhenDeserializedMediaIsNull() {
        ConversationMemoryEntry entry = new ConversationMemoryEntry(
                1L,
                System.currentTimeMillis(),
                "USER",
                "hello",
                null,
                null
        );

        UserMessage userMessage = assertInstanceOf(UserMessage.class, entry.toMessage());

        assertNotNull(userMessage.getMedia());
        assertTrue(userMessage.getMedia().isEmpty());
        assertNotNull(userMessage.getMetadata());
        assertEquals(MessageType.USER, userMessage.getMetadata().get("messageType"));
    }

    @Test
    void fromMessageShouldPersistEmptyMediaListForPlainTextUserMessage() {
        UserMessage userMessage = new UserMessage("plain text only");

        ConversationMemoryEntry entry = ConversationMemoryEntry.fromMessage(2L, userMessage);

        assertNotNull(entry.media());
        assertTrue(entry.media().isEmpty());
    }
}
