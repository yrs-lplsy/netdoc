package com.kbrag.cache;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CosineSimilarityTest {
    @Test
    void identical_vectors_similarity_one() {
        float[] v = {1f, 2f, 3f};
        assertEquals(1.0, CosineSimilarity.cosine(v, v), 1e-6);
    }

    @Test
    void orthogonal_vectors_similarity_zero() {
        assertEquals(0.0, CosineSimilarity.cosine(new float[]{1f, 0f}, new float[]{0f, 1f}), 1e-6);
    }

    @Test
    void above_threshold_classified_as_hit() {
        float[] a = {1f, 0f};
        float[] b = {0.99f, 0.141f};          // 夹角约 8°,相似度约 0.99
        assertTrue(CosineSimilarity.cosine(a, b) > 0.95);
    }

    @Test
    void select_best_above_threshold() {
        Map<String, float[]> candidates = Map.of(
                "a", new float[]{1f, 0f},
                "b", new float[]{0.5f, 0.866f});  // 与 query 夹角 60°,相似度 0.5
        assertEquals("a", CosineSimilarity.selectBest(candidates, new float[]{1f, 0f}, 0.95));
        assertNull(CosineSimilarity.selectBest(Map.of("a", new float[]{0f, 1f}), new float[]{1f, 0f}, 0.95));
    }

    @Test
    void none_above_threshold_returns_null() {
        assertNull(CosineSimilarity.selectBest(Map.of("a", new float[]{0f, 1f}), new float[]{1f, 0f}, 0.95));
    }
}
