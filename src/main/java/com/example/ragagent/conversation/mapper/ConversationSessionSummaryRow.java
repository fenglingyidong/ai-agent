package com.example.ragagent.conversation.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 会话摘要查询的中间行对象，用于从 SQL 结果转换成 API record。
 */
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
