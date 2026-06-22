package com.example.ragagent.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * RAG 召回配置，覆盖 dense、BM25、并行检索和父文档截断策略。
 */
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
    private int bm25RpcDeadlineMs = 2_000;

    @Min(1)
    private int bm25FutureTimeoutMs = 2_500;

    @Min(1)
    private int parallelExecutorCoreSize = 4;

    @Min(1)
    private int parallelExecutorMaxSize = 8;

    @Min(1)
    private int parallelExecutorQueueCapacity = 32;

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

    /**
     * 校验参与父文档聚合的候选窗口不能小于保底保留数量。
     */
    @AssertTrue(message = "maxChildResultsToConsider must be greater than or equal to minChildResultsToKeep")
    public boolean isChildResultWindowValid() {
        return maxChildResultsToConsider >= minChildResultsToKeep;
    }

    /**
     * 校验异步等待时间覆盖 Milvus BM25 RPC 截止时间。
     */
    @AssertTrue(message = "bm25FutureTimeoutMs must be greater than or equal to bm25RpcDeadlineMs")
    public boolean isBm25TimeoutWindowValid() {
        return bm25FutureTimeoutMs >= bm25RpcDeadlineMs;
    }

    /**
     * 校验 RAG 并行线程池最大线程数不小于核心线程数。
     */
    @AssertTrue(message = "parallelExecutorMaxSize must be greater than or equal to parallelExecutorCoreSize")
    public boolean isParallelExecutorSizeValid() {
        return parallelExecutorMaxSize >= parallelExecutorCoreSize;
    }
}
