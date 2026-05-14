package com.example.ragagent.rag;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.wltea.analyzer.core.IKSegmenter;
import org.wltea.analyzer.core.Lexeme;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class ChineseTextSegmenter {

    public String segmentForSearch(String text) {
        String normalizedText = normalizeText(text);
        if (!StringUtils.hasText(normalizedText)) {
            return "";
        }

        List<String> tokens = segmentWithIk(normalizedText);
        if (tokens.isEmpty()) {
            return normalizedText;
        }
        return String.join(" ", tokens);
    }

    private List<String> segmentWithIk(String text) {
        List<String> tokens = new ArrayList<>();
        IKSegmenter segmenter = new IKSegmenter(new StringReader(text), true);
        try {
            Lexeme lexeme;
            while ((lexeme = segmenter.next()) != null) {
                String token = normalizeToken(lexeme.getLexemeText());
                if (StringUtils.hasText(token)) {
                    tokens.add(token);
                }
            }
        }
        catch (IOException ex) {
            return List.of();
        }
        return List.copyOf(tokens);
    }

    private String normalizeToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        return token.replaceAll("[^\\p{IsHan}a-zA-Z0-9]", "").trim();
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().replace("\r\n", "\n").replace('\r', '\n');
    }
}
