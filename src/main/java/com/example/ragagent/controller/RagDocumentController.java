package com.example.ragagent.controller;

import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/rag/documents")
public class RagDocumentController {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentController.class);

    private final ParentChildDocumentIndexer parentChildDocumentIndexer;

    /**
     * 创建文档导入控制器，用于手动执行 RAG 入库。
     */
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
                    request.content()
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
                indexingResult.childIds()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public record RagDocumentImportRequest(
            String sourceId,
            String title,
            String content
    ) {
    }

    public record RagDocumentImportResponse(
            String sourceId,
            String title,
            int parentCount,
            int childCount,
            List<String> parentIds,
            List<String> childIds
    ) {
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
    }
}
