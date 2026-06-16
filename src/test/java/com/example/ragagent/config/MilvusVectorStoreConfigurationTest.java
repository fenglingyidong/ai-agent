package com.example.ragagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import io.milvus.v2.client.RetryConfig;

import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MilvusVectorStoreConfigurationTest {

    @Test
    void defaultV2ClientShouldRemainPrimary() throws NoSuchMethodException {
        Method method = MilvusVectorStoreConfiguration.class.getMethod(
                "milvusClientV2",
                AppVectorProperties.class
        );

        assertTrue(method.isAnnotationPresent(Primary.class));
    }

    @Test
    void bm25V2ClientShouldUseDedicatedBeanName() throws NoSuchMethodException {
        Method method = MilvusVectorStoreConfiguration.class.getMethod(
                "bm25MilvusClientV2",
                AppVectorProperties.class,
                RagRetrievalProperties.class
        );
        Bean bean = method.getAnnotation(Bean.class);

        assertTrue(beanNames(bean).anyMatch(MilvusVectorStoreConfiguration.BM25_MILVUS_CLIENT::equals));
    }

    @Test
    void bm25RetryConfigShouldStayWithinBm25RpcDeadline() {
        RagRetrievalProperties properties = new RagRetrievalProperties();
        properties.setBm25RpcDeadlineMs(2_000);

        RetryConfig retryConfig = MilvusVectorStoreConfiguration.bm25RetryConfig(properties);

        assertTrue(retryConfig.getMaxRetryTimeoutMs() <= properties.getBm25RpcDeadlineMs());
        assertTrue(retryConfig.getMaxRetryTimes() <= 2);
    }

    @Test
    void bm25TextSearchShouldRequireUtf8JvmCharset() {
        assertDoesNotThrow(() -> MilvusVectorStoreConfiguration.requireUtf8JvmCharset(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> MilvusVectorStoreConfiguration.requireUtf8JvmCharset(Charset.forName("GBK")));
    }

    private Stream<String> beanNames(Bean bean) {
        return Stream.concat(Arrays.stream(bean.name()), Arrays.stream(bean.value()));
    }
}
