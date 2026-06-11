package com.example.ragagent.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragagent.conversation.entity.ConversationSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ConversationSessionMapper extends BaseMapper<ConversationSessionEntity> {

    @Insert("""
            INSERT INTO conversation_sessions (user_id, session_id, title, next_turn_no, created_at, updated_at, deleted_at)
            VALUES (#{userId}, #{sessionId}, #{title}, 1, #{now}, #{now}, NULL)
            ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at), deleted_at = NULL
            """)
    int upsertSession(@Param("userId") String userId,
                      @Param("sessionId") String sessionId,
                      @Param("title") String title,
                      @Param("now") LocalDateTime now);

    @Select("""
            SELECT next_turn_no
            FROM conversation_sessions
            WHERE user_id = #{userId} AND session_id = #{sessionId}
            FOR UPDATE
            """)
    Long lockNextTurnNo(@Param("userId") String userId, @Param("sessionId") String sessionId);

    @Update("""
            UPDATE conversation_sessions
            SET next_turn_no = #{nextTurnNo}, updated_at = #{now}
            WHERE user_id = #{userId} AND session_id = #{sessionId}
            """)
    int updateNextTurnNo(@Param("userId") String userId,
                         @Param("sessionId") String sessionId,
                         @Param("nextTurnNo") long nextTurnNo,
                         @Param("now") LocalDateTime now);
}
