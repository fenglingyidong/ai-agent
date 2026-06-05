package com.example.ragagent.commerce;

import java.util.List;
import java.util.Map;

/**
 * 提供给 Agent 上下文渲染的短期偏好快照。
 *
 * @param state         Redis Hash 中的当前完整偏好
 * @param recentChanges Redis List 中最近若干次增量变化，按写入时间正序排列
 */
public record ShoppingPreferenceSnapshot(
        ShoppingPreferenceState state,
        List<Map<String, Object>> recentChanges
) {

    public ShoppingPreferenceSnapshot {
        recentChanges = recentChanges == null ? List.of() : List.copyOf(recentChanges);
    }
}
