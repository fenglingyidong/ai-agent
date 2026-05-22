package com.example.ragagent.memory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.content.MediaContent;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ConversationMemoryEntry(
        long sequence,
        long timestampEpochMillis,
        String messageType,
        String text,
        List<Media> media,
        Map<String, Object> metadata
) {

    /**
     * 规范化可空字段，确保存储的记忆条目始终带有安全默认值。
     */
    public ConversationMemoryEntry {
        text = text == null ? "" : text;
        media = media == null ? List.of() : media.stream().filter(Objects::nonNull).toList();
        metadata = metadata == null ? Collections.emptyMap() : metadata;
    }

    /**
     * 将 Spring AI 消息转换为可序列化的记忆条目，以便存入 Redis。
     */
    public static ConversationMemoryEntry fromMessage(long sequence, Message message) {
        String text = "";
        if (message.getMessageType() == MessageType.USER) {
            text = ((UserMessage) message).getText();
        }
        else if (message.getMessageType() == MessageType.ASSISTANT) {
            text = ((AssistantMessage) message).getText();
        }
        else if (message.getMessageType() == MessageType.SYSTEM) {
            text = ((SystemMessage) message).getText();
        }
        List<Media> media = message instanceof MediaContent mediaContent
                ? mediaContent.getMedia()
                : List.of();
        Map<String, Object> metadata = message.getMetadata() == null ? Collections.emptyMap() : message.getMetadata();
        return new ConversationMemoryEntry(
                sequence,
                Instant.now().toEpochMilli(),
                message.getMessageType().name(),
                text,
                media,
                metadata
        );
    }

    /**
     * 将 Redis 中的记忆条目还原为聊天流程使用的 Spring AI 消息类型。
     */
    public Message toMessage() {
        MessageType type = MessageType.valueOf(messageType);
        if (type == MessageType.USER) {
            return UserMessage.builder()
                    .text(text)
                    .media(media)
                    .metadata(metadata)
                    .build();
        }
        if (type == MessageType.ASSISTANT) {
            return AssistantMessage.builder()
                    .content(text)
                    .properties(metadata)
                    .media(media)
                    .build();
        }
        if (type == MessageType.SYSTEM) {
            return SystemMessage.builder()
                    .text(text)
                    .metadata(metadata)
                    .build();
        }
        throw new IllegalStateException("Unsupported memory message type: " + messageType);
    }

    /**
     * 将存储的毫秒时间戳暴露为 Instant，便于窗口计算。
     */
    public Instant timestamp() {
        return Instant.ofEpochMilli(timestampEpochMillis);
    }
}
