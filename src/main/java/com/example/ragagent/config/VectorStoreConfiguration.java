package com.example.ragagent.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.JedisPooled;

@Configuration
public class VectorStoreConfiguration {

    private static final String DOC_TYPE = "docType";
    private static final String PARENT_ID = "parentId";
    private static final String SOURCE_ID = "sourceId";
    private static final String TITLE = "title";
    private static final String CHILD_INDEX = "childIndex";
    private static final String PARENT_INDEX = "parentIndex";
    private static final String DOCUMENT_HASH = "documentHash";
    private static final String BM25_TEXT = "bm25Text";
    private static final String PRODUCT_ID = "productId";
    private static final String SKU_ID = "skuId";
    private static final String CATEGORY = "category";
    private static final String BRAND = "brand";
    private static final String PRICE = "price";
    private static final String STOCK = "stock";
    private static final String IMAGE_URL = "imageUrl";

    @Bean
    public JedisPooled jedisPooled(JedisConnectionFactory jedisConnectionFactory) {
        return createJedisPooled(jedisConnectionFactory);
    }

    @Bean
    @Primary
    public RedisVectorStore redisVectorStore(EmbeddingModel embeddingModel,
                                             RedisVectorStoreProperties properties,
                                             JedisPooled jedisPooled,
                                             ObjectProvider<ObservationRegistry> observationRegistry,
                                             ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
                                             ObjectProvider<BatchingStrategy> batchingStrategy) {
        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .initializeSchema(properties.isInitializeSchema())
                .observationRegistry(observationRegistry.getIfUnique(ObservationRegistry::create))
                .customObservationConvention(customObservationConvention.getIfAvailable(() -> null))
                .batchingStrategy(batchingStrategy.getIfAvailable(TokenCountBatchingStrategy::new))
                .indexName(properties.getIndexName())
                .prefix(properties.getPrefix())
                .metadataFields(
                        RedisVectorStore.MetadataField.tag(DOC_TYPE),
                        RedisVectorStore.MetadataField.tag(PARENT_ID),
                        RedisVectorStore.MetadataField.tag(SOURCE_ID),
                        RedisVectorStore.MetadataField.text(TITLE),
                        RedisVectorStore.MetadataField.numeric(CHILD_INDEX),
                        RedisVectorStore.MetadataField.numeric(PARENT_INDEX),
                        RedisVectorStore.MetadataField.tag(DOCUMENT_HASH),
                        RedisVectorStore.MetadataField.text(BM25_TEXT),
                        RedisVectorStore.MetadataField.tag(PRODUCT_ID),
                        RedisVectorStore.MetadataField.tag(SKU_ID),
                        RedisVectorStore.MetadataField.tag(CATEGORY),
                        RedisVectorStore.MetadataField.tag(BRAND),
                        RedisVectorStore.MetadataField.numeric(PRICE),
                        RedisVectorStore.MetadataField.numeric(STOCK),
                        RedisVectorStore.MetadataField.text(IMAGE_URL),
                        RedisVectorStore.MetadataField.tag("userId"),
                        RedisVectorStore.MetadataField.tag("conversationKey"),
                        RedisVectorStore.MetadataField.tag("memoryType"),
                        RedisVectorStore.MetadataField.numeric("fromSequence"),
                        RedisVectorStore.MetadataField.numeric("toSequence"),
                        RedisVectorStore.MetadataField.numeric("fromTimestamp"),
                        RedisVectorStore.MetadataField.numeric("toTimestamp"),
                        RedisVectorStore.MetadataField.numeric("summarizedAt")
                )
                .build();
    }

    private JedisPooled createJedisPooled(JedisConnectionFactory jedisConnectionFactory) {
        String password = jedisConnectionFactory.getPassword();
        String clientName = jedisConnectionFactory.getClientName();
        int database = jedisConnectionFactory.getDatabase();
        int timeout = jedisConnectionFactory.getTimeout();
        boolean useSsl = jedisConnectionFactory.isUseSsl();

        return new JedisPooled(
                jedisConnectionFactory.getPoolConfig(),
                jedisConnectionFactory.getHostName(),
                jedisConnectionFactory.getPort(),
                timeout,
                timeout,
                password,
                database,
                clientName,
                useSsl,
                null,
                null,
                null
        );
    }
}
