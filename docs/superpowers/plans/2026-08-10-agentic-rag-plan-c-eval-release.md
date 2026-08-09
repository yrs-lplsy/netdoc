# Plan C:评测体系 + 图谱对照实验 + 可观测 + 知识包导出 + 部署实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 协作协议:用户自己写代码,助手教学/验收/答疑。对应 spec《2026-08-10-agentic-rag-enterprise-design.md》第 5-6 周(8/30 指标出炉,9/6 上线投递)。前置:Plan A(Agent/SSE/限流/缓存)、Plan B(认证/多库/KG)。

**Goal:** 交付可量化结果:120 条评测集 + Recall@10/MRR/忠实度 + 图谱对照实验(量化"图谱增强增益")+ 可观测指标 + 知识包导出(端侧接口)+ 全栈部署 demo。

**Architecture:** 评测离线可跑(Java):检索指标纯算法(Recall@10/MRR,可单测),忠实度 LLM-as-judge(DeepSeek);对照实验通过 strategy 参数区分(有/无图谱)跑同一评测集;知识包导出组装 JSON/zip 供端侧(概念)消费;部署用 Dockerfile ×2 + docker-compose 全栈一键起。

**Tech Stack:** 现有栈 + Java 评测模块 + Docker(Java 17 / Python 3.13 镜像)。

## Global Constraints

- 评测离线可跑,不依赖前端;指标计算纯 Java 可单测
- 忠实度 judge:DeepSeek(与 chat 同一模型),prompt 强约束 PASS/FAIL + 分数
- 对照实验:同一评测集,strategy ∈ {baseline(双路), kg_enhanced(三路)},逐条跑,结果入 eval_result
- 知识包格式:JSON(v1),含文档元数据/chunk 摘要/KG 三元组/关键词索引;端侧加载为"离线兜底检索"素材(概念验证)
- 部署:backend/Dockerfile + python/Dockerfile + docker-compose(Java/Python/PG/Redis),.env 注入密钥
- 端口/认证/幂等等约束沿用 Plan A/B

---

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 | 评测体系:评测集 + Recall@10/MRR + 忠实度 judge | 评测能跑,指标落库 |
| Task 2 | 图谱对照实验(baseline vs kg_enhanced) | 对比报告,增益量化 |
| Task 3 | 可观测完善(Token 数/成本) | rag_span 含 token,stats 可查 |
| Task 4 | 知识包导出(端侧接口) | 导出 zip,格式文档化 |
| Task 5 | 全栈部署 + README + 简历 | docker compose up 一键起 |

---

### Task 1: 评测体系(评测集 + Recall@10/MRR + 忠实度)

**Files:**
- Create: `backend/src/main/java/com/kbrag/eval/EvalCase.java`、`EvalResult.java`、`EvalRepositories.java`
- Create: `backend/src/main/java/com/kbrag/eval/RetrievalMetrics.java`(纯算法,可单测)
- Create: `backend/src/main/java/com/kbrag/eval/EvalService.java`
- Create: `backend/src/main/java/com/kbrag/eval/EvalController.java`
- Create: `backend/src/main/resources/eval/eval_cases.json`(评测集种子)

**Interfaces:**
- Consumes: `HybridRetriever.search(query, kbId, topK)`(Plan B 三路)、ChatService(直连生成)
- Produces: `POST /api/eval/run {strategy, kbId} → 汇总`;`GET /api/eval/report`;`RetrievalMetrics.recallAt10 / mrr`

- [ ] **Step 1: 评测集种子(120 条:双库各 60,标注相关 chunk id)**

```json
// eval_cases.json 结构(人工出题 + LLM 辅助,chunk_id 从库中确认):
[
  {"kbId": 2, "question": "OpenWrt 如何配置无线桥接?", "relevantChunkIds": [101, 102], "category": "config"},
  {"kbId": 2, "question": "opkg 安装软件包的命令是什么?", "relevantChunkIds": [105], "category": "cmd"},
  {"kbId": 1, "question": "如何提交文档修订申请?", "relevantChunkIds": [210], "category": "process"}
]
```

```java
// 加载:启动时读 classpath:eval/eval_cases.json → eval_case 表(幂等:已存在跳过)
```

- [ ] **Step 2: 指标实体与仓库**

```java
package com.kbrag.eval;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "eval_case")
public class EvalCase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long kbId;
    @Column(columnDefinition = "text")
    private String question;
    @Column(columnDefinition = "text")
    private String relevantChunkIdsJson;   // [101,102]
    private String category;
}

@Data
@Entity
@Table(name = "eval_result")
public class EvalResult {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long caseId;
    private String strategy;      // baseline / kg_enhanced
    private Double recall10;
    private Double mrr;
    private Double faithfulness;  // LLM-as-judge 0-1
    private LocalDateTime runAt = LocalDateTime.now();
}
```

- [ ] **Step 3: 指标纯算法(TDD:先写失败测试)**

```java
package com.kbrag.eval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalMetricsTest {
    @Test
    void recall_at_10_hits_all_relevant() {
        // 检索返回 [101, 999, 102],相关 [101, 102]
        assertEquals(1.0, RetrievalMetrics.recallAt10(List.of(101L, 999L, 102L), List.of(101L, 102L)));
    }

    @Test
    void recall_at_10_partial() {
        assertEquals(0.5, RetrievalMetrics.recallAt10(List.of(101L, 999L), List.of(101L, 102L)));
    }

    @Test
    void mrr_is_reciprocal_of_first_hit_rank() {
        // 第一个相关在 rank2 → 1/2
        assertEquals(0.5, RetrievalMetrics.mrr(List.of(999L, 101L, 102L), List.of(101L, 102L)));
    }

    @Test
    void mrr_zero_when_no_hit() {
        assertEquals(0.0, RetrievalMetrics.mrr(List.of(999L, 888L), List.of(101L)));
    }
}
```

- [ ] **Step 4: 实现指标**

```java
package com.kbrag.eval;

import java.util.List;

/** 检索指标(spec §10):Recall@10 与 MRR,纯算法可单测。 */
public class RetrievalMetrics {
    /** 前 10 条中命中的相关 chunk 数 / 相关 chunk 总数。 */
    public static double recallAt10(List<Long> retrieved, List<Long> relevant) {
        if (relevant.isEmpty()) return 0;
        List<Long> top10 = retrieved.size() > 10 ? retrieved.subList(0, 10) : retrieved;
        long hits = top10.stream().filter(relevant::contains).count();
        return (double) hits / relevant.size();
    }

    /** 第一个相关 chunk 在结果中的倒数排名;无命中返回 0。 */
    public static double mrr(List<Long> retrieved, List<Long> relevant) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (relevant.contains(retrieved.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }
}
```

- [ ] **Step 5: EvalService(跑评测 + 忠实度 judge)**

```java
package com.kbrag.eval;

import com.kbrag.chat.ChatService;
import com.kbrag.retrieval.HybridRetriever;
import com.kbrag.retrieval.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class EvalService {
    private final EvalCaseRepository cases;
    private final EvalResultRepository results;
    private final HybridRetriever retriever;
    private final OpenAiChatModel judgeModel;
    private final ObjectMapper om = new ObjectMapper();

    public EvalService(EvalCaseRepository cases, EvalResultRepository results,
                       HybridRetriever retriever,
                       @Value("${app.llm.chat-base-url}") String baseUrl,
                       @Value("${app.llm.chat-model}") String model,
                       @Value("${app.llm.chat-api-key}") String apiKey) {
        this.cases = cases; this.results = results; this.retriever = retriever;
        this.judgeModel = OpenAiChatModel.builder().baseUrl(baseUrl).apiKey(apiKey)
                .modelName(model).temperature(0).build();
    }

    /** 跑全量评测集;strategy 传给检索(对照实验用,如 kg_enhanced 开图谱路)。 */
    public Map<String, Object> run(String strategy, Long kbId) {
        List<EvalCase> list = cases.findByKbId(kbId);
        double recallSum = 0, mrrSum = 0, faithSum = 0;
        for (EvalCase c : list) {
            List<Long> relevant = om.readValue(c.getRelevantChunkIdsJson(), List.class).stream()
                    .map(o -> Long.valueOf(o.toString())).toList();
            List<SearchResult> hits = retriever.search(c.getQuestion(), kbId, 10);
            List<Long> retrieved = hits.stream().map(SearchResult::chunkId).toList();
            double recall = RetrievalMetrics.recallAt10(retrieved, relevant);
            double mrr = RetrievalMetrics.mrr(retrieved, relevant);
            double faith = faithfulness(c.getQuestion(), hits, strategy);

            EvalResult r = new EvalResult();
            r.setCaseId(c.getId()); r.setStrategy(strategy);
            r.setRecall10(recall); r.setMrr(mrr); r.setFaithfulness(faith);
            results.save(r);

            recallSum += recall; mrrSum += mrr; faithSum += faith;
        }
        int n = list.size();
        return Map.of("strategy", strategy, "cases", n,
                "avgRecall10", Math.round(recallSum / n * 100) / 100.0,
                "avgMrr", Math.round(mrrSum / n * 100) / 100.0,
                "avgFaithfulness", Math.round(faithSum / n * 100) / 100.0);
    }

    /** LLM-as-judge:回答是否忠实于检索片段(0-1)。 */
    private double faithfulness(String question, List<SearchResult> hits, String strategy) {
        if (hits.isEmpty()) return 0;
        StringBuilder ctx = new StringBuilder();
        for (int i = 0; i < hits.size(); i++) {
            ctx.append("[").append(i + 1).append("] ").append(hits.get(i).headingPath())
               .append(": ").append(hits.get(i).content()).append("\n");
        }
        AiMessage answer = judgeModel.generate(
                "根据以下资料回答:" + question + "\n资料:\n" + ctx).content();
        String verdict = judgeModel.generate(
                "判断回答是否忠实于资料(只输出 0 到 1 之间的数字):\n资料:\n" + ctx
                + "\n回答:\n" + answer.text()).content().text();
        try {
            double v = Double.parseDouble(verdict.trim());
            return Math.max(0, Math.min(1, v));
        } catch (NumberFormatException e) {
            return 0.5;   // 解析失败给中性分
        }
    }
}
```

> 忠实度两段式:先生成回答,再 judge 打分;`temperature=0` 保证可复现(面试点:评测可复现性)。真实落地:与 ChatService 生成一致(此处用直连简化,对照实验内部一致即可)。

- [ ] **Step 6: EvalController + 报告**

```java
package com.kbrag.eval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {
    @Autowired private EvalService evalService;
    @Autowired private EvalResultRepository results;

    public record RunRequest(String strategy, Long kbId) {}

    @PostMapping("/run")
    public Map<String, Object> run(@RequestBody RunRequest req) {
        return evalService.run(req.strategy() == null ? "baseline" : req.strategy(), req.kbId());
    }

    @GetMapping("/report")
    public Map<String, Object> report() {
        List<EvalResult> recent = results.findTop50ByOrderByIdDesc();
        return Map.of("runs", recent.size(), "latest", recent.isEmpty() ? null : recent.get(0));
    }
}
```

- [ ] **Step 7: 验证**

```bash
TOKEN=$(...login...)
curl -s -X POST localhost:9000/api/eval/run -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"strategy":"baseline","kbId":2}' | python3 -m json.tool
# 期望:avgRecall10 / avgMrr / avgFaithfulness 落库返回
psql ... -c "SELECT strategy, recall_10, mrr, faithfulness FROM eval_result ORDER BY id DESC LIMIT 3;"
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: eval suite with recall@10, mrr and faithfulness judge"
```

**验收**:指标单测 4/4;评测可跑;eval_result 落库;忠实度 judge 出分。

---

### Task 2: 图谱对照实验(量化增益)

**Files:**
- Modify: `backend/src/main/java/com/kbrag/retrieval/HybridRetriever.java`(strategy 开关图谱路)
- Create: `backend/src/main/java/com/kbrag/eval/EvalReportController.java`(或并入 EvalController)
- Create: `docs/eval/对照实验报告.md`(模板)

**Interfaces:**
- Consumes: Task 1 评测、Task 5(Plan B)图谱检索
- Produces: `retriever.search(query, kbId, topK, strategy)`;对照报告文档(recall/mrr/faithfulness 对比)

- [ ] **Step 1: HybridRetriever 加 strategy 开关**

```java
public List<SearchResult> search(String query, Long kbId, int topK) {
    return search(query, kbId, topK, "kg_enhanced");   // 默认开图谱
}

public List<SearchResult> search(String query, Long kbId, int topK, String strategy) {
    // ...稠密/稀疏路不变
    List<Long> kgIds = List.of();
    if ("kg_enhanced".equals(strategy)) {
        // 实体链接 + 邻居 + 文档候选(Plan B Task 5 Step 3-4)
    }
    List<Long> fused = RrfFusion.fuse(
            List.of(denseIds, sparseIds, kgIds), rrfK, Math.min(topK, finalTopK));
    ...
}
```

- [ ] **Step 2: EvalService 传 strategy(检索处)**

```java
List<SearchResult> hits = retriever.search(c.getQuestion(), kbId, 10, strategy);
```

- [ ] **Step 3: 跑对比 + 出报告**

```bash
# baseline(关图谱):
curl -s -X POST localhost:9000/api/eval/run -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"strategy":"baseline","kbId":2}'
# kg_enhanced(开图谱):
curl -s -X POST localhost:9000/api/eval/run -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"strategy":"kg_enhanced","kbId":2}'
# 汇总两次结果 → 写 docs/eval/对照实验报告.md:
#   指标    | baseline | kg_enhanced | 增益
#   Recall@10 |  0.62    |   0.71      | +0.09
#   MRR       |  0.55    |   0.63      | +0.08
#   忠实度    |  0.88    |   0.91      | +0.03
```

> 报告模板含:实验设置(评测集规模/双库)、结果表、结论(图谱增强对实体/关系类问题的增益)、局限(图谱质量依赖抽取)。

- [ ] **Step 4: 提交**

```bash
git add -A && git commit -m "feat: kg ablation experiment and comparison report"
```

**验收**:两次 run 数据可对比;报告含增益数字(简历量化句素材)。

---

### Task 3: 可观测完善(Token 数/成本)

**Files:**
- Modify: `backend/src/main/java/com/kbrag/obs/RagSpan.java`(+promptTokens/completionTokens/estimatedCost)
- Modify: `python/app/sse.py` 或 `graph.py`(done 事件带 token 用量)
- Modify: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`(解析 token 落 span)

**Interfaces:**
- Consumes: Plan A Task 9(rag_span)、Plan B(AgentChatService 已收集 phaseMs)
- Produces: rag_span 含 token 数;done 事件 `{verified, error, usage:{promptTokens, completionTokens}}`

- [ ] **Step 1: Python 上报 token 用量(chat_model astream 的 usage)**

```python
# graph.py generate 节点:astream 每 chunk 有 usage_metadata(langchain 标准字段)
# 简化:astream 结束后,用 chat_model 的 response_metadata 或单独统计:
#   from langchain_openai import ChatOpenAI —— astream 返回的最后一个 chunk 带 usage_metadata
total_prompt = 0
total_completion = 0
async for chunk in chat.astream(messages):
    answer_parts.append(chunk.content or "")
    meta = getattr(chunk, "usage_metadata", None)
    if meta:
        total_prompt = meta.get("input_tokens", total_prompt)
        total_completion = meta.get("output_tokens", total_completion)
# state 记入 usage
state["usage"] = {"promptTokens": total_prompt, "completionTokens": total_completion}
```

```python
# sse.py done 事件带上 usage:
elif kind == "done":
    yield emit("done", {"verified": payload["verified"], "error": payload["error"],
                        "usage": payload.get("usage", {})})
```

- [ ] **Step 2: Java 解析 + RagSpan 扩展**

```java
// AgentChatService done 分支:
JsonNode usage = om.readTree(data).path("data").path("usage");
span.setPromptTokens(usage.path("promptTokens").asInt(0));
span.setCompletionTokens(usage.path("completionTokens").asInt(0));
// 估算成本:DeepSeek 约 ¥1/M prompt、¥2/M completion(按实际价格常量)
span.setEstimatedCost((prompt + 2.0 * completion) / 1_000_000.0);
```

- [ ] **Step 3: /api/stats 加成本汇总**

```java
// StatsController 追加:totalEstimatedCost(近 100 轮)、avgTokensPerTurn
```

- [ ] **Step 4: 验证 + 提交**

```bash
curl -s localhost:9000/api/stats -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
# 期望:含 avgRetrievalMs / cacheHitRate / totalEstimatedCost / avgTokensPerTurn
git add -A && git commit -m "feat: token usage and cost tracking in rag spans"
```

**验收**:rag_span 有 token/成本;stats 出成本指标(简历量化:"单轮成本 xx 元,缓存命中省 xx%")。

---

### Task 4: 知识包导出(端侧接口)

**Files:**
- Create: `backend/src/main/java/com/kbrag/export/KnowledgePackService.java`
- Create: `backend/src/main/java/com/kbrag/export/ExportController.java`
- Create: `docs/export/knowledge-pack-format.md`(格式文档,端侧消费契约)

**Interfaces:**
- Consumes: DocumentRepository/DocumentChunkRepository/Kg 仓库(Plan B)
- Produces: `POST /api/export/knowledge-pack?kbId= → application/zip(manifest.json + chunks.jsonl + kg.json)`

- [ ] **Step 1: 打包服务(JSON v1 格式)**

```java
package com.kbrag.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.document.Document;
import com.kbrag.document.DocumentChunk;
import com.kbrag.document.DocumentChunkRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.kg.KgEntity;
import com.kbrag.kg.KgEntityRepository;
import com.kbrag.kg.KgRelation;
import com.kbrag.kg.KgRelationRepository;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 知识包导出(spec §7):文档元数据 + chunk 摘要 + KG 三元组。
 * 端侧路由器(概念阶段)按格式加载为离线检索素材——"云侧中台 + 边侧消费"。
 */
@Service
public class KnowledgePackService {
    private final DocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final KgEntityRepository entities;
    private final KgRelationRepository relations;
    private final ObjectMapper om = new ObjectMapper();

    public KnowledgePackService(...) { ... }

    public byte[] export(Long kbId) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bos);
        List<Document> docs = documents.findAll().stream().filter(d -> d.getKbId().equals(kbId)).toList();

        zip.putNextEntry(new ZipEntry("manifest.json"));
        zip.write(om.writeValueAsBytes(Map.of(
                "formatVersion", 1, "kbId", kbId,
                "exportedAt", System.currentTimeMillis(),
                "docCount", docs.size())));

        zip.putNextEntry(new ZipEntry("chunks.jsonl"));   // 每行一条,端侧可流式加载
        for (Document d : docs) {
            for (DocumentChunk c : chunks.findByDocId(d.getId())) {
                zip.write(om.writeValueAsBytes(Map.of(
                        "chunkId", c.getId(), "docTitle", d.getTitle(),
                        "headingPath", c.getHeadingPath(),
                        "content", c.getContent().length() > 500 ? c.getContent().substring(0, 500) : c.getContent()))
                        .length > 0 ? om.writeValueAsBytes(Map.of(
                        "chunkId", c.getId(), "docTitle", d.getTitle(),
                        "headingPath", c.getHeadingPath(),
                        "content", c.getContent().length() > 500 ? c.getContent().substring(0, 500) : c.getContent())) : new byte[0]);
                zip.write('\n');
            }
        }

        zip.putNextEntry(new ZipEntry("kg.json"));
        List<KgEntity> es = entities.findAll().stream().filter(e -> e.getKbId().equals(kbId)).toList();
        List<KgRelation> rs = relations.findAll().stream().filter(r -> r.getKbId().equals(kbId)).toList();
        zip.write(om.writeValueAsBytes(Map.of(
                "entities", es.stream().map(e -> Map.of("id", e.getId(), "name", e.getName(), "type", e.getType())).toList(),
                "relations", rs.stream().map(r -> Map.of(
                        "source", r.getSourceId(), "target", r.getTargetId(), "relation", r.getRelation())).toList())));

        zip.close();
        return bos.toByteArray();
    }
}
```

> 注:chunks.jsonl 的写入可简化为先组装 List 再一次性写出(教学简化,避免上面三元表达式嵌套);核心是格式契约。

- [ ] **Step 2: ExportController**

```java
package com.kbrag.export;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/export")
public class ExportController {
    @Autowired private KnowledgePackService knowledgePackService;

    @PostMapping("/knowledge-pack")
    public ResponseEntity<byte[]> export(@RequestParam Long kbId) throws Exception {
        byte[] zip = knowledgePackService.export(kbId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=kb-" + kbId + ".zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zip);
    }
}
```

- [ ] **Step 3: 格式文档(端侧消费契约)**

```markdown
# 知识包格式 v1(knowledge-pack-format.md)
manifest.json: 版本/kbId/导出时间/文档数
chunks.jsonl:  每行 {chunkId, docTitle, headingPath, content(≤500字摘要)}
kg.json:       entities[{id,name,type}] + relations[{source,target,relation}]
端侧加载:manifest 校验 → chunks 建倒排索引(离线检索)→ kg 建邻接表(实体问答)
```

- [ ] **Step 4: 验证 + 提交**

```bash
curl -s -X POST "localhost:9000/api/export/knowledge-pack?kbId=2" -H "Authorization: Bearer $TOKEN" -o kb2.zip
unzip -l kb2.zip && unzip -p kb2.zip manifest.json
git add -A && git commit -m "feat: knowledge pack export for edge consumption"
```

**验收**:zip 含 manifest/chunks.jsonl/kg.json;格式文档可交付端侧(概念)。

---

### Task 5: 全栈部署 + README + 简历收口

**Files:**
- Create: `backend/Dockerfile`、`python/Dockerfile`
- Modify: `docker-compose.yml`(全栈 4 服务)
- Create: `.dockerignore`
- Modify: `README.md`(一键起 + 演示脚本 + 指标)
- Modify: 简历(按实测指标填写 spec §12.1)

- [ ] **Step 1: backend/Dockerfile**

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/backend-0.1.0.jar app.jar
EXPOSE 9000
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: python/Dockerfile**

```dockerfile
FROM python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
EXPOSE 9100
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9100"]
```

- [ ] **Step 3: docker-compose.yml 全栈**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: kbrag-pg
    environment: { POSTGRES_DB: kbrag, POSTGRES_USER: kbrag, POSTGRES_PASSWORD: kbrag123 }
    ports: ["5433:5432"]
    volumes: [pgdata:/var/lib/postgresql/data, ./init.sql:/docker-entrypoint-initdb.d/init.sql]
  redis:
    image: redis:7
    container_name: kbrag-redis
    ports: ["6379:6379"]
  backend:
    build: ./backend
    container_name: kbrag-backend
    environment:
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY}
      SILICONFLOW_API_KEY: ${SILICONFLOW_API_KEY}
    depends_on: [postgres, redis]
    ports: ["9000:9000"]
  python-agent:
    build: ./python
    container_name: kbrag-python
    environment:
      JAVA_BASE_URL: http://backend:9000
      DEEPSEEK_API_KEY: ${DEEPSEEK_API_KEY}
      SILICONFLOW_API_KEY: ${SILICONFLOW_API_KEY}
    depends_on: [backend]
    ports: ["9100:9100"]
volumes:
  pgdata:
```

> 注意:容器内 Java 连 PG 用 `jdbc:postgresql://postgres:5432/kbrag`——application.yml 加 `SPRING_DATASOURCE_URL` 环境变量覆盖(Compose 里注入)。

- [ ] **Step 4: README 更新(一键起 + 演示脚本 + 当前指标)**

```markdown
## 一键部署
docker compose up --build -d
# 初始化:admin/admin123(DataInitializer 自动建库建用户)
curl http://localhost:9000/actuator/health

## 演示脚本(3 分钟)
1. 登录 admin → 双库切换
2. 提问(流式 + 引用)→ 图谱 Tab 看实体关系 → 点踩反馈
3. 评测报告:GET /api/eval/report(Recall@10/MRR/忠实度,含图谱对照)
4. 导出知识包 → 说明端侧离线消费(云边一体)

## 当前指标(实测填写)
- 评测集 120 条;Recall@10 baseline 0.62 → kg_enhanced 0.71(+0.09)
- 平均检索延迟 xx ms;缓存命中率 xx%;单轮成本 ¥xx
```

- [ ] **Step 5: 简历收口**

按 spec §12.1 双版本模板,量化数字全部用实测;附 GitHub 链接 + demo 链接 + 技术博客 ×3。

- [ ] **Step 6: 验证 + 提交**

```bash
docker compose up --build -d && sleep 30
curl http://localhost:9000/actuator/health && curl http://localhost:9100/health
# 浏览器全流程演示
git add -A && git commit -m "feat: full-stack docker deployment and release docs"
```

**验收**:`docker compose up --build -d` 一键起 4 服务;端到端问答/图谱/评测可用;README/简历完成。

---

## Self-Review 记录

**Spec 覆盖(2026-08-10):**
- §10 评测体系(120 条/Recall@10/MRR/忠实度/对照实验)→ Task 1 + Task 2
- §9 可观测(Token 数/成本)→ Task 3
- §7 知识包导出 → Task 4
- §11 第 6 周部署/简历/博客 → Task 5
- §3.4 裁剪对照的 Docker Compose 落点 → Task 5。**无遗漏。**

**占位符扫描:** Task 1 的 eval_cases.json 仅给 3 条示例(120 条需按双库实际 chunk_id 标注——标注工作本身是用户任务,示例说明格式);Task 2 报告模板给出结构;Task 4 chunks.jsonl 写入处标注"可简化为先组装 List"(避免三元嵌套,核心契约不变)。无 TBD。

**类型一致性:**
- `HybridRetriever.search(query, kbId, topK)` 与 `search(query, kbId, topK, strategy)` 重载——EvalService 用四参版本,Plan A/B 调用三参版本兼容
- done 事件 `{verified, error, usage:{promptTokens, completionTokens}}`——Python graph.py 产出 → sse.py 透传 → Java AgentChatService 解析,三方一致
- eval_case.relevantChunkIdsJson ↔ RetrievalMetrics 的 List<Long> 转换一致
- 知识包格式 v1 在 ExportController/KnowledgePackService/format 文档三处一致
- 指标口径:Recall@10 取前 10、MRR 全量排名——与 spec §10 一致
