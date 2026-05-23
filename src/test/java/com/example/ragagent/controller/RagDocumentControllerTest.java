package com.example.ragagent.controller;

import com.example.ragagent.rag.RagDocumentConstants;
import com.example.ragagent.rag.impl.ParentChildDocumentIndexer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

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

class RagDocumentControllerTest {

    @Test
    void importProductDocumentShouldPutStructuredAttributesIntoMetadata() {
        ParentChildDocumentIndexer indexer = mock(ParentChildDocumentIndexer.class);
        when(indexer.indexDocumentDetails(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(new ParentChildDocumentIndexer.DocumentIndexingResult(List.of("parent-1"), List.of("child-1")));
        RagDocumentController controller = new RagDocumentController(indexer);

        RagDocumentController.ProductDocumentImportRequest request =
                new RagDocumentController.ProductDocumentImportRequest(
                        "P1001",
                        "SKU-P1001-BLK-42",
                        "云跑 AirLite 缓震跑步鞋",
                        "Stride",
                        "运动鞋",
                        BigDecimal.valueOf(499),
                        38,
                        "https://example.com/p1001.jpg",
                        "轻量缓震，适合通勤慢跑。",
                        "脚感柔软，尺码标准。",
                        "适合预算 500 元左右的慢跑入门用户。",
                        Map.of("颜色", " 黑色 ", "鞋码", "42", "空属性", " ")
                );

        ResponseEntity<RagDocumentController.RagDocumentImportResponse> response =
                controller.importProductDocument(request);

        ArgumentCaptor<Map> metadataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(indexer).indexDocumentDetails(
                org.mockito.ArgumentMatchers.eq("product-P1001"),
                org.mockito.ArgumentMatchers.eq("云跑 AirLite 缓震跑步鞋"),
                org.mockito.ArgumentMatchers.argThat(content -> content.contains("规格参数")),
                metadataCaptor.capture()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = metadataCaptor.getValue();
        assertEquals("P1001", metadata.get(RagDocumentConstants.METADATA_PRODUCT_ID));
        assertEquals("SKU-P1001-BLK-42", metadata.get(RagDocumentConstants.METADATA_SKU_ID));
        assertEquals(Map.of("颜色", "黑色", "鞋码", "42"), metadata.get(RagDocumentConstants.METADATA_ATTRIBUTES));
        assertTrue(response.getStatusCode().is2xxSuccessful());
    }
}
