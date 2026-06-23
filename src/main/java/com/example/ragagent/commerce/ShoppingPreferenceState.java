package com.example.ragagent.commerce;

import lombok.Getter;
import lombok.Setter;

/**
 * 当前会话内沉淀的短期导购偏好完整状态。
 */
@Getter
@Setter
public class ShoppingPreferenceState {

    private String category;
    private Integer budget;
    private String brand;
    private String size;
    private String color;
    private String style;
    private String usageScenario;
    private String source;
    private Double confidence;
    private Long updatedAtEpochMillis;
    private Long updatedTurnNo;
}
