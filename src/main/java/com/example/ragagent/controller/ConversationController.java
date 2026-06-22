package com.example.ragagent.controller;

import com.example.ragagent.conversation.ConversationLogService;
import com.example.ragagent.conversation.ConversationSessionSummary;
import com.example.ragagent.conversation.ConversationTurnRecord;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供会话列表、会话轮次查询和删除接口，支撑前端历史会话视图。
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final String DEFAULT_USER_ID = "anonymous";

    private final ConversationLogService conversationLogService;

    public ConversationController(ConversationLogService conversationLogService) {
        this.conversationLogService = conversationLogService;
    }

    /**
     * 分页返回当前用户最近的会话摘要。
     */
    @GetMapping
    public ConversationsResponse sessions(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                          @RequestParam(value = "offset", defaultValue = "0") int offset,
                                          Authentication authentication) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        return new ConversationsResponse(
                conversationLogService.listRecentSessions(resolveCurrentUserId(authentication), safeLimit, safeOffset)
        );
    }

    /**
     * 返回指定会话的最近对话轮次。
     */
    @GetMapping("/{sessionId}/turns")
    public ConversationTurnsResponse turns(@PathVariable String sessionId,
                                           @RequestParam(value = "limit", defaultValue = "50") int limit,
                                           Authentication authentication) {
        String userId = resolveCurrentUserId(authentication);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return new ConversationTurnsResponse(
                sessionId,
                conversationLogService.listRecentTurns(userId, sessionId, safeLimit)
        );
    }

    /**
     * 删除当前用户下指定会话的持久化记录。
     */
    @DeleteMapping("/{sessionId}")
    public void deleteSession(@PathVariable String sessionId, Authentication authentication) {
        conversationLogService.deleteSession(resolveCurrentUserId(authentication), sessionId);
    }

    private String resolveCurrentUserId(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()
                && StringUtils.hasText(authentication.getName())
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return DEFAULT_USER_ID;
    }

    /**
     * 会话轮次查询响应。
     */
    public record ConversationTurnsResponse(
            String sessionId,
            List<ConversationTurnRecord> items
    ) {
    }

    /**
     * 会话摘要列表响应。
     */
    public record ConversationsResponse(List<ConversationSessionSummary> items) {
    }
}
