package com.example.ragagent.rag.impl;

import org.springframework.ai.document.Document;

import java.util.List;

public interface ProductChildDocumentWriter {

    void add(List<Document> documents);
}
