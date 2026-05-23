package com.example.ragagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Validated
@ConfigurationProperties(prefix = "app.vector")
public class AppVectorProperties {

    @Valid
    private final Milvus milvus = new Milvus();

    @Valid
    private final Product product = new Product();

    @Valid
    private final Memory memory = new Memory();

    @Getter
    @Setter
    public static class Milvus {

        private String host = "localhost";

        @Min(1)
        @Max(65535)
        private int port = 19530;

        private String uri = "";

        private String token = "";

        private String username = "";

        private String password = "";

        @NotBlank
        private String databaseName = "default";

        private boolean secure = false;

        private boolean initializeSchema = true;

        @Min(1)
        private int embeddingDimension = 1024;

        @NotBlank
        private String metricType = "COSINE";

        @NotBlank
        private String indexType = "IVF_FLAT";

        private String indexParameters = "{\"nlist\":1024}";
    }

    @Getter
    @Setter
    public static class Product {

        @NotBlank
        private String collectionName = "product_index";

        private boolean bm25Enabled = true;
    }

    @Getter
    @Setter
    public static class Memory {

        @NotBlank
        private String collectionName = "memory_index";
    }
}
