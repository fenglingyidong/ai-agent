package com.example.ragagent.config;

import io.micrometer.observation.ObservationRegistry;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class MilvusVectorStoreConfiguration {

    public static final String PRODUCT_VECTOR_STORE = "productVectorStore";
    public static final String MEMORY_VECTOR_STORE = "memoryVectorStore";

    public static final String SPARSE_VECTOR_FIELD = "sparse_vector";
    private static final String PRODUCT_BM25_FUNCTION = "product_bm25_function";
    private static final Map<String, Object> PRODUCT_CONTENT_ANALYZER_PARAMS = Map.of("type", "chinese");

    @Bean(destroyMethod = "")
    public MilvusServiceClient milvusServiceClient(AppVectorProperties properties) {
        AppVectorProperties.Milvus milvus = properties.getMilvus();
        ConnectParam.Builder builder = ConnectParam.newBuilder()
                .withDatabaseName(milvus.getDatabaseName())
                .withSecure(milvus.isSecure())
                .withConnectTimeout(10, TimeUnit.SECONDS)
                .withRpcDeadline(30, TimeUnit.SECONDS);
        if (StringUtils.hasText(milvus.getUri())) {
            builder.withUri(milvus.getUri().trim());
        }
        else {
            builder.withHost(milvus.getHost()).withPort(milvus.getPort());
        }
        if (StringUtils.hasText(milvus.getToken())) {
            builder.withToken(milvus.getToken().trim());
        }
        else if (StringUtils.hasText(milvus.getUsername()) || StringUtils.hasText(milvus.getPassword())) {
            builder.withAuthorization(nullToBlank(milvus.getUsername()), nullToBlank(milvus.getPassword()));
        }
        return new MilvusServiceClient(builder.build());
    }

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClientV2(AppVectorProperties properties) {
        AppVectorProperties.Milvus milvus = properties.getMilvus();
        ConnectConfig.ConnectConfigBuilder<?, ?> builder = ConnectConfig.builder()
                .uri(resolveMilvusUri(milvus))
                .dbName(milvus.getDatabaseName())
                .secure(milvus.isSecure())
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(30_000);
        if (StringUtils.hasText(milvus.getToken())) {
            builder.token(milvus.getToken().trim());
        }
        else {
            if (StringUtils.hasText(milvus.getUsername())) {
                builder.username(milvus.getUsername().trim());
            }
            if (StringUtils.hasText(milvus.getPassword())) {
                builder.password(milvus.getPassword());
            }
        }
        return new MilvusClientV2(builder.build());
    }

    @Bean(PRODUCT_VECTOR_STORE)
    public MilvusVectorStore productVectorStore(MilvusServiceClient milvusServiceClient,
                                                EmbeddingModel embeddingModel,
                                                AppVectorProperties properties,
                                                ObjectProvider<ObservationRegistry> observationRegistry,
                                                ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
                                                ObjectProvider<BatchingStrategy> batchingStrategy) {
        boolean frameworkCreatesSchema = properties.getMilvus().isInitializeSchema()
                && !properties.getProduct().isBm25Enabled();
        return baseBuilder(milvusServiceClient, embeddingModel, properties, observationRegistry,
                customObservationConvention, batchingStrategy)
                .collectionName(properties.getProduct().getCollectionName())
                .initializeSchema(frameworkCreatesSchema)
                .build();
    }

    @Bean(MEMORY_VECTOR_STORE)
    public MilvusVectorStore memoryVectorStore(MilvusServiceClient milvusServiceClient,
                                               EmbeddingModel embeddingModel,
                                               AppVectorProperties properties,
                                               ObjectProvider<ObservationRegistry> observationRegistry,
                                               ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
                                               ObjectProvider<BatchingStrategy> batchingStrategy) {
        return baseBuilder(milvusServiceClient, embeddingModel, properties, observationRegistry,
                customObservationConvention, batchingStrategy)
                .collectionName(properties.getMemory().getCollectionName())
                .initializeSchema(properties.getMilvus().isInitializeSchema())
                .build();
    }

    @Bean
    public SmartInitializingSingleton productBm25SchemaInitializer(MilvusClientV2 milvusClientV2,
                                                                   AppVectorProperties properties) {
        return () -> {
            if (!properties.getMilvus().isInitializeSchema() || !properties.getProduct().isBm25Enabled()) {
                return;
            }
            String collectionName = properties.getProduct().getCollectionName();
            Boolean exists = milvusClientV2.hasCollection(HasCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
            if (Boolean.TRUE.equals(exists)) {
                loadCollection(milvusClientV2, collectionName);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = milvusClientV2.createSchema();
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusVectorStore.DOC_ID_FIELD_NAME)
                    .dataType(DataType.VarChar)
                    .maxLength(512)
                    .isPrimaryKey(true)
                    .autoID(false)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusVectorStore.CONTENT_FIELD_NAME)
                    .dataType(DataType.VarChar)
                    .maxLength(8192)
                    .enableAnalyzer(true)
                    .analyzerParams(PRODUCT_CONTENT_ANALYZER_PARAMS)
                    .enableMatch(true)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusVectorStore.METADATA_FIELD_NAME)
                    .dataType(DataType.JSON)
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(MilvusVectorStore.EMBEDDING_FIELD_NAME)
                    .dataType(DataType.FloatVector)
                    .dimension(properties.getMilvus().getEmbeddingDimension())
                    .build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(SPARSE_VECTOR_FIELD)
                    .dataType(DataType.SparseFloatVector)
                    .build());
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .name(PRODUCT_BM25_FUNCTION)
                    .functionType(FunctionType.BM25)
                    .inputFieldNames(List.of(MilvusVectorStore.CONTENT_FIELD_NAME))
                    .outputFieldNames(List.of(SPARSE_VECTOR_FIELD))
                    .build());

            milvusClientV2.createCollection(CreateCollectionReq.builder()
                    .collectionName(collectionName)
                    .collectionSchema(schema)
                    .indexParams(List.of(
                            IndexParam.builder()
                                    .fieldName(MilvusVectorStore.EMBEDDING_FIELD_NAME)
                                    .indexName("product_dense_index")
                                    .indexType(IndexParam.IndexType.AUTOINDEX)
                                    .metricType(toV2MetricType(properties.getMilvus().getMetricType()))
                                    .build(),
                            IndexParam.builder()
                                    .fieldName(SPARSE_VECTOR_FIELD)
                                    .indexName("product_sparse_bm25_index")
                                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                    .metricType(IndexParam.MetricType.BM25)
                                    .extraParams(Map.of("drop_ratio_build", 0.0))
                                    .build()
                    ))
                    .build());
            loadCollection(milvusClientV2, collectionName);
        };
    }

    private MilvusVectorStore.Builder baseBuilder(MilvusServiceClient milvusServiceClient,
                                                  EmbeddingModel embeddingModel,
                                                  AppVectorProperties properties,
                                                  ObjectProvider<ObservationRegistry> observationRegistry,
                                                  ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
                                                  ObjectProvider<BatchingStrategy> batchingStrategy) {
        AppVectorProperties.Milvus milvus = properties.getMilvus();
        return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .databaseName(milvus.getDatabaseName())
                .embeddingDimension(milvus.getEmbeddingDimension())
                .indexType(IndexType.valueOf(milvus.getIndexType()))
                .metricType(MetricType.valueOf(milvus.getMetricType()))
                .indexParameters(milvus.getIndexParameters())
                .batchingStrategy(batchingStrategy.getIfAvailable(TokenCountBatchingStrategy::new))
                .observationRegistry(observationRegistry.getIfUnique(ObservationRegistry::create))
                .customObservationConvention(customObservationConvention.getIfAvailable(() -> null));
    }

    private IndexParam.MetricType toV2MetricType(String metricType) {
        return IndexParam.MetricType.valueOf(metricType);
    }

    private String resolveMilvusUri(AppVectorProperties.Milvus milvus) {
        if (StringUtils.hasText(milvus.getUri())) {
            return milvus.getUri().trim();
        }
        return "http://" + milvus.getHost() + ":" + milvus.getPort();
    }

    private void loadCollection(MilvusClientV2 milvusClientV2, String collectionName) {
        milvusClientV2.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .sync(true)
                .build());
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
