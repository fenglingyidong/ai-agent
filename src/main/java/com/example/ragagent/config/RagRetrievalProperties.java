package com.example.ragagent.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.rag.retrieval")
public class RagRetrievalProperties {

    @Min(1)
    private int denseChildTopK = 24;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double denseSimilarityThreshold = 0.2d;

    @Min(1)
    private int denseFallbackTopK = 32;

    @Min(1)
    private int bm25ChildTopK = 8;

    @Min(1)
    private int rrfK = 60;

    @Min(1)
    private int minChildResultsToKeep = 4;

    @Min(1)
    private int maxChildResultsToConsider = 12;

    @Min(1)
    private int maxParentResults = 6;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minNormalizedGapToTruncate = 0.0d;

    @AssertTrue(message = "maxChildResultsToConsider must be greater than or equal to minChildResultsToKeep")
    public boolean isChildResultWindowValid() {
        return maxChildResultsToConsider >= minChildResultsToKeep;
    }
}
