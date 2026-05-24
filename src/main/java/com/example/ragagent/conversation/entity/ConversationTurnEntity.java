package com.example.ragagent.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("conversation_turns")
public class ConversationTurnEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String turnId;

    private String userId;

    private String sessionId;

    private Long turnNo;

    private String modelId;

    private Boolean webSearchEnabled;

    private String userText;

    private String assistantText;

    private String status;

    private String errorMessage;

    private String metadataJson;

    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private LocalDateTime updatedAt;
}
