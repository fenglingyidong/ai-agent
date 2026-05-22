package com.example.ragagent.commerce;

import java.util.ArrayList;
import java.util.List;

public class ShoppingPreferenceState {

    private String category;
    private Integer budgetMin;
    private Integer budgetMax;
    private String brand;
    private String size;
    private String color;
    private String style;
    private String usageScenario;
    private final List<String> negativePreferences = new ArrayList<>();
    private final List<String> lastComparedProductIds = new ArrayList<>();

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getBudgetMin() {
        return budgetMin;
    }

    public void setBudgetMin(Integer budgetMin) {
        this.budgetMin = budgetMin;
    }

    public Integer getBudgetMax() {
        return budgetMax;
    }

    public void setBudgetMax(Integer budgetMax) {
        this.budgetMax = budgetMax;
    }

    public String getBrand() {
        return brand;
    }

    public void setBrand(String brand) {
        this.brand = brand;
    }

    public String getSize() {
        return size;
    }

    public void setSize(String size) {
        this.size = size;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getStyle() {
        return style;
    }

    public void setStyle(String style) {
        this.style = style;
    }

    public String getUsageScenario() {
        return usageScenario;
    }

    public void setUsageScenario(String usageScenario) {
        this.usageScenario = usageScenario;
    }

    public List<String> getNegativePreferences() {
        return negativePreferences;
    }

    public List<String> getLastComparedProductIds() {
        return lastComparedProductIds;
    }
}
