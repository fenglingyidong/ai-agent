package com.example.ragagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

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

    private Stream<String> beanNames(Bean bean) {
        return Stream.concat(Arrays.stream(bean.name()), Arrays.stream(bean.value()));
    }
}
