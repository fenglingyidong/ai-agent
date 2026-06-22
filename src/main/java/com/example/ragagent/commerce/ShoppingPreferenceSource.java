package com.example.ragagent.commerce;

/**
 * 标记导购偏好字段的来源，供合并和置信度判断使用。
 */
public enum ShoppingPreferenceSource {
    USER_EXPLICIT,
    ROUTER_SLOT,
    MODEL_TOOL,
    VISUAL_CONTEXT
}
