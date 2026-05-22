package com.example.ragagent.rag.impl;

import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.ChineseTextSegmenter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisBm25ChildChunkRetriever {

    private static final int BM25_TOP_K = 8;
    private static final String CONTENT_FIELD = RedisVectorStore.DEFAULT_CONTENT_FIELD_NAME;

    @Autowired
    private JedisPooled jedisPooled;

    @Autowired
    private RedisVectorStoreProperties properties;

    @Autowired
    private ChineseTextSegmenter chineseTextSegmenter;

    private String indexName;
    private String prefix;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.indexName = StringUtils.hasText(properties.getIndexName())
                ? properties.getIndexName()
                : RedisVectorStore.DEFAULT_INDEX_NAME;
        this.prefix = StringUtils.hasText(properties.getPrefix())
                ? properties.getPrefix()
                : RedisVectorStore.DEFAULT_PREFIX;
    }

    public RedisBm25ChildChunkRetriever() {
    }

    public RedisBm25ChildChunkRetriever(JedisPooled jedisPooled,
                                        RedisVectorStoreProperties properties,
                                        ChineseTextSegmenter chineseTextSegmenter) {
        this.jedisPooled = jedisPooled;
        this.properties = properties;
        this.chineseTextSegmenter = chineseTextSegmenter;
        init();
    }

    /**
     * 使用 Redis 全文检索召回 child chunk，结果顺序直接保留 BM25 排名。
     */
    public List<Document> retrieve(String query) {
        String normalizedQuery = normalizeText(query);
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }

        Query redisQuery = new Query(buildRedisQuery(normalizedQuery))
                .setLanguage("chinese")
                .limit(0, BM25_TOP_K)
                .returnFields(
                        CONTENT_FIELD,
                        RagDocumentConstants.METADATA_DOC_TYPE,
                        RagDocumentConstants.METADATA_PARENT_ID,
                        RagDocumentConstants.METADATA_SOURCE_ID,
                        RagDocumentConstants.METADATA_TITLE,
                        RagDocumentConstants.METADATA_CHILD_INDEX,
                        RagDocumentConstants.METADATA_PARENT_INDEX,
                        RagDocumentConstants.METADATA_DOCUMENT_HASH
                );

        SearchResult searchResult = jedisPooled.ftSearch(indexName, redisQuery);
        if (searchResult == null || searchResult.getDocuments() == null) {
            return List.of();
        }

        return searchResult.getDocuments().stream()
                .map(this::toSpringDocument)
                .filter(this::isChildDocument)
                .toList();
    }

    /**
     * 构造 FT.SEARCH 查询，固定过滤 child chunk，并将用户问题作为全文检索条件。
     */
    private String buildRedisQuery(String query) {
        return "@%s:{%s} @%s:%s".formatted(
                RagDocumentConstants.METADATA_DOC_TYPE,
                escapeTagValue(RagDocumentConstants.CHILD_DOCUMENT_TYPE),
                RagDocumentConstants.METADATA_BM25_TEXT,
                buildFullTextClause(query)
        );
    }

    /**
     * 将 RediSearch 返回结果转换为 Spring AI Document。
     */
    private Document toSpringDocument(redis.clients.jedis.search.Document redisDocument) {
        String id = stripPrefix(redisDocument.getId());
        Map<String, Object> metadata = new LinkedHashMap<>();
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_DOC_TYPE);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_PARENT_ID);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_SOURCE_ID);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_TITLE);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_CHILD_INDEX);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_PARENT_INDEX);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_DOCUMENT_HASH);
        copyFieldIfPresent(redisDocument, metadata, RagDocumentConstants.METADATA_BM25_TEXT);

        return Document.builder()
                .id(id)
                .text(readStringField(redisDocument, CONTENT_FIELD))
                .metadata(metadata)
                .build();
    }

    /**
     * 将查询文本转成 RediSearch 可接受的全文查询片段。
     */
    private String buildFullTextClause(String query) {
        String segmentedQuery = chineseTextSegmenter.segmentForSearch(query);
        String sanitizedQuery = sanitizeFullTextQuery(segmentedQuery);
        if (!StringUtils.hasText(sanitizedQuery)) {
            return "";
        }

        String[] tokens = StringUtils.tokenizeToStringArray(sanitizedQuery, " \n\t");
        if (tokens == null || tokens.length == 0) {
            return escapeQueryToken(sanitizedQuery);
        }

        return "(" + String.join("|", Arrays.stream(tokens)
                .map(this::escapeQueryToken)
                .toList()) + ")";
    }

    /**
     * RediSearch 查询语法对符号比较敏感；中文评测集里常见的全角标点会触发 syntax error。
     * 这里先保留中文、英文、数字和空白，其他字符统一替换为空格。
     */
    private String sanitizeFullTextQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        return query.replaceAll("[^\\p{IsHan}a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * RediSearch TAG 字段的值需要转义特殊字符。
     */
    private String escapeTagValue(String value) {
        return value.replaceAll("([\\\\{}\\[\\]|,<>\"':;!@#$%^&*()\\-+=~/? ])", "\\\\$1");
    }

    /**
     * RediSearch 全文查询中的特殊字符需要转义，避免语法冲突。
     */
    private String escapeQueryToken(String token) {
        return token.replaceAll("([\\\\@{}\\[\\]\"'|!()~:*?&><=\\-])", "\\\\$1");
    }

    /**
     * 去掉 RedisVectorStore 写入时附加的 key 前缀，保持和向量检索返回的文档 ID 一致。
     */
    private String stripPrefix(String value) {
        if (value == null) {
            return "";
        }
        return StringUtils.hasText(prefix) && value.startsWith(prefix)
                ? value.substring(prefix.length())
                : value;
    }

    /**
     * 安全读取 RediSearch 文档字段。
     */
    private String readStringField(redis.clients.jedis.search.Document redisDocument, String fieldName) {
        if (redisDocument == null || !redisDocument.hasProperty(fieldName)) {
            return "";
        }
        String value = redisDocument.getString(fieldName);
        return value == null ? "" : value;
    }

    /**
     * 将字段复制到元数据中，方便后续按 parentId 回查。
     */
    private void copyFieldIfPresent(redis.clients.jedis.search.Document redisDocument,
                                    Map<String, Object> metadata,
                                    String fieldName) {
        if (redisDocument == null || metadata == null || !redisDocument.hasProperty(fieldName)) {
            return;
        }
        Object value = redisDocument.get(fieldName);
        if (value != null) {
            metadata.put(fieldName, value);
        }
    }

    /**
     * 判断全文检索命中的文档是否属于 RAG child chunk。
     */
    private boolean isChildDocument(Document document) {
        Object value = document.getMetadata().get(RagDocumentConstants.METADATA_DOC_TYPE);
        return RagDocumentConstants.CHILD_DOCUMENT_TYPE.equals(value == null ? "" : value.toString());
    }

    /**
     * 规范化检索输入文本。
     */
    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }
}
