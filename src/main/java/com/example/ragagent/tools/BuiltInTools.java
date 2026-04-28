package com.example.ragagent.tools;

import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class BuiltInTools {

    private static final Map<String, String> MOCK_WEATHER = Map.of(
            "beijing", "Sunny, 15C, wind level 2, humidity 30%",
            "shanghai", "Cloudy, 20C, wind level 3, humidity 65%",
            "guangzhou", "Light rain, 25C, wind level 1, humidity 80%",
            "shenzhen", "Overcast, 23C, wind level 2, humidity 72%"
    );

    private final DocumentRetriever documentRetriever;

    public BuiltInTools(DocumentRetriever documentRetriever) {
        this.documentRetriever = documentRetriever;
    }

    @Tool(description = "获取某个城市的当前天气。示例输入：Beijing")
    public String getWeather(String city) {
        String key = city.trim().toLowerCase();
        String result = MOCK_WEATHER.get(key);
        if (result == null) {
            result = "Sunny, 22C, wind level 2, humidity 45% (mock weather for " + city.trim() + ")";
        }
        return result;
    }

    @Tool(description = "计算包含 +、-、*、/ 和括号的数学表达式。示例输入：(3 + 5) * 2")
    public String calculator(String expression) {
        try {
            ExpressionParser parser = new SpelExpressionParser();
            Object raw = parser.parseExpression(expression.trim())
                    .getValue(new StandardEvaluationContext());

            double result = raw instanceof Number n
                    ? n.doubleValue()
                    : Double.parseDouble(raw.toString());

            String formatted = result == Math.floor(result) && !Double.isInfinite(result)
                    ? String.valueOf((long) result)
                    : String.valueOf(result);

            return expression.trim() + " = " + formatted;
        }
        catch (Exception e) {
            return "Calculation error: " + e.getMessage();
        }
    }

    @Tool(description = "检索知识库中的领域信息、政策、指南或文档事实。示例输入：refund policy after cancellation")
    public String searchKnowledgeBase(String query) {
        List<Document> documents = documentRetriever.retrieve(new Query(query));
        if (documents == null || documents.isEmpty()) {
            return "No relevant knowledge found in the knowledge base.";
        }

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            if (index > 0) {
                builder.append(System.lineSeparator()).append(System.lineSeparator());
            }

            builder.append("[Knowledge ").append(index + 1).append("]");

            String title = readMetadataValue(document, "title");
            if (StringUtils.hasText(title)) {
                builder.append(System.lineSeparator()).append("Title: ").append(title);
            }

            String sourceId = readMetadataValue(document, "sourceId");
            if (StringUtils.hasText(sourceId)) {
                builder.append(System.lineSeparator()).append("Source: ").append(sourceId);
            }

            builder.append(System.lineSeparator()).append(document.getText());
        }
        return builder.toString();
    }

    private String readMetadataValue(Document document, String key) {
        if (document.getMetadata() == null) {
            return "";
        }
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString();
    }
}
