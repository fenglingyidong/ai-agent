package com.example.ragagent.conversation.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class ConversationSessionSummaryRow {

    private String sessionId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long turnCount;
    private String latestUserText;
    private String latestAssistantText;
}
