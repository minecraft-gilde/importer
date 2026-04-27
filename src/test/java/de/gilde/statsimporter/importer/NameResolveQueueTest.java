package de.gilde.statsimporter.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NameResolveQueueTest {

    @Test
    void deduplicatesQueuedResolverRunsAndKeepsLargestBudget() {
        NameResolveQueue queue = new NameResolveQueue();

        assertTrue(queue.offer("after-import", 300));
        assertFalse(queue.offer("timer:maintenance", 500));

        NameResolveQueue.NameResolveRequest request = queue.poll();
        assertEquals("after-import+timer:maintenance", request.reason());
        assertEquals(500, request.maxPerRunOverride());
        assertNull(queue.poll());
    }

    @Test
    void clampsInvalidManualBudget() {
        NameResolveQueue queue = new NameResolveQueue();

        queue.offer("manual", -20);

        assertEquals(1, queue.poll().maxPerRunOverride());
    }
}
