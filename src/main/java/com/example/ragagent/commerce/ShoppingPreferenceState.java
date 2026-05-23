package com.example.ragagent.commerce;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ShoppingPreferenceState {

    private String category;
    private Integer budgetMin;
    private Integer budgetMax;
    private String brand;
    private String size;
    private String color;
    private String style;
    private String usageScenario;
}
