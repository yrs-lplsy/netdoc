package com.kbrag.retrieval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/** Reciprocal Rank Fusion: score = sum(1 / (k + rank)), rank 从 1 开始。 */
public class RrfFusion {
    public static List<Long> fuse(List<Long> denseIds, List<Long> sparseIds, int k, int topN) {
        Map<Long, Double> scores = new HashMap<>();
        add(scores, denseIds, k);   // 加入向量检索排名得分
        add(scores, sparseIds, k);  // 加入全文检索排名得分
        // return scores.entrySet().stream()
        //         .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
        //         .limit(topN)        // 取前 top‑N
        //         .map(Map.Entry::getKey)
        //         .toList();

        return scores.entrySet().stream()
            .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
            // .peek(entry->System.out.printf("id:%d score:%.6f%n",entry.getKey(),entry.getValue()))
            .limit(topN)
            .map(Map.Entry::getKey)
            .toList();
    }
    private static void add(Map<Long, Double> scores, List<Long> ids, int k) {
        for (int i = 0; i < ids.size(); i++) {
            scores.merge(ids.get(i), 1.0 / (k + i + 1), Double::sum);
        }
    }
}
