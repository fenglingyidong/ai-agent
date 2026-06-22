package com.example.ragagent.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragagent.conversation.entity.ConversationTurnEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话轮次表的 MyBatis-Plus Mapper，封装状态更新和历史轮次查询 SQL。
 */
@Mapper
public interface ConversationTurnMapper extends BaseMapper<ConversationTurnEntity> {

    /**
     * 写入流式响应完成后的助手输出、状态、错误和元数据。
     */
    @Update("""
            UPDATE conversation_turns
            SET assistant_text = #{assistantText},
                status = #{status},
                error_message = #{errorMessage},
                completed_at = #{completedAt},
                metadata_json = #{metadataJson},
                updated_at = #{updatedAt}
            WHERE turn_id = #{turnId}
            """)
    int updateTurnResult(@Param("turnId") String turnId,
                         @Param("assistantText") String assistantText,
                         @Param("status") String status,
                         @Param("errorMessage") String errorMessage,
                         @Param("completedAt") LocalDateTime completedAt,
                         @Param("metadataJson") String metadataJson,
                         @Param("updatedAt") LocalDateTime updatedAt);

    /**
     * 查询指定会话最近若干轮，并按正序返回给前端。
     */
    @Select("""
            SELECT *
            FROM (
                SELECT *
                FROM conversation_turns
                WHERE user_id = #{userId} AND session_id = #{sessionId}
                ORDER BY turn_no DESC
                LIMIT #{limit}
            ) recent_turns
            ORDER BY turn_no ASC
            """)
    List<ConversationTurnEntity> selectRecentTurns(@Param("userId") String userId,
                                                   @Param("sessionId") String sessionId,
                                                   @Param("limit") int limit);

    /**
     * 删除超过会话保留上限的旧轮次。
     */
    @Delete("""
            DELETE FROM conversation_turns
            WHERE user_id = #{userId} AND session_id = #{sessionId}
              AND id NOT IN (
                SELECT id FROM (
                    SELECT id
                    FROM conversation_turns
                    WHERE user_id = #{userId} AND session_id = #{sessionId}
                    ORDER BY turn_no DESC
                    LIMIT #{maxTurns}
                ) kept_turns
              )
            """)
    int deleteOverflowTurns(@Param("userId") String userId,
                            @Param("sessionId") String sessionId,
                            @Param("maxTurns") int maxTurns);

    /**
     * 删除指定用户会话下的全部轮次。
     */
    @Delete("DELETE FROM conversation_turns WHERE user_id = #{userId} AND session_id = #{sessionId}")
    int deleteByUserAndSession(@Param("userId") String userId, @Param("sessionId") String sessionId);
}
