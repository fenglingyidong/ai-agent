package com.example.ragagent.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryServiceTest {

    @Test
    void buildMemoryDocumentIdShouldFitMilvusDefaultDocIdLength() {
        String id = LongTermMemoryService.buildMemoryDocumentId(
                "alice::mem-ui-evict-neutral-20260522-2154",
                123456789L,
                223456789L
        );

        assertEquals(36, id.length());
        assertTrue(id.matches("[0-9a-f-]{36}"));
    }

    @Test
    void buildMemoryDocumentIdShouldBeStableForSameSequenceRange() {
        String first = LongTermMemoryService.buildMemoryDocumentId("user-1::session-1", 1L, 4L);
        String second = LongTermMemoryService.buildMemoryDocumentId("user-1::session-1", 1L, 4L);

        assertEquals(first, second);
    }
}
