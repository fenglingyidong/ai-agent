package com.example.ragagent.rag;

public final class RagDocumentConstants {

    public static final String PARENT_KEY_PREFIX = "rag:parent:";
    public static final String SOURCE_PARENT_SET_KEY_PREFIX = "rag:source:";
    public static final String CHILD_DOCUMENT_TYPE = "rag-child";
    public static final String METADATA_DOC_TYPE = "docType";
    public static final String METADATA_PARENT_ID = "parentId";
    public static final String METADATA_SOURCE_ID = "sourceId";
    public static final String METADATA_TITLE = "title";
    public static final String METADATA_CHILD_INDEX = "childIndex";
    public static final String METADATA_PARENT_INDEX = "parentIndex";
    public static final String METADATA_DOCUMENT_HASH = "documentHash";
    public static final String METADATA_BM25_TEXT = "bm25Text";
    public static final String DEFAULT_SOURCE_ID = "default-source";

    private RagDocumentConstants() {
    }
}
