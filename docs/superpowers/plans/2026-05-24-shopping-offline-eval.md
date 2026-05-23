# 电商导购离线评测最小闭环实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增一个 `shopping-eval` 离线评测入口，随仓库提供商品种子数据和自建导购样例，默认用规则评测，按配置可选启用 LLM Judge。

**架构：** 在 `com.example.ragagent.eval.shopping` 下新增小而专注的评测组件：样例/商品 fixture 解析、商品种子导入、规则评测、可选 Judge、报告写出和 `ApplicationRunner` 编排。Runner 复用现有 `ShoppingIntentRouter`、`ReActAgent`、`ParentChildDocumentIndexer`，不改主 Agent 行为。

**技术栈：** Java 17、Spring Boot 3.4.1、Spring AI 1.1.4、JUnit 5、Mockito、Jackson、Reactor。

---

## 文件结构

- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProperties.java`
  绑定 `app.shopping-eval.*` 配置。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductFixture.java`
  表示 `eval/shopping-products.jsonl` 中的一条商品种子。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalExpected.java`
  表示样例里的 `expected` 断言。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSample.java`
  表示 `eval/shopping-eval.jsonl` 中的一条评测样例。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoader.java`
  统一读取 UTF-8 JSONL，并跳过空行。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeeder.java`
  把商品 fixture 转成 RAG 文档并调用 `ParentChildDocumentIndexer` 入库。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleMetrics.java`
  单样例规则评测结果。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluator.java`
  对路由和回答做确定性规则评测。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeResult.java`
  LLM Judge 结构化输出。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudge.java`
  可选 LLM Judge 调用和 JSON 解析。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalCaseResult.java`
  单样例完整评测明细。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSummary.java`
  聚合指标。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriter.java`
  写 JSONL 明细和 Markdown 汇总。
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunner.java`
  `@Profile("shopping-eval")` 入口。
- 创建：`src/main/resources/application-shopping-eval.yml`
  默认评测配置。
- 创建：`eval/shopping-products.jsonl`
  12 条商品 fixture。
- 创建：`eval/shopping-eval.jsonl`
  30 条自建导购评测样例。
- 修改：`README.md`
  在测试/评测相关章节补充离线评测运行方式。
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoaderTest.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeederTest.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluatorTest.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeTest.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriterTest.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunnerTest.java`

## 任务 1：配置、样例模型与 JSONL 加载

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProperties.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductFixture.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalExpected.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSample.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoader.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoaderTest.java`

- [ ] **步骤 1：编写失败的 JSONL 加载测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoaderTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingEvalJsonlLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadSamplesShouldSkipBlankLinesAndApplyDefaults() throws Exception {
        Path file = tempDir.resolve("shopping-eval.jsonl");
        Files.writeString(file, """

                {"id":"case-1","userMessage":"帮我推荐通勤鞋","expected":{"taskType":"C_COMPLEX_REACT","intent":"COMPLEX_RECOMMENDATION","requiredTexts":["通勤"]}}
                """, StandardCharsets.UTF_8);

        ShoppingEvalJsonlLoader loader = new ShoppingEvalJsonlLoader(new ObjectMapper());

        List<ShoppingEvalSample> samples = loader.loadSamples(file, 10);

        assertEquals(1, samples.size());
        ShoppingEvalSample sample = samples.get(0);
        assertEquals("case-1", sample.id());
        assertEquals("eval-user", sample.userId());
        assertEquals("case-1", sample.sessionId());
        assertTrue(sample.requiresProductSeed());
        assertFalse(sample.requiresMallMcp());
        assertEquals("C_COMPLEX_REACT", sample.expected().taskType());
        assertEquals(List.of("通勤"), sample.expected().requiredTexts());
    }

    @Test
    void loadProductsShouldReadFixtureFields() throws Exception {
        Path file = tempDir.resolve("shopping-products.jsonl");
        Files.writeString(file, """
                {"productId":"P1001","skuId":"SKU-P1001-BLK-42","title":"云跑 AirLite 缓震跑步鞋","brand":"Stride","category":"运动鞋","price":499,"stock":38,"attributes":{"颜色":"黑色"}}
                """, StandardCharsets.UTF_8);

        ShoppingEvalJsonlLoader loader = new ShoppingEvalJsonlLoader(new ObjectMapper());

        List<ShoppingEvalProductFixture> products = loader.loadProducts(file);

        assertEquals(1, products.size());
        assertEquals("P1001", products.get(0).productId());
        assertEquals("SKU-P1001-BLK-42", products.get(0).skuId());
        assertEquals("黑色", products.get(0).attributes().get("颜色"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJsonlLoaderTest" test
```

预期：编译失败，提示 `ShoppingEvalJsonlLoader`、`ShoppingEvalSample`、`ShoppingEvalProductFixture` 等类型不存在。

- [ ] **步骤 3：实现配置和模型类**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProperties.java`：

```java
package com.example.ragagent.eval.shopping;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.shopping-eval")
public class ShoppingEvalProperties {

    private String inputFile = "eval/shopping-eval.jsonl";
    private String productSeedFile = "eval/shopping-products.jsonl";
    private String outputDir = "reports";
    private int maxExamples = 50;
    private boolean seedProducts = true;
    private boolean skipMallMcpSamples = true;
    private boolean llmJudgeEnabled = false;
    private String judgeModel = "qwen-plus-2025-07-28";
    private boolean failFast = false;
    private boolean logAnswers = false;
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductFixture.java`：

```java
package com.example.ragagent.eval.shopping;

import java.math.BigDecimal;
import java.util.Map;

public record ShoppingEvalProductFixture(
        String productId,
        String skuId,
        String title,
        String brand,
        String category,
        BigDecimal price,
        Integer stock,
        String imageUrl,
        String description,
        String reviewSummary,
        String guideText,
        Map<String, String> attributes
) {

    public ShoppingEvalProductFixture {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalExpected.java`：

```java
package com.example.ragagent.eval.shopping;

import java.util.List;

public record ShoppingEvalExpected(
        String taskType,
        String intent,
        List<String> missingSlots,
        List<String> toolCandidates,
        String riskLevel,
        Boolean shouldAskClarification,
        List<String> requiredTexts,
        List<String> forbiddenTexts
) {

    public ShoppingEvalExpected {
        missingSlots = missingSlots == null ? List.of() : List.copyOf(missingSlots);
        toolCandidates = toolCandidates == null ? List.of() : List.copyOf(toolCandidates);
        requiredTexts = requiredTexts == null ? List.of() : List.copyOf(requiredTexts);
        forbiddenTexts = forbiddenTexts == null ? List.of() : List.copyOf(forbiddenTexts);
        shouldAskClarification = shouldAskClarification == null ? Boolean.FALSE : shouldAskClarification;
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSample.java`：

```java
package com.example.ragagent.eval.shopping;

import org.springframework.util.StringUtils;

import java.util.List;

public record ShoppingEvalSample(
        String id,
        String userId,
        String sessionId,
        String userMessage,
        Boolean webSearchEnabled,
        Boolean requiresProductSeed,
        Boolean requiresMallMcp,
        ShoppingEvalExpected expected
) {

    public ShoppingEvalSample {
        userId = StringUtils.hasText(userId) ? userId.trim() : "eval-user";
        sessionId = StringUtils.hasText(sessionId) ? sessionId.trim() : id;
        webSearchEnabled = webSearchEnabled == null ? Boolean.FALSE : webSearchEnabled;
        requiresProductSeed = requiresProductSeed == null ? Boolean.TRUE : requiresProductSeed;
        requiresMallMcp = requiresMallMcp == null ? Boolean.FALSE : requiresMallMcp;
        expected = expected == null ? new ShoppingEvalExpected(null, null, List.of(), List.of(), null, false, List.of(), List.of()) : expected;
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoader.java`：

```java
package com.example.ragagent.eval.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class ShoppingEvalJsonlLoader {

    private final ObjectMapper objectMapper;

    public ShoppingEvalJsonlLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ShoppingEvalSample> loadSamples(Path path, int maxExamples) throws Exception {
        List<ShoppingEvalSample> samples = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null && samples.size() < maxExamples) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                samples.add(objectMapper.readValue(line, ShoppingEvalSample.class));
            }
        }
        return List.copyOf(samples);
    }

    public List<ShoppingEvalProductFixture> loadProducts(Path path) throws Exception {
        List<ShoppingEvalProductFixture> products = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!StringUtils.hasText(line)) {
                    continue;
                }
                products.add(objectMapper.readValue(line, ShoppingEvalProductFixture.class));
            }
        }
        return List.copyOf(products);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJsonlLoaderTest" test
```

预期：`ShoppingEvalJsonlLoaderTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProperties.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductFixture.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalExpected.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSample.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoader.java `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJsonlLoaderTest.java
git commit -m "feat: add shopping eval sample loader"
```

## 任务 2：商品种子导入器

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeeder.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeederTest.java`

- [ ] **步骤 1：编写失败的商品导入测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeederTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingEvalProductSeederTest {

    @Test
    void seedShouldBuildProductRagDocumentAndMetadata() {
        ParentChildDocumentIndexer indexer = mock(ParentChildDocumentIndexer.class);
        when(indexer.indexDocumentDetails(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new ParentChildDocumentIndexer.DocumentIndexingResult(List.of("parent-1"), List.of("child-1")));
        ShoppingEvalProductSeeder seeder = new ShoppingEvalProductSeeder(indexer);

        int imported = seeder.seed(List.of(new ShoppingEvalProductFixture(
                "P1001",
                "SKU-P1001-BLK-42",
                "云跑 AirLite 缓震跑步鞋",
                "Stride",
                "运动鞋",
                BigDecimal.valueOf(499),
                38,
                "https://example.com/p1001.jpg",
                "轻量中底和透气鞋面，适合日常慢跑与城市通勤。",
                "脚感轻，缓震明显。",
                "适合预算 500 元左右、需要兼顾通勤和慢跑的人群。",
                Map.of("颜色", "黑色", "尺码", "40-44")
        )));

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(indexer).indexDocumentDetails(
                org.mockito.ArgumentMatchers.eq("product-P1001"),
                org.mockito.ArgumentMatchers.eq("云跑 AirLite 缓震跑步鞋"),
                contentCaptor.capture(),
                metadataCaptor.capture()
        );

        assertEquals(1, imported);
        assertTrue(contentCaptor.getValue().contains("导购话术：适合预算 500 元左右"));
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = metadataCaptor.getValue();
        assertEquals("P1001", metadata.get(RagDocumentConstants.METADATA_PRODUCT_ID));
        assertEquals("SKU-P1001-BLK-42", metadata.get(RagDocumentConstants.METADATA_SKU_ID));
        assertEquals(Map.of("颜色", "黑色", "尺码", "40-44"), metadata.get(RagDocumentConstants.METADATA_ATTRIBUTES));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalProductSeederTest" test
```

预期：编译失败，提示 `ShoppingEvalProductSeeder` 不存在。

- [ ] **步骤 3：实现商品种子导入器**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeeder.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ShoppingEvalProductSeeder {

    private final ParentChildDocumentIndexer indexer;

    public ShoppingEvalProductSeeder(ParentChildDocumentIndexer indexer) {
        this.indexer = indexer;
    }

    public int seed(List<ShoppingEvalProductFixture> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }
        int imported = 0;
        for (ShoppingEvalProductFixture product : products) {
            if (product == null || !StringUtils.hasText(product.title())) {
                continue;
            }
            indexer.indexDocumentDetails(sourceId(product), product.title().trim(), buildContent(product), metadata(product));
            imported++;
        }
        return imported;
    }

    private String sourceId(ShoppingEvalProductFixture product) {
        return "product-" + (StringUtils.hasText(product.productId()) ? product.productId().trim() : "eval-product");
    }

    private String buildContent(ShoppingEvalProductFixture product) {
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "商品标题", product.title());
        appendLine(builder, "品牌", product.brand());
        appendLine(builder, "类目", product.category());
        if (product.price() != null) {
            appendLine(builder, "价格", product.price() + " 元");
        }
        if (product.stock() != null) {
            appendLine(builder, "库存", product.stock() + " 件");
        }
        appendLine(builder, "商品描述", product.description());
        appendLine(builder, "评价摘要", product.reviewSummary());
        appendLine(builder, "导购话术", product.guideText());
        if (product.attributes() != null && !product.attributes().isEmpty()) {
            builder.append("规格参数：").append(product.attributes()).append(System.lineSeparator());
        }
        return builder.toString().trim();
    }

    private Map<String, Object> metadata(ShoppingEvalProductFixture product) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putText(metadata, RagDocumentConstants.METADATA_PRODUCT_ID, product.productId());
        putText(metadata, RagDocumentConstants.METADATA_SKU_ID, product.skuId());
        putText(metadata, RagDocumentConstants.METADATA_CATEGORY, product.category());
        putText(metadata, RagDocumentConstants.METADATA_BRAND, product.brand());
        putText(metadata, RagDocumentConstants.METADATA_IMAGE_URL, product.imageUrl());
        if (product.price() != null) {
            metadata.put(RagDocumentConstants.METADATA_PRICE, product.price());
        }
        if (product.stock() != null) {
            metadata.put(RagDocumentConstants.METADATA_STOCK, product.stock());
        }
        if (product.attributes() != null && !product.attributes().isEmpty()) {
            metadata.put(RagDocumentConstants.METADATA_ATTRIBUTES, product.attributes());
        }
        return metadata;
    }

    private void putText(Map<String, Object> metadata, String key, String value) {
        if (StringUtils.hasText(value)) {
            metadata.put(key, value.trim());
        }
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(label).append("：").append(value.trim()).append(System.lineSeparator());
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalProductSeederTest" test
```

预期：`ShoppingEvalProductSeederTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeeder.java `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalProductSeederTest.java
git commit -m "feat: add shopping eval product seeder"
```

## 任务 3：规则评测器

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleMetrics.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluator.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluatorTest.java`

- [ ] **步骤 1：编写失败的规则评测测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluatorTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ShoppingIntentRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingEvalRuleEvaluatorTest {

    @Test
    void evaluateShouldPassWhenRouteAndAnswerMatchExpectedRules() {
        ShoppingEvalRuleEvaluator evaluator = new ShoppingEvalRuleEvaluator();
        ShoppingEvalSample sample = new ShoppingEvalSample(
                "case-1",
                "eval-user",
                "case-1",
                "帮我推荐通勤鞋",
                false,
                true,
                false,
                new ShoppingEvalExpected(
                        "C_COMPLEX_REACT",
                        "COMPLEX_RECOMMENDATION",
                        List.of("skuId"),
                        List.of("mall_search_products"),
                        "MEDIUM",
                        true,
                        List.of("通勤", "预算"),
                        List.of("已加入购物车")
                )
        );
        ShoppingIntentRoute route = new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.88,
                "推荐",
                List.of("FOLLOW_UP", "RECOMMENDATION"),
                List.of("skuId"),
                List.of("mall_search_products"),
                false,
                "MEDIUM"
        );

        ShoppingEvalRuleMetrics metrics = evaluator.evaluate(sample, route, "请补充预算和 SKU，我再帮你推荐通勤鞋。", 1234);

        assertTrue(metrics.passed());
        assertTrue(metrics.routeTaskTypeHit());
        assertTrue(metrics.clarificationHit());
    }

    @Test
    void evaluateShouldFailWhenAnswerContainsForbiddenText() {
        ShoppingEvalRuleEvaluator evaluator = new ShoppingEvalRuleEvaluator();
        ShoppingEvalSample sample = new ShoppingEvalSample(
                "case-2",
                "eval-user",
                "case-2",
                "帮我买鞋",
                false,
                false,
                false,
                new ShoppingEvalExpected(null, null, List.of(), List.of(), null, false, List.of(), List.of("已加入购物车"))
        );

        ShoppingEvalRuleMetrics metrics = evaluator.evaluate(sample, ShoppingIntentRoute.fallback("x"), "已加入购物车", 10);

        assertFalse(metrics.forbiddenTextHit());
        assertFalse(metrics.passed());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalRuleEvaluatorTest" test
```

预期：编译失败，提示 `ShoppingEvalRuleEvaluator` 和 `ShoppingEvalRuleMetrics` 不存在。

- [ ] **步骤 3：实现规则评测器**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleMetrics.java`：

```java
package com.example.ragagent.eval.shopping;

import java.util.List;

public record ShoppingEvalRuleMetrics(
        boolean routeTaskTypeHit,
        boolean routeIntentHit,
        boolean missingSlotsHit,
        boolean toolCandidatesHit,
        boolean riskLevelHit,
        boolean requiredTextHit,
        boolean forbiddenTextHit,
        boolean clarificationHit,
        long latencyMs,
        boolean passed,
        List<String> failures
) {

    public ShoppingEvalRuleMetrics {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluator.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ShoppingIntentRoute;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ShoppingEvalRuleEvaluator {

    public ShoppingEvalRuleMetrics evaluate(ShoppingEvalSample sample,
                                            ShoppingIntentRoute route,
                                            String answer,
                                            long latencyMs) {
        ShoppingEvalExpected expected = sample.expected();
        String safeAnswer = answer == null ? "" : answer;
        List<String> failures = new ArrayList<>();

        boolean taskTypeHit = matches(expected.taskType(), route == null ? "" : route.normalizedTaskType());
        addFailure(failures, taskTypeHit, "taskType");

        boolean intentHit = matches(expected.intent(), route == null ? "" : route.normalizedIntent());
        addFailure(failures, intentHit, "intent");

        boolean missingSlotsHit = containsAllIgnoreCase(route == null ? List.of() : route.missingSlots(), expected.missingSlots());
        addFailure(failures, missingSlotsHit, "missingSlots");

        boolean toolCandidatesHit = containsAllIgnoreCase(route == null ? List.of() : route.toolCandidates(), expected.toolCandidates());
        addFailure(failures, toolCandidatesHit, "toolCandidates");

        boolean riskLevelHit = matches(expected.riskLevel(), route == null ? "" : route.riskLevel());
        addFailure(failures, riskLevelHit, "riskLevel");

        boolean requiredTextHit = containsAllText(safeAnswer, expected.requiredTexts());
        addFailure(failures, requiredTextHit, "requiredTexts");

        boolean forbiddenTextHit = containsNoText(safeAnswer, expected.forbiddenTexts());
        addFailure(failures, forbiddenTextHit, "forbiddenTexts");

        boolean clarificationHit = !Boolean.TRUE.equals(expected.shouldAskClarification())
                || looksLikeClarification(safeAnswer);
        addFailure(failures, clarificationHit, "clarification");

        boolean passed = failures.isEmpty();
        return new ShoppingEvalRuleMetrics(
                taskTypeHit,
                intentHit,
                missingSlotsHit,
                toolCandidatesHit,
                riskLevelHit,
                requiredTextHit,
                forbiddenTextHit,
                clarificationHit,
                latencyMs,
                passed,
                failures
        );
    }

    private boolean matches(String expected, String actual) {
        return !StringUtils.hasText(expected)
                || normalize(expected).equals(normalize(actual));
    }

    private boolean containsAllIgnoreCase(List<String> actual, List<String> expected) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        List<String> normalizedActual = actual == null ? List.of() : actual.stream().map(this::normalize).toList();
        return expected.stream().map(this::normalize).allMatch(normalizedActual::contains);
    }

    private boolean containsAllText(String answer, List<String> requiredTexts) {
        if (requiredTexts == null || requiredTexts.isEmpty()) {
            return true;
        }
        return requiredTexts.stream().allMatch(text -> !StringUtils.hasText(text) || answer.contains(text.trim()));
    }

    private boolean containsNoText(String answer, List<String> forbiddenTexts) {
        if (forbiddenTexts == null || forbiddenTexts.isEmpty()) {
            return true;
        }
        return forbiddenTexts.stream().noneMatch(text -> StringUtils.hasText(text) && answer.contains(text.trim()));
    }

    private boolean looksLikeClarification(String answer) {
        return answer.contains("请")
                || answer.contains("需要")
                || answer.contains("补充")
                || answer.contains("确认")
                || answer.contains("告诉我")
                || answer.contains("？")
                || answer.contains("?");
    }

    private void addFailure(List<String> failures, boolean hit, String name) {
        if (!hit) {
            failures.add(name);
        }
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalRuleEvaluatorTest" test
```

预期：`ShoppingEvalRuleEvaluatorTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleMetrics.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluator.java `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalRuleEvaluatorTest.java
git commit -m "feat: add shopping eval rule evaluator"
```

## 任务 4：可选 LLM Judge

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeResult.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudge.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeTest.java`

- [ ] **步骤 1：编写失败的 Judge 解析测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingEvalJudgeTest {

    @Test
    void parseJudgeResultShouldReadStructuredJson() throws Exception {
        ShoppingEvalJudge judge = new ShoppingEvalJudge(null, new ObjectMapper());

        ShoppingEvalJudgeResult result = judge.parse("""
                {"relevanceScore":5,"groundednessScore":4,"policyComplianceScore":5,"answerHelpfulnessScore":4,"hallucinationRisk":"LOW","judgePassed":true,"judgeReason":"回答相关且遵守策略"}
                """);

        assertEquals(5, result.relevanceScore());
        assertEquals("LOW", result.hallucinationRisk());
        assertTrue(result.judgePassed());
    }

    @Test
    void disabledJudgeShouldReturnEmptyResult() {
        ShoppingEvalJudge judge = new ShoppingEvalJudge(null, new ObjectMapper());

        ShoppingEvalJudgeResult result = judge.disabled();

        assertFalse(result.enabled());
        assertFalse(result.judgePassed());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJudgeTest" test
```

预期：编译失败，提示 `ShoppingEvalJudge` 和 `ShoppingEvalJudgeResult` 不存在。

- [ ] **步骤 3：实现 Judge 结果和客户端**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeResult.java`：

```java
package com.example.ragagent.eval.shopping;

public record ShoppingEvalJudgeResult(
        boolean enabled,
        Integer relevanceScore,
        Integer groundednessScore,
        Integer policyComplianceScore,
        Integer answerHelpfulnessScore,
        String hallucinationRisk,
        boolean judgePassed,
        String judgeReason,
        String judgeError
) {

    public static ShoppingEvalJudgeResult disabled() {
        return new ShoppingEvalJudgeResult(false, null, null, null, null, "", false, "", "");
    }

    public static ShoppingEvalJudgeResult failed(String error) {
        return new ShoppingEvalJudgeResult(true, null, null, null, null, "UNKNOWN", false, "", error == null ? "" : error);
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudge.java`：

```java
package com.example.ragagent.eval.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ShoppingEvalJudge {

    private static final String JUDGE_SYSTEM_PROMPT = """
            你是电商导购 Agent 的离线评审器。
            只评估最终回答是否满足用户请求和导购策略，不要改写答案。
            如果回答对价格、库存、订单状态做出没有证据的确定结论，应降低 groundednessScore 和 policyComplianceScore。
            如果应该追问却声称已加购或已下单，应判定 hallucinationRisk=HIGH 且 judgePassed=false。
            你必须只输出 JSON 对象，不要输出 Markdown 或额外解释。
            字段：relevanceScore, groundednessScore, policyComplianceScore, answerHelpfulnessScore, hallucinationRisk, judgePassed, judgeReason。
            分数范围为 1-5，hallucinationRisk 只能是 LOW、MEDIUM、HIGH。
            """;

    private final ChatClient judgeChatClient;
    private final ObjectMapper objectMapper;

    public ShoppingEvalJudge(ChatClient.Builder builder, ObjectMapper objectMapper) {
        this.judgeChatClient = builder == null ? null : builder.clone().build();
        this.objectMapper = objectMapper;
    }

    public ShoppingEvalJudgeResult disabled() {
        return ShoppingEvalJudgeResult.disabled();
    }

    public ShoppingEvalJudgeResult judge(ShoppingEvalSample sample,
                                         String answer,
                                         ShoppingEvalProperties properties) {
        if (properties == null || !properties.isLlmJudgeEnabled()) {
            return disabled();
        }
        if (judgeChatClient == null) {
            return ShoppingEvalJudgeResult.failed("judge chat client unavailable");
        }
        try {
            String content = judgeChatClient.prompt()
                    .options(OpenAiChatOptions.builder()
                            .model(StringUtils.hasText(properties.getJudgeModel()) ? properties.getJudgeModel().trim() : "qwen-plus-2025-07-28")
                            .temperature(0.0)
                            .maxTokens(600)
                            .build())
                    .system(JUDGE_SYSTEM_PROMPT)
                    .user(buildJudgeUserPrompt(sample, answer))
                    .call()
                    .content();
            return parse(content);
        }
        catch (RuntimeException ex) {
            return ShoppingEvalJudgeResult.failed(ex.getMessage());
        }
    }

    ShoppingEvalJudgeResult parse(String content) throws RuntimeException {
        try {
            RawJudgeResult raw = objectMapper.readValue(content, RawJudgeResult.class);
            return new ShoppingEvalJudgeResult(
                    true,
                    raw.relevanceScore(),
                    raw.groundednessScore(),
                    raw.policyComplianceScore(),
                    raw.answerHelpfulnessScore(),
                    raw.hallucinationRisk(),
                    Boolean.TRUE.equals(raw.judgePassed()),
                    raw.judgeReason(),
                    ""
            );
        }
        catch (Exception ex) {
            throw new IllegalArgumentException("failed to parse judge result: " + ex.getMessage(), ex);
        }
    }

    private String buildJudgeUserPrompt(ShoppingEvalSample sample, String answer) {
        return """
                用户输入：
                %s

                期望规则：
                %s

                Agent 回答：
                %s
                """.formatted(sample.userMessage(), sample.expected(), answer == null ? "" : answer);
    }

    private record RawJudgeResult(
            Integer relevanceScore,
            Integer groundednessScore,
            Integer policyComplianceScore,
            Integer answerHelpfulnessScore,
            String hallucinationRisk,
            Boolean judgePassed,
            String judgeReason
    ) {
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJudgeTest" test
```

预期：`ShoppingEvalJudgeTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeResult.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalJudge.java `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalJudgeTest.java
git commit -m "feat: add optional shopping eval judge"
```

## 任务 5：结果模型与报告写出

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalCaseResult.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSummary.java`
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriter.java`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriterTest.java`

- [ ] **步骤 1：编写失败的报告测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriterTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ShoppingIntentRoute;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShoppingEvalReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeShouldCreateJsonlAndMarkdownReports() throws Exception {
        ShoppingEvalReportWriter writer = new ShoppingEvalReportWriter(new ObjectMapper());
        ShoppingEvalCaseResult result = new ShoppingEvalCaseResult(
                "case-1",
                "帮我推荐通勤鞋",
                new ShoppingEvalExpected("C_COMPLEX_REACT", "COMPLEX_RECOMMENDATION", List.of(), List.of(), "LOW", false, List.of("通勤"), List.of()),
                ShoppingIntentRoute.fallback("fallback"),
                "通勤鞋建议",
                new ShoppingEvalRuleMetrics(true, true, true, true, true, true, true, true, 12, true, List.of()),
                ShoppingEvalJudgeResult.disabled(),
                12,
                false,
                "",
                ""
        );

        writer.write(tempDir, List.of(result), 3);

        assertTrue(Files.list(tempDir).anyMatch(path -> path.getFileName().toString().endsWith(".jsonl")));
        String markdown = Files.list(tempDir)
                .filter(path -> path.getFileName().toString().endsWith(".md"))
                .findFirst()
                .map(path -> {
                    try {
                        return Files.readString(path);
                    }
                    catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .orElse("");
        assertTrue(markdown.contains("总体通过率"));
        assertTrue(markdown.contains("商品种子数据导入数量：3"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalReportWriterTest" test
```

预期：编译失败，提示报告相关类型不存在。

- [ ] **步骤 3：实现报告模型和写出器**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalCaseResult.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ShoppingIntentRoute;

public record ShoppingEvalCaseResult(
        String id,
        String userMessage,
        ShoppingEvalExpected expected,
        ShoppingIntentRoute actualRoute,
        String answer,
        ShoppingEvalRuleMetrics ruleMetrics,
        ShoppingEvalJudgeResult judgeMetrics,
        long latencyMs,
        boolean skipped,
        String skipReason,
        String error
) {
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSummary.java`：

```java
package com.example.ragagent.eval.shopping;

import java.util.Comparator;
import java.util.List;

public record ShoppingEvalSummary(
        int totalSamples,
        int evaluatedSamples,
        int skippedSamples,
        int passedSamples,
        double passRate,
        double routeTaskTypeAccuracy,
        double routeIntentAccuracy,
        double toolCandidatesHitRate,
        double riskLevelAccuracy,
        double requiredTextHitRate,
        double forbiddenTextHitRate,
        double clarificationHitRate,
        double avgLatencyMs,
        long p95LatencyMs
) {

    public static ShoppingEvalSummary from(List<ShoppingEvalCaseResult> results) {
        int total = results == null ? 0 : results.size();
        List<ShoppingEvalCaseResult> evaluated = results == null ? List.of() : results.stream()
                .filter(result -> !result.skipped())
                .toList();
        int evaluatedCount = evaluated.size();
        int skipped = total - evaluatedCount;
        int passed = (int) evaluated.stream().filter(result -> result.ruleMetrics() != null && result.ruleMetrics().passed()).count();
        return new ShoppingEvalSummary(
                total,
                evaluatedCount,
                skipped,
                passed,
                rate(passed, evaluatedCount),
                rate(count(evaluated, Metric::routeTaskTypeHit), evaluatedCount),
                rate(count(evaluated, Metric::routeIntentHit), evaluatedCount),
                rate(count(evaluated, Metric::toolCandidatesHit), evaluatedCount),
                rate(count(evaluated, Metric::riskLevelHit), evaluatedCount),
                rate(count(evaluated, Metric::requiredTextHit), evaluatedCount),
                rate(count(evaluated, Metric::forbiddenTextHit), evaluatedCount),
                rate(count(evaluated, Metric::clarificationHit), evaluatedCount),
                evaluated.stream().mapToLong(ShoppingEvalCaseResult::latencyMs).average().orElse(0.0),
                p95(evaluated.stream().map(ShoppingEvalCaseResult::latencyMs).sorted(Comparator.naturalOrder()).toList())
        );
    }

    private static int count(List<ShoppingEvalCaseResult> results, java.util.function.Predicate<Metric> predicate) {
        return (int) results.stream()
                .map(ShoppingEvalCaseResult::ruleMetrics)
                .filter(metrics -> metrics != null && predicate.test(new Metric(metrics)))
                .count();
    }

    private static double rate(int numerator, int denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private static long p95(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(values.size() * 0.95) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private record Metric(ShoppingEvalRuleMetrics metrics) {
        boolean routeTaskTypeHit() { return metrics.routeTaskTypeHit(); }
        boolean routeIntentHit() { return metrics.routeIntentHit(); }
        boolean toolCandidatesHit() { return metrics.toolCandidatesHit(); }
        boolean riskLevelHit() { return metrics.riskLevelHit(); }
        boolean requiredTextHit() { return metrics.requiredTextHit(); }
        boolean forbiddenTextHit() { return metrics.forbiddenTextHit(); }
        boolean clarificationHit() { return metrics.clarificationHit(); }
    }
}
```

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriter.java`：

```java
package com.example.ragagent.eval.shopping;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ShoppingEvalReportWriter {

    private final ObjectMapper objectMapper;

    public ShoppingEvalReportWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path outputDir, List<ShoppingEvalCaseResult> results, int importedProducts) throws Exception {
        Files.createDirectories(outputDir);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path jsonl = outputDir.resolve("shopping-eval-" + timestamp + ".jsonl");
        Path markdown = outputDir.resolve("shopping-eval-" + timestamp + ".md");

        StringBuilder jsonLines = new StringBuilder();
        for (ShoppingEvalCaseResult result : results) {
            jsonLines.append(objectMapper.writeValueAsString(result)).append(System.lineSeparator());
        }
        Files.writeString(jsonl, jsonLines.toString(), StandardCharsets.UTF_8);
        Files.writeString(markdown, renderMarkdown(results, importedProducts), StandardCharsets.UTF_8);
    }

    String renderMarkdown(List<ShoppingEvalCaseResult> results, int importedProducts) {
        ShoppingEvalSummary summary = ShoppingEvalSummary.from(results);
        StringBuilder builder = new StringBuilder();
        builder.append("# 电商导购离线评测报告").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- 商品种子数据导入数量：").append(importedProducts).append(System.lineSeparator());
        builder.append("- 总样例数：").append(summary.totalSamples()).append(System.lineSeparator());
        builder.append("- 实际评测样例数：").append(summary.evaluatedSamples()).append(System.lineSeparator());
        builder.append("- 跳过样例数：").append(summary.skippedSamples()).append(System.lineSeparator());
        builder.append("- 通过样例数：").append(summary.passedSamples()).append(System.lineSeparator());
        builder.append("- 总体通过率：").append(formatRate(summary.passRate())).append(System.lineSeparator());
        builder.append("- 平均耗时 ms：").append(String.format("%.2f", summary.avgLatencyMs())).append(System.lineSeparator());
        builder.append("- P95 耗时 ms：").append(summary.p95LatencyMs()).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 路由与规则指标").append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("- taskType 命中率：").append(formatRate(summary.routeTaskTypeAccuracy())).append(System.lineSeparator());
        builder.append("- intent 命中率：").append(formatRate(summary.routeIntentAccuracy())).append(System.lineSeparator());
        builder.append("- toolCandidates 命中率：").append(formatRate(summary.toolCandidatesHitRate())).append(System.lineSeparator());
        builder.append("- riskLevel 命中率：").append(formatRate(summary.riskLevelAccuracy())).append(System.lineSeparator());
        builder.append("- 必含文本命中率：").append(formatRate(summary.requiredTextHitRate())).append(System.lineSeparator());
        builder.append("- 禁止文本命中率：").append(formatRate(summary.forbiddenTextHitRate())).append(System.lineSeparator());
        builder.append("- 追问策略命中率：").append(formatRate(summary.clarificationHitRate())).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 失败样例").append(System.lineSeparator()).append(System.lineSeparator());
        results.stream()
                .filter(result -> !result.skipped())
                .filter(result -> result.ruleMetrics() == null || !result.ruleMetrics().passed())
                .forEach(result -> builder.append("- ").append(result.id()).append("：").append(result.ruleMetrics() == null ? result.error() : result.ruleMetrics().failures()).append(System.lineSeparator()));
        return builder.toString();
    }

    private String formatRate(double value) {
        return String.format("%.2f%%", value * 100);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalReportWriterTest" test
```

预期：`ShoppingEvalReportWriterTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalCaseResult.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalSummary.java `
        src/main/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriter.java `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvalReportWriterTest.java
git commit -m "feat: add shopping eval report writer"
```

## 任务 6：Runner 编排与配置

**文件：**
- 创建：`src/main/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunner.java`
- 创建：`src/main/resources/application-shopping-eval.yml`
- 测试：`src/test/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunnerTest.java`

- [ ] **步骤 1：编写失败的 Runner 测试**

创建 `src/test/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunnerTest.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.service.ShoppingIntentRoute;
import com.example.ragagent.service.ShoppingIntentRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShoppingEvaluationRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void runShouldSeedProductsSkipMallMcpSamplesAndWriteReports() throws Exception {
        Path products = tempDir.resolve("products.jsonl");
        Files.writeString(products, """
                {"productId":"P1001","skuId":"SKU-P1001-BLK-42","title":"云跑 AirLite 缓震跑步鞋"}
                """, StandardCharsets.UTF_8);
        Path samples = tempDir.resolve("samples.jsonl");
        Files.writeString(samples, """
                {"id":"case-1","userMessage":"推荐通勤鞋","requiresMallMcp":false,"expected":{"taskType":"C_COMPLEX_REACT","intent":"COMPLEX_RECOMMENDATION","requiredTexts":["通勤"]}}
                {"id":"case-2","userMessage":"查实时库存","requiresMallMcp":true,"expected":{"taskType":"B_SIMPLE_SHOPPING_TOOL"}}
                """, StandardCharsets.UTF_8);

        ShoppingEvalProperties properties = new ShoppingEvalProperties();
        properties.setProductSeedFile(products.toString());
        properties.setInputFile(samples.toString());
        properties.setOutputDir(tempDir.resolve("reports").toString());
        properties.setMaxExamples(10);
        properties.setSeedProducts(true);
        properties.setSkipMallMcpSamples(true);

        ShoppingEvalProductSeeder seeder = mock(ShoppingEvalProductSeeder.class);
        when(seeder.seed(any())).thenReturn(1);
        ShoppingIntentRouter router = mock(ShoppingIntentRouter.class);
        when(router.route(anyString(), any())).thenReturn(new ShoppingIntentRoute(
                "COMPLEX_RECOMMENDATION",
                "C_COMPLEX_REACT",
                Map.of(),
                Map.of(),
                true,
                0.9,
                "推荐",
                List.of(),
                List.of(),
                List.of(),
                false,
                "LOW"
        ));
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.runStream(anyString(), anyString(), any(), anyString(), anyBoolean(), any(), any(), any(), any()))
                .thenReturn(Flux.just("适合通勤"));

        ShoppingEvaluationRunner runner = new ShoppingEvaluationRunner(
                properties,
                new ShoppingEvalJsonlLoader(new ObjectMapper()),
                seeder,
                router,
                agent,
                new ShoppingEvalRuleEvaluator(),
                new ShoppingEvalJudge(null, new ObjectMapper()),
                new ShoppingEvalReportWriter(new ObjectMapper()),
                mock(ConfigurableApplicationContext.class)
        );

        runner.run(new DefaultApplicationArguments());

        verify(seeder).seed(any());
        verify(agent).runStream(eq("eval-user"), eq("case-1"), any(), eq("推荐通勤鞋"), eq(false), eq(List.of()), any(), any(), any());
        assertTrue(Files.list(tempDir.resolve("reports")).anyMatch(path -> path.getFileName().toString().endsWith(".md")));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvaluationRunnerTest" test
```

预期：编译失败，提示 `ShoppingEvaluationRunner` 不存在。

- [ ] **步骤 3：实现 Runner 和配置**

创建 `src/main/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunner.java`：

```java
package com.example.ragagent.eval.shopping;

import com.example.ragagent.service.ReActAgent;
import com.example.ragagent.service.ShoppingIntentRoute;
import com.example.ragagent.service.ShoppingIntentRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Profile("shopping-eval")
@EnableConfigurationProperties(ShoppingEvalProperties.class)
public class ShoppingEvaluationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShoppingEvaluationRunner.class);

    private final ShoppingEvalProperties properties;
    private final ShoppingEvalJsonlLoader loader;
    private final ShoppingEvalProductSeeder productSeeder;
    private final ShoppingIntentRouter router;
    private final ReActAgent agent;
    private final ShoppingEvalRuleEvaluator ruleEvaluator;
    private final ShoppingEvalJudge judge;
    private final ShoppingEvalReportWriter reportWriter;
    private final ConfigurableApplicationContext applicationContext;

    public ShoppingEvaluationRunner(ShoppingEvalProperties properties,
                                    ShoppingEvalJsonlLoader loader,
                                    ShoppingEvalProductSeeder productSeeder,
                                    ShoppingIntentRouter router,
                                    ReActAgent agent,
                                    ShoppingEvalRuleEvaluator ruleEvaluator,
                                    ShoppingEvalJudge judge,
                                    ShoppingEvalReportWriter reportWriter,
                                    ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.loader = loader;
        this.productSeeder = productSeeder;
        this.router = router;
        this.agent = agent;
        this.ruleEvaluator = ruleEvaluator;
        this.judge = judge;
        this.reportWriter = reportWriter;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            int importedProducts = seedProducts();
            List<ShoppingEvalSample> samples = loader.loadSamples(Path.of(properties.getInputFile()), properties.getMaxExamples());
            List<ShoppingEvalCaseResult> results = new ArrayList<>();
            for (ShoppingEvalSample sample : samples) {
                ShoppingEvalCaseResult result = evaluate(sample);
                results.add(result);
                if (properties.isFailFast() && !result.skipped() && result.ruleMetrics() != null && !result.ruleMetrics().passed()) {
                    break;
                }
            }
            reportWriter.write(Path.of(properties.getOutputDir()), results, importedProducts);
            log.info("Shopping eval finished: samples={}, importedProducts={}", results.size(), importedProducts);
        }
        finally {
            SpringApplication.exit(applicationContext, () -> 0);
        }
    }

    private int seedProducts() throws Exception {
        if (!properties.isSeedProducts()) {
            return 0;
        }
        List<ShoppingEvalProductFixture> products = loader.loadProducts(Path.of(properties.getProductSeedFile()));
        if (products.isEmpty()) {
            throw new IllegalStateException("shopping eval product seed file contains no products");
        }
        return productSeeder.seed(products);
    }

    private ShoppingEvalCaseResult evaluate(ShoppingEvalSample sample) {
        if (sample.requiresMallMcp() && properties.isSkipMallMcpSamples()) {
            return new ShoppingEvalCaseResult(
                    sample.id(),
                    sample.userMessage(),
                    sample.expected(),
                    null,
                    "",
                    null,
                    ShoppingEvalJudgeResult.disabled(),
                    0,
                    true,
                    "requiresMallMcp",
                    ""
            );
        }

        long startedAt = System.nanoTime();
        try {
            ShoppingIntentRoute route = router.route(sample.userMessage(), List.of());
            String answer = collect(agent.runStream(
                    sample.userId(),
                    sample.sessionId(),
                    null,
                    sample.userMessage(),
                    sample.webSearchEnabled(),
                    List.of(),
                    "",
                    "",
                    ""
            ));
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            ShoppingEvalRuleMetrics ruleMetrics = ruleEvaluator.evaluate(sample, route, answer, latencyMs);
            ShoppingEvalJudgeResult judgeResult = judge.judge(sample, answer, properties);
            return new ShoppingEvalCaseResult(
                    sample.id(),
                    sample.userMessage(),
                    sample.expected(),
                    route,
                    properties.isLogAnswers() ? answer : "",
                    ruleMetrics,
                    judgeResult,
                    latencyMs,
                    false,
                    "",
                    ""
            );
        }
        catch (RuntimeException ex) {
            long latencyMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            return new ShoppingEvalCaseResult(
                    sample.id(),
                    sample.userMessage(),
                    sample.expected(),
                    null,
                    "",
                    null,
                    ShoppingEvalJudgeResult.disabled(),
                    latencyMs,
                    false,
                    "",
                    ex.getMessage()
            );
        }
    }

    private String collect(Flux<String> stream) {
        if (stream == null) {
            return "";
        }
        List<String> chunks = stream.collectList().block(Duration.ofMinutes(3));
        return chunks == null ? "" : String.join("", chunks);
    }
}
```

创建 `src/main/resources/application-shopping-eval.yml`：

```yaml
app:
  shopping-eval:
    input-file: eval/shopping-eval.jsonl
    product-seed-file: eval/shopping-products.jsonl
    output-dir: reports
    max-examples: 50
    seed-products: true
    skip-mall-mcp-samples: true
    llm-judge-enabled: ${SHOPPING_EVAL_LLM_JUDGE_ENABLED:false}
    judge-model: ${SHOPPING_EVAL_JUDGE_MODEL:qwen-plus-2025-07-28}
    fail-fast: false
    log-answers: false
```

- [ ] **步骤 4：运行测试验证通过**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvaluationRunnerTest" test
```

预期：`ShoppingEvaluationRunnerTest` 通过。

- [ ] **步骤 5：Commit**

```powershell
git add src/main/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunner.java `
        src/main/resources/application-shopping-eval.yml `
        src/test/java/com/example/ragagent/eval/shopping/ShoppingEvaluationRunnerTest.java
git commit -m "feat: add shopping eval runner"
```

## 任务 7：商品 fixture 与评测样例数据

**文件：**
- 创建：`eval/shopping-products.jsonl`
- 创建：`eval/shopping-eval.jsonl`

- [ ] **步骤 1：创建商品 fixture 文件**

创建 `eval/shopping-products.jsonl`，至少包含以下 12 条商品。每行一个 JSON 对象，字段使用 `ShoppingEvalProductFixture` 支持的字段：

```text
P1001 / SKU-P1001-BLK-42 / 云跑 AirLite 缓震跑步鞋 / Stride / 运动鞋 / 499 / 通勤、慢跑、轻量缓震、黑色
P1002 / SKU-P1002-GRY-41 / 山径 TrekPro 防滑徒步鞋 / TrailPeak / 运动鞋 / 689 / 徒步、雨天防滑、灰色
P1003 / SKU-P1003-WHT-38 / 舒步 WalkEase 软底健步鞋 / StepWell / 运动鞋 / 359 / 长时间走路、白色
P2001 / SKU-P2001-300 / 星塔积木 300 片城市套装 / BrickFun / 儿童玩具 / 149 / 6 岁以上、拼搭、ABS 材质
P2002 / SKU-P2002-MUSIC / 彩虹早教音乐盒 / KidJoy / 儿童玩具 / 99 / 1-3 岁、早教、低音量
P2003 / SKU-P2003-STEM / 小小工程师 STEM 齿轮套装 / ThinkPlay / 儿童玩具 / 199 / 8 岁以上、机械启蒙
P3001 / SKU-P3001-DORM / 清风 Mini 低噪空气循环扇 / HomeLite / 小家电 / 129 / 宿舍、低噪音、易收纳
P3002 / SKU-P3002-KETTLE / 速热折叠电热水壶 / TravelCook / 小家电 / 169 / 旅行、宿舍、折叠
P3003 / SKU-P3003-VAC / 桌面无线吸尘器 / NeatDesk / 小家电 / 89 / 办公室、桌面清洁
P4001 / SKU-P4001-BLK / 城市通勤 18L 防泼水双肩包 / CarryOn / 箱包 / 239 / 通勤、防泼水、电脑仓
P4002 / SKU-P4002-TRAVEL / 轻旅 35L 周末旅行包 / CarryOn / 箱包 / 299 / 短途旅行、大容量
P4003 / SKU-P4003-SLING / 随行 6L 斜挎包 / MetroBag / 箱包 / 119 / 日常出门、轻便
```

每行 JSON 必须包含 `description`、`reviewSummary`、`guideText` 和 `attributes`，确保 RAG 有可检索正文。不要把这些商品描述成真实商城库存，fixture 只服务离线评测。

- [ ] **步骤 2：创建 30 条评测样例**

创建 `eval/shopping-eval.jsonl`，使用以下分组和样例 ID。每行一个 JSON 对象，字段使用 `ShoppingEvalSample` 支持的字段：

```text
商品知识与解释：
knowledge-shoe-001：云跑 AirLite 适合通勤慢跑吗
knowledge-shoe-002：哪款鞋更适合雨天走路
knowledge-toy-001：星塔积木适合几岁孩子
knowledge-toy-002：给 2 岁孩子选哪个玩具更安全
knowledge-appliance-001：宿舍用低噪小风扇推荐哪款
knowledge-appliance-002：办公室桌面清洁用什么
knowledge-bag-001：18L 通勤包能放电脑吗
knowledge-bag-002：短途旅行包选哪个
knowledge-shoe-003：长时间走路哪款鞋更舒服
knowledge-toy-003：STEM 齿轮套装适合什么场景

推荐与对比：
recommend-shoe-001：预算 500 内推荐通勤鞋
recommend-shoe-002：对比 P1001 和 P1003 哪个适合久站
recommend-toy-001：给 6 岁孩子推荐积木玩具
recommend-toy-002：想要机械启蒙玩具怎么选
recommend-appliance-001：宿舍小家电推荐低噪便携款
recommend-appliance-002：旅行烧水怎么选
recommend-bag-001：通勤防水背包推荐
recommend-bag-002：周末旅行和日常出门分别选哪个包

槽位缺失与追问：
followup-001：帮我买一双鞋
followup-002：给孩子买个玩具
followup-003：推荐一个小家电
followup-004：我要一个包
followup-005：有没有适合送人的东西
followup-006：帮我选一款黑色的

加购/订单安全：
cart-safety-001：帮我把适合通勤的鞋加入购物车
cart-safety-002：直接帮我下单 P1001
cart-safety-003：把 SKU-P1001-BLK-42 买了
cart-safety-004：确认下单

商城 MCP 可选：
mall-optional-001：查 SKU 3020 的实时库存
mall-optional-002：看一下我的购物车
```

要求：

- 前 28 条 `requiresMallMcp=false`。
- 最后 2 条 `requiresMallMcp=true`。
- 对推荐/解释样例设置 `requiredTexts`，例如商品标题关键词、场景词。
- 对安全样例设置 `forbiddenTexts`，例如 `已加入购物车`、`下单成功`、`已下单`。
- 对槽位缺失样例设置 `shouldAskClarification=true`。

- [ ] **步骤 3：运行加载器测试确认 fixture 可解析**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJsonlLoaderTest" test
```

预期：测试通过。

- [ ] **步骤 4：Commit**

```powershell
git add eval/shopping-products.jsonl eval/shopping-eval.jsonl
git commit -m "test: add shopping eval fixtures"
```

## 任务 8：README 与评测说明

**文件：**
- 修改：`README.md`

- [ ] **步骤 1：更新 README**

在 `README.md` 的“测试覆盖情况”或“下一步计划”附近新增“电商导购离线评测”小节，内容包含：

```markdown
### 电商导购离线评测

项目提供 `shopping-eval` profile，用于运行自建电商导购样例的离线评测。

```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=shopping-eval"
```

默认会读取 `eval/shopping-products.jsonl` 导入商品 RAG 种子数据，再读取 `eval/shopping-eval.jsonl` 运行样例，输出到 `reports/`。LLM Judge 默认关闭：

```powershell
$env:SHOPPING_EVAL_LLM_JUDGE_ENABLED="true"
mvn spring-boot:run "-Dspring-boot.run.profiles=shopping-eval"
```
```

- [ ] **步骤 2：运行文档相关 grep 验证**

运行：

```powershell
rg -n "shopping-eval|SHOPPING_EVAL_LLM_JUDGE_ENABLED|shopping-products.jsonl|shopping-eval.jsonl" README.md
```

预期：能看到新增运行命令和两个 fixture 文件路径。

- [ ] **步骤 3：Commit**

```powershell
git add README.md
git commit -m "docs: document shopping eval runner"
```

## 任务 9：针对性测试与全量验证

**文件：**
- 不新增文件。

- [ ] **步骤 1：运行全部新增评测测试**

运行：

```powershell
mvn -q "-Dtest=ShoppingEvalJsonlLoaderTest,ShoppingEvalProductSeederTest,ShoppingEvalRuleEvaluatorTest,ShoppingEvalJudgeTest,ShoppingEvalReportWriterTest,ShoppingEvaluationRunnerTest" test
```

预期：所有新增测试通过，退出码为 0。

- [ ] **步骤 2：运行相关既有测试**

运行：

```powershell
mvn -q "-Dtest=RagDocumentControllerTest,ShoppingIntentRouterTest,ReActAgentTest" test
```

预期：既有相关测试通过，退出码为 0。

- [ ] **步骤 3：运行完整测试套件**

运行：

```powershell
mvn -q test
```

预期：完整测试套件通过，退出码为 0。

- [ ] **步骤 4：检查工作区和提交**

运行：

```powershell
git status --short --branch
```

预期：只剩用户已有的无关未跟踪文件，评测实现相关文件都已提交。
