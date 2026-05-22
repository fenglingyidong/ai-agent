package com.example.ragagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.ai.vectorstore.redis.initialize-schema=false")
class RagAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
