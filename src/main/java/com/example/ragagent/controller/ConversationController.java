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

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private static final String DEFAULT_USER_ID = "anonymous";

    private final ConversationLogService conversationLogService;

    public ConversationController(ConversationLogService conversationLogService) {
        this.conversationLogService = conversationLogService;
    }

    @GetMapping
    public ConversationsResponse sessions(@RequestParam(value = "limit", defaultValue = "50") int limit,
                                          Authentication authentication) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return new ConversationsResponse(
                conversationLogService.listRecentSessions(resolveCurrentUserId(authentication), safeLimit)
        );
    }

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

    public record ConversationTurnsResponse(
            String sessionId,
            List<ConversationTurnRecord> items
    ) {
    }

    public record ConversationsResponse(List<ConversationSessionSummary> items) {
    }
}
