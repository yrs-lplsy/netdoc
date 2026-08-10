package com.kbrag.cache;

import java.util.Map;

/** 余弦相似度(纯算法,手写不引依赖——面试点:点积/模长,与 pgvector <=> 同一数学)。 */
public class CosineSimilarity {
    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** 从候选向量中选相似度超过 threshold 的最高分 id;无命中返回 null(可单测的纯决策)。 */
    public static String selectBest(Map<String, float[]> candidates, float[] query, double threshold) {
        String bestId = null;
        double bestSim = threshold;
        for (Map.Entry<String, float[]> e : candidates.entrySet()) {
            double sim = cosine(query, e.getValue());
            if (sim > bestSim) { bestSim = sim; bestId = e.getKey(); }
        }
        return bestId;
    }
}
