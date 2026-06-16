package com.example.ragagent.rag.impl;

import com.example.ragagent.config.MilvusVectorStoreConfiguration;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import com.example.ragagent.rag.RagDocumentConstants;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ParentChildDocumentIndexer {

    private static final int CHILD_CHUNK_SIZE = 120;
    private static final int CHILD_CHUNK_OVERLAP = 12;
    private static final int CHILD_CHUNK_STRIDE = CHILD_CHUNK_SIZE - CHILD_CHUNK_OVERLAP;

    private static final List<Character> SPLIT_PUNCTUATION = List.of(
            '.', '!', '?', ';', ',', '。', '，', '；', '！', '？'
    );

    private final VectorStore vectorStore;
    private final ProductChildDocumentWriter childDocumentWriter;
    private final ParentDocumentStore parentDocumentStore;
    private final TextSplitter parentSplitter;
    private final Encoding childChunkEncoding;
    private final FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
    private final ConcurrentMap<String, Object> sourceIndexLocks = new ConcurrentHashMap<>();

    /**
     * 创建索引器，用于切分父子文档并写入父文档存储与向量库。
     */
    @Autowired
    public ParentChildDocumentIndexer(@Qualifier(MilvusVectorStoreConfiguration.PRODUCT_VECTOR_STORE) VectorStore vectorStore,
                                      ParentDocumentStore parentDocumentStore,
                                      ProductChildDocumentWriter childDocumentWriter) {
        this.vectorStore = vectorStore;
        this.childDocumentWriter = childDocumentWriter;
        this.parentDocumentStore = parentDocumentStore;
        this.parentSplitter = TokenTextSplitter.builder()
                .withChunkSize(350)
                .withMinChunkSizeChars(200)
                .withMinChunkLengthToEmbed(20)
                .withMaxNumChunks(10_000)
                .withKeepSeparator(true)
                .withPunctuationMarks(SPLIT_PUNCTUATION)
                .build();
        this.childChunkEncoding = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    }

    public ParentChildDocumentIndexer(VectorStore vectorStore,
                                      ParentDocumentStore parentDocumentStore) {
        this(vectorStore, parentDocumentStore, vectorStore::add);
    }

    /**
     * 索引完整原始文档，并返回生成的父分块 ID。
     */
    public List<String> indexDocument(String sourceId, String title, String documentText) {
        return indexDocumentDetails(sourceId, title, documentText).parentIds();
    }

    /**
     * 索引完整原始文档，并返回父子两层生成的全部 ID。
     */
    public DocumentIndexingResult indexDocumentDetails(String sourceId, String title, String documentText) {
        return indexDocumentDetails(sourceId, title, documentText, Map.of());
    }

    /**
     * 索引完整原始文档，并为父子分块附加商品域元数据。
     */
    public DocumentIndexingResult indexDocumentDetails(String sourceId,
                                                       String title,
                                                       String documentText,
                                                       Map<String, Object> extraMetadata) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        String normalizedDocumentText = normalizeText(documentText);
        if (!StringUtils.hasText(normalizedDocumentText)) {
            return new DocumentIndexingResult(List.of(), List.of());
        }

        String documentHash = calculateHash(normalizedDocumentText);
        synchronized (sourceIndexLock(normalizedSourceId)) {
            overwriteIndexedDocument(normalizedSourceId);

            List<String> parentIds = new ArrayList<>();
            List<String> childIds = new ArrayList<>();

            List<Document> parentDocuments = splitParentDocuments(normalizedSourceId, title, normalizedDocumentText);
            for (int parentIndex = 0; parentIndex < parentDocuments.size(); parentIndex++) {
                Document parentDocument = parentDocuments.get(parentIndex);
                IndexedParentChunk indexedParentChunk = indexParentChunkDetails(
                        normalizedSourceId,
                        title,
                        normalizeText(parentDocument.getText()),
                        parentIndex,
                        documentHash,
                        sanitizeExtraMetadata(extraMetadata)
                );
                parentIds.add(indexedParentChunk.parentId());
                childIds.addAll(indexedParentChunk.childIds());
            }

            return new DocumentIndexingResult(List.copyOf(parentIds), List.copyOf(childIds));
        }
    }

    /**
     * 仅索引单个父分块，并返回生成的 parentId。
     */
    public String indexParentChunk(String sourceId, String title, String parentText) {
        return indexParentChunkDetails(sourceId, title, parentText).parentId();
    }

    /**
     * 仅索引单个父分块，并返回父子两层生成的全部 ID。
     */
    public IndexedParentChunk indexParentChunkDetails(String sourceId, String title, String parentText) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        String normalizedParentText = normalizeText(parentText);
        if (!StringUtils.hasText(normalizedParentText)) {
            throw new IllegalArgumentException("parentText must not be blank");
        }

        synchronized (sourceIndexLock(normalizedSourceId)) {
            overwriteIndexedDocument(normalizedSourceId);
            return indexParentChunkDetails(
                    normalizedSourceId,
                    title,
                    normalizedParentText,
                    0,
                    calculateHash(normalizedParentText)
            );
        }
    }

    /**
     * 保存父分块并将其子分块写入向量索引。
     */
    private IndexedParentChunk indexParentChunkDetails(String sourceId,
                                                       String title,
                                                       String normalizedParentText,
                                                       int parentIndex,
                                                       String documentHash) {
        return indexParentChunkDetails(sourceId, title, normalizedParentText, parentIndex, documentHash, Map.of());
    }

    private IndexedParentChunk indexParentChunkDetails(String sourceId,
                                                       String title,
                                                       String normalizedParentText,
                                                       int parentIndex,
                                                       String documentHash,
                                                       Map<String, Object> extraMetadata) {
        String parentId = buildParentId(sourceId, parentIndex, normalizedParentText);
        Map<String, Object> sanitizedExtraMetadata = sanitizeExtraMetadata(extraMetadata);
        parentDocumentStore.save(
                parentId,
                sourceId,
                title,
                normalizedParentText,
                parentIndex,
                documentHash,
                sanitizedExtraMetadata
        );

        List<Document> childDocuments = buildChildDocuments(
                parentId,
                sourceId,
                title,
                normalizedParentText,
                parentIndex,
                documentHash,
                sanitizedExtraMetadata
        );
        childDocumentWriter.add(childDocuments);

        List<String> childIds = childDocuments.stream()
                .map(Document::getId)
                .toList();
        return new IndexedParentChunk(parentId, childIds);
    }

    /**
     * 使用 Spring AI 分词切分器生成父分块。
     */
    private List<Document> splitParentDocuments(String sourceId, String title, String documentText) {
        Map<String, Object> metadata = baseMetadata(sourceId, title, 0, "");
        Document sourceDocument = Document.builder()
                .text(documentText)
                .metadata(metadata)
                .build();
        return splitWithFallback(parentSplitter, sourceDocument);
    }

    /**
     * 使用 Spring AI 分词切分器从父分块中生成子分块。
     */
    private List<Document> buildChildDocuments(String parentId,
                                               String sourceId,
                                               String title,
                                               String parentText,
                                               int parentIndex,
                                               String documentHash,
                                               Map<String, Object> extraMetadata) {
        List<String> splitChildren = splitChildTextsWithOverlap(parentText);
        List<Document> childDocuments = new ArrayList<>();

        for (int index = 0; index < splitChildren.size(); index++) {
            String childText = normalizeText(splitChildren.get(index));
            if (!StringUtils.hasText(childText)) {
                continue;
            }

            Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata(sourceId, title, parentIndex, documentHash));
            metadata.putAll(extraMetadata);
            metadata.put(RagDocumentConstants.METADATA_DOC_TYPE, RagDocumentConstants.CHILD_DOCUMENT_TYPE);
            metadata.put(RagDocumentConstants.METADATA_PARENT_ID, parentId);
            metadata.put(RagDocumentConstants.METADATA_CHILD_INDEX, index);

            childDocuments.add(Document.builder()
                    .id(parentId + "-child-" + index)
                    .text(childText)
                    .metadata(metadata)
                    .build());
        }

        if (!childDocuments.isEmpty()) {
            return List.copyOf(childDocuments);
        }

        Map<String, Object> fallbackMetadata = new LinkedHashMap<>(baseMetadata(sourceId, title, parentIndex, documentHash));
        fallbackMetadata.putAll(extraMetadata);
        fallbackMetadata.put(RagDocumentConstants.METADATA_DOC_TYPE, RagDocumentConstants.CHILD_DOCUMENT_TYPE);
        fallbackMetadata.put(RagDocumentConstants.METADATA_PARENT_ID, parentId);
        fallbackMetadata.put(RagDocumentConstants.METADATA_CHILD_INDEX, 0);

        return List.of(Document.builder()
                .id(parentId + "-child-0")
                .text(parentText)
                .metadata(fallbackMetadata)
                .build());
    }

    /**
     * 当切分器没有输出任何分块时，回退为原文档本身。
     */
    private List<Document> splitWithFallback(TextSplitter splitter, Document document) {
        List<Document> splitDocuments = splitter.split(document).stream()
                .filter(chunk -> StringUtils.hasText(normalizeText(chunk.getText())))
                .toList();
        return splitDocuments.isEmpty() ? List.of(document) : List.copyOf(splitDocuments);
    }

    /**
     * 按 token 滑窗切分 child chunk，固定使用 chunkSize=120、overlap=12、stride=108。
     */
    private List<String> splitChildTextsWithOverlap(String parentText) {
        String normalizedParentText = normalizeText(parentText);
        if (!StringUtils.hasText(normalizedParentText)) {
            return List.of();
        }

        IntArrayList encodedTokens = childChunkEncoding.encode(normalizedParentText);
        if (encodedTokens.isEmpty()) {
            return List.of(normalizedParentText);
        }

        List<String> childTexts = new ArrayList<>();
        for (int start = 0; start < encodedTokens.size(); start += CHILD_CHUNK_STRIDE) {
            int end = Math.min(start + CHILD_CHUNK_SIZE, encodedTokens.size());
            if (start >= end) {
                break;
            }

            String childText = normalizeText(decodeTokenWindow(encodedTokens, start, end));
            if (StringUtils.hasText(childText)) {
                childTexts.add(childText);
            }

            if (end >= encodedTokens.size()) {
                break;
            }
        }

        return childTexts.isEmpty() ? List.of(normalizedParentText) : List.copyOf(childTexts);
    }

    /**
     * 将指定 token 窗口解码回文本，供 child chunk 入库使用。
     */
    private String decodeTokenWindow(IntArrayList encodedTokens, int startInclusive, int endExclusive) {
        IntArrayList windowTokens = new IntArrayList(endExclusive - startInclusive);
        for (int index = startInclusive; index < endExclusive; index++) {
            windowTokens.add(encodedTokens.get(index));
        }
        return childChunkEncoding.decode(windowTokens);
    }

    /**
     * 构建父子分块共用的来源元数据。
     */
    private Map<String, Object> baseMetadata(String sourceId, String title, int parentIndex, String documentHash) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RagDocumentConstants.METADATA_SOURCE_ID, normalizeSourceId(sourceId));
        metadata.put(RagDocumentConstants.METADATA_TITLE, title == null ? "" : title.trim());
        metadata.put(RagDocumentConstants.METADATA_PARENT_INDEX, parentIndex);
        metadata.put(RagDocumentConstants.METADATA_DOCUMENT_HASH, documentHash == null ? "" : documentHash);
        return metadata;
    }

    private Map<String, Object> sanitizeExtraMetadata(Map<String, Object> extraMetadata) {
        if (extraMetadata == null || extraMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        extraMetadata.forEach((key, value) -> {
            if (StringUtils.hasText(key) && value != null) {
                sanitized.put(key.trim(), value);
            }
        });
        return Map.copyOf(sanitized);
    }

    /**
     * 导入同一 sourceId 时先删除旧索引，避免重复数据累积。
     */
    private void overwriteIndexedDocument(String sourceId) {
        String normalizedSourceId = normalizeSourceId(sourceId);
        vectorStore.delete(filterExpressionBuilder
                .eq(RagDocumentConstants.METADATA_SOURCE_ID, normalizedSourceId)
                .build());
        parentDocumentStore.deleteBySourceId(normalizedSourceId);
    }

    private Object sourceIndexLock(String normalizedSourceId) {
        return sourceIndexLocks.computeIfAbsent(normalizedSourceId, ignored -> new Object());
    }

    /**
     * 规范化输入文本，统一换行和前后空白。
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * 规范化 sourceId，确保元数据与父文档存储使用同一来源标识。
     */
    private String normalizeSourceId(String sourceId) {
        return StringUtils.hasText(sourceId) ? sourceId.trim() : RagDocumentConstants.DEFAULT_SOURCE_ID;
    }

    /**
     * 使用 sourceId + parentIndex + hash 生成稳定的父分块 ID。
     */
    private String buildParentId(String sourceId, int parentIndex, String parentText) {
        return normalizeSourceId(sourceId) + "-" + parentIndex + "-" + calculateHash(parentText);
    }

    /**
     * 计算文本的 SHA-256，用于构建稳定且内容敏感的标识。
     */
    private String calculateHash(String value) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(normalizeText(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record DocumentIndexingResult(
            List<String> parentIds,
            List<String> childIds
    ) {
    }

    public record IndexedParentChunk(
            String parentId,
            List<String> childIds
    ) {
    }
}
