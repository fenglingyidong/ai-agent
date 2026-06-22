package com.example.ragagent.conversation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * conversation_sessions 表实体，保存会话摘要和下一轮轮次号。
 */
@Getter
@Setter
@TableName("conversation_sessions")
public class ConversationSessionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String userId;

    private String sessionId;

    private String title;

    private Long nextTurnNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;
}
