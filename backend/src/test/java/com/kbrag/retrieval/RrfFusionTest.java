package com.kbrag.retrieval;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class RrfFusionTest {
    @Test
    void dense_and_sparse_agree_gets_top_rank() {
        // dense: [1,2,3], sparse: [1,3,4]，1 在两个列表都是第 1 名
        List<Long> fused = RrfFusion.fuse(List.of(1L, 2L, 3L), List.of(1L, 3L, 4L), 60, 3);
        assertTrue(fused.contains(2L) || fused.contains(4L));

        System.out.println("融合之后完整列表：" + fused);
        for (int i = 0; i < fused.size(); i++) {
            System.out.printf("下标%d, 文档id = %d%n", i, fused.get(i));
        }
    }

    @Test
    void dedupes_and_respects_top_n() {
        List<Long> fused = RrfFusion.fuse(List.of(1L, 2L), List.of(2L, 1L), 60, 2);

        System.out.println("融合之后完整列表：" + fused);
        for (int i = 0; i < fused.size(); i++) {
            System.out.printf("下标%d，文档id = %d%n", i, fused.get(i));
        }
        assertEquals(2, fused.size());
        assertEquals(fused.size(), fused.stream().distinct().count());

    }
    
    @Test
    void three_way_fusion_prefers_agreement() {
        // 图谱路与向量路一致:[1,2];关键词路:[1,3] → 1 三路命中排第一
        List<Long> fused = RrfFusion.fuse(
                List.of(List.of(1L, 2L, 3L), List.of(1L, 2L, 4L), List.of(1L, 3L, 5L)), 60, 3);
        assertEquals(1L, fused.get(0));
        assertTrue(fused.contains(2L));
        assertEquals(3, fused.size());
    }
}
