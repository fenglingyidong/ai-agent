package com.example.ragagent.controller;

import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import com.example.ragagent.rag.RagDocumentConstants;
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
    public ResponseEntity<RagDocumentImportResponse> importDocument(@RequestBody RagDocumentImportRequest request) {
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
    public ResponseEntity<RagDocumentImportResponse> importProductDocument(@RequestBody ProductDocumentImportRequest request) {
        if (request == null || !StringUtils.hasText(request.title())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title must not be blank");
        }
        return importDocument(request.toRagDocumentImportRequest());
    }

    public record RagDocumentImportRequest(
            String sourceId,
            String title,
            String content,
            String productId,
            String skuId,
            String category,
            String brand,
            BigDecimal price,
            Integer stock,
            String imageUrl
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
            return metadata;
        }

        private void putText(Map<String, Object> metadata, String key, String value) {
            if (StringUtils.hasText(value)) {
                metadata.put(key, value.trim());
            }
        }
    }

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

    public record ProductDocumentImportRequest(
            String productId,
            String skuId,
            String title,
            String brand,
            String category,
            BigDecimal price,
            Integer stock,
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
                    imageUrl
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
