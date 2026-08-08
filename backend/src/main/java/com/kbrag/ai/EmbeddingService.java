package com.kbrag.ai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;


@Service
public class EmbeddingService {
    private final EmbeddingModel model;
    private static final int BATCH = 32; //批量 32:每 32 个 chunk 打包成一次请求发给 API,返回 32 个向量

    /**
        | batch | 优点      | 代价 |
        | ---   | ---       | --- |
        | 1     | 失败只影响 1 条,重试粒度最小 | 网络往返次数最多,总耗时最长 |
        | 32    | 往返次数少 32 倍,总耗时短 | 一批失败要整批重试(我们代码里是整批重试 3 次) |
        | 128+  | 往返更少 | 超服务商上限直接 400;单次请求体变大,慢请求拖累更多数据 |
     */

    public EmbeddingService(
            @Value("${app.llm.embedding-base-url}") String baseUrl,
            @Value("${app.llm.embedding-model}") String modelName,
            @Value("${app.llm.embedding-api-key}") String apiKey) {
        this.model = OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    /** 批量向量化，失败自动重试 2 次；单条 512 token 截断（BGE-M3 上限）。 */
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < texts.size(); i += BATCH) {
            List<String> batch = texts.subList(i, Math.min(i + BATCH, texts.size()));
            List<Embedding> embeddings = retry(() ->
                    model.embedAll(batch.stream().map(dev.langchain4j.data.segment.TextSegment::from).toList())
                        .content());
            embeddings.forEach(e -> out.add(e.vector()));
        }
        return out;
    }

    private <T> T retry(CheckedSupplier<T> fn) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try { return fn.get(); }
            catch (RuntimeException e) { last = e; }
        }
        throw last;
    }

    private interface CheckedSupplier<T> { T get(); }

}
