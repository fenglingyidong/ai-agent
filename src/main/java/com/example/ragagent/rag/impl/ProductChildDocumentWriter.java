package com.example.ragagent.rag.impl;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 商品子分块写入器抽象，用于在索引流程中替换不同向量库写入实现。
 */
public interface ProductChildDocumentWriter {

    /**
     * 批量写入商品子分块文档。
     */
    void add(List<Document> documents);
}
