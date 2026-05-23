package com.example.ragagent;

import io.milvus.client.MilvusServiceClient;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "app.vector.milvus.initialize-schema=false")
class RagAgentApplicationTests {

    @MockitoBean(name = "milvusServiceClient")
    private MilvusServiceClient milvusServiceClient;

    @MockitoBean(name = "milvusClientV2")
    private MilvusClientV2 milvusClientV2;

    @MockitoBean(name = "productVectorStore")
    private VectorStore productVectorStore;

    @MockitoBean(name = "memoryVectorStore")
    private VectorStore memoryVectorStore;

    @Test
    void contextLoads() {
    }

}
