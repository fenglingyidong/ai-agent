package com.example.ragagent.controller;

import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import com.example.ragagent.rag.RagDocumentConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供 RAG 文档和商品文档导入接口，把外部内容写入父子分块索引。
 */
@RestController
@RequestMapping("/api/rag/documents")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    @Autowired
    private ParentChildDocumentIndexer parentChildDocumentIndexer;

    public RagDocumentController() {
    }

    public RagDocumentController(ParentChildDocumentIndexer parentChildDocumentIndexer) {
        this.parentChildDocumentIndexer = parentChildDocumentIndexer;
    }

    /**
     * 将原始文档导入父子分块 RAG 索引，并返回生成的父分块 ID。
     */
    @PostMapping("/import")
    public ResponseEntity<RagDocumentImportResponse> importDocument(@Valid @RequestBody RagDocumentImportRequest request) {
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content must not be blank");
        }

        ParentChildDocumentIndexer.DocumentIndexingResult indexingResult;
        try {
            indexingResult = parentChildDocumentIndexer.indexDocumentDetails(
                    request.sourceId(),
                    request.title(),
                    request.content(),
                    request.toMetadata()
            );
        }
        catch (RuntimeException ex) {
            String rootCauseMessage = rootCauseMessage(ex);
            log.error("Failed to import RAG document. sourceId={}, title={}", request.sourceId(), request.title(), ex);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to import RAG document: " + rootCauseMessage,
                    ex
            );
        }

        RagDocumentImportResponse response = new RagDocumentImportResponse(
                request.sourceId(),
                request.title(),
                indexingResult.parentIds().size(),
                indexingResult.childIds().size(),
                indexingResult.parentIds(),
                indexingResult.childIds(),
                request.toMetadata()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * 将商品结构化信息转换为导购知识文档后导入父子分块 RAG 索引。
     */
    @PostMapping("/products/import")
    public ResponseEntity<RagDocumentImportResponse> importProductDocument(@Valid @RequestBody ProductDocumentImportRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        return importDocument(request.toRagDocumentImportRequest());
    }

    /**
     * 通用 RAG 文档导入请求，包含原文和可用于召回过滤的商品元数据。
     */
    public record RagDocumentImportRequest(
            @Size(max = 128)
            String sourceId,
            @Size(max = 256)
            String title,
            @NotBlank
            String content,
            @Size(max = 128)
            String productId,
            @Size(max = 128)
            String skuId,
            @Size(max = 128)
            String category,
            @Size(max = 128)
            String brand,
            @PositiveOrZero
            BigDecimal price,
            @PositiveOrZero
            Integer stock,
            @Size(max = 1024)
            String imageUrl,
            Map<String, String> attributes
    ) {
        private Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new LinkedHashMap<>();
            putText(metadata, RagDocumentConstants.METADATA_PRODUCT_ID, productId);
            putText(metadata, RagDocumentConstants.METADATA_SKU_ID, skuId);
            putText(metadata, RagDocumentConstants.METADATA_CATEGORY, category);
            putText(metadata, RagDocumentConstants.METADATA_BRAND, brand);
            putText(metadata, RagDocumentConstants.METADATA_IMAGE_URL, imageUrl);
            if (price != null) {
                metadata.put(RagDocumentConstants.METADATA_PRICE, price);
            }
            if (stock != null) {
                metadata.put(RagDocumentConstants.METADATA_STOCK, stock);
            }
            Map<String, String> sanitizedAttributes = sanitizeAttributes(attributes);
            if (!sanitizedAttributes.isEmpty()) {
                metadata.put(RagDocumentConstants.METADATA_ATTRIBUTES, sanitizedAttributes);
            }
            return metadata;
        }

        private void putText(Map<String, Object> metadata, String key, String value) {
            if (StringUtils.hasText(value)) {
                metadata.put(key, value.trim());
            }
        }

        private Map<String, String> sanitizeAttributes(Map<String, String> sourceAttributes) {
            if (sourceAttributes == null || sourceAttributes.isEmpty()) {
                return Map.of();
            }
            Map<String, String> sanitized = new LinkedHashMap<>();
            sourceAttributes.forEach((key, value) -> {
                if (StringUtils.hasText(key) && StringUtils.hasText(value)) {
                    sanitized.put(key.trim(), value.trim());
                }
            });
            return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
        }
    }

    /**
     * 文档导入结果，返回生成的父子分块 ID 和写入索引的元数据。
     */
    public record RagDocumentImportResponse(
            String sourceId,
            String title,
            int parentCount,
            int childCount,
            List<String> parentIds,
            List<String> childIds,
            Map<String, Object> metadata
    ) {
    }

    /**
     * 商品结构化导入请求，会被转换成适合 RAG 检索的商品知识文本。
     */
    public record ProductDocumentImportRequest(
            @Size(max = 128)
            String productId,
            @Size(max = 128)
            String skuId,
            @NotBlank
            @Size(max = 256)
            String title,
            @Size(max = 128)
            String brand,
            @Size(max = 128)
            String category,
            @PositiveOrZero
            BigDecimal price,
            @PositiveOrZero
            Integer stock,
            @Size(max = 1024)
            String imageUrl,
            String description,
            String reviewSummary,
            String guideText,
            Map<String, String> attributes
    ) {
        private RagDocumentImportRequest toRagDocumentImportRequest() {
            String normalizedProductId = StringUtils.hasText(productId) ? productId.trim() : "product";
            String normalizedSkuId = StringUtils.hasText(skuId) ? skuId.trim() : normalizedProductId;
            return new RagDocumentImportRequest(
                    "product-" + normalizedProductId,
                    title,
                    buildContent(),
                    normalizedProductId,
                    normalizedSkuId,
                    category,
                    brand,
                    price,
                    stock,
                    imageUrl,
                    attributes
            );
        }

        private String buildContent() {
            StringBuilder builder = new StringBuilder();
            appendLine(builder, "商品标题", title);
            appendLine(builder, "品牌", brand);
            appendLine(builder, "类目", category);
            if (price != null) {
                appendLine(builder, "价格", price + " 元");
            }
            if (stock != null) {
                appendLine(builder, "库存", stock + " 件");
            }
            appendLine(builder, "商品描述", description);
            appendLine(builder, "评价摘要", reviewSummary);
            appendLine(builder, "导购话术", guideText);
            if (attributes != null && !attributes.isEmpty()) {
                builder.append("规格参数：").append(attributes).append(System.lineSeparator());
            }
            return builder.toString().trim();
        }

        private void appendLine(StringBuilder builder, String label, String value) {
            if (StringUtils.hasText(value)) {
                builder.append(label).append("：").append(value.trim()).append(System.lineSeparator());
            }
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
    }
}
