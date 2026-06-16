package com.example.ragagent.conversation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.ragagent.conversation.entity.ConversationSessionEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

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

    @Select("""
            SELECT s.session_id,
                   s.title,
                   s.created_at,
                   s.updated_at,
                   (SELECT COUNT(*)
                      FROM conversation_turns tc
                     WHERE tc.user_id = s.user_id AND tc.session_id = s.session_id) AS turn_count,
                   COALESCE((SELECT tu.user_text
                               FROM conversation_turns tu
                              WHERE tu.user_id = s.user_id AND tu.session_id = s.session_id
                              ORDER BY tu.turn_no DESC
                              LIMIT 1), '') AS latest_user_text,
                   COALESCE((SELECT ta.assistant_text
                               FROM conversation_turns ta
                              WHERE ta.user_id = s.user_id AND ta.session_id = s.session_id
                              ORDER BY ta.turn_no DESC
                              LIMIT 1), '') AS latest_assistant_text
             FROM conversation_sessions s
             WHERE s.user_id = #{userId} AND s.deleted_at IS NULL
             ORDER BY s.updated_at DESC
             LIMIT #{limit}
             OFFSET #{offset}
            """)
    List<ConversationSessionSummaryRow> selectRecentSessionSummaries(@Param("userId") String userId,
                                                                      @Param("limit") int limit,
                                                                      @Param("offset") int offset);
}
