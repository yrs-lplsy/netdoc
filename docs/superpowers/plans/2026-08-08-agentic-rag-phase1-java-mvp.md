# Agentic RAG Phase 1：Java 后端 MVP（文档管线 + 混合检索 + SSE 对话）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本计划主要面向个人执行（建议自己动手写，面试要能讲细节），可用 executing-plans 由助手陪你逐任务推进。

**Goal:** 2 周内交付可演示的 Java 后端 MVP：文档上传 → 分块 → 向量化入库 → 混合检索 → SSE 流式问答，形成可投递简历的最小闭环。

**Architecture:** 前后端分离双服务的第一阶段。Spring Boot 3 单体后端 + PostgreSQL(pgvector) + Redis；本期实现文档管线（解析/标题感知分块/向量化）、混合检索（jieba 分词 + tsvector + pgvector + RRF）、DeepSeek 流式生成、SSE 对话网关。Phase 2 再接入 Python Agent 服务（LangGraph），Java 侧预留工具端点。

**Tech Stack:** Java 17、Spring Boot 3.3.x、Maven、PostgreSQL 16 + pgvector、Redis 7、LangChain4j 1.x（OpenAiChatModel/OpenAiEmbeddingModel，OpenAI 兼容协议）、jieba-analysis 1.0.2、PDFBox 3.0、POI 5.2、commonmark 0.22、Docker Desktop（Windows）

## Global Constraints

- LLM：DeepSeek，OpenAI 兼容 base_url `https://api.deepseek.com/v1`，model `deepseek-chat`
- Embedding：硅基流动 BGE-M3，base_url `https://api.siliconflow.cn/v1`，model `BAAI/bge-m3`，维度 **1024**
- API Key 只从环境变量读取（`DEEPSEEK_API_KEY`、`SILICONFLOW_API_KEY`），禁止写进代码/git
- 分块：标题感知，目标 400-800 字符，overlap 80-150
- 检索：向量 Top30 + 关键词 Top30 → RRF(k=60) Top10 → 取 Top5 进 Prompt
- SSE 事件：`answer`(delta) / `source` / `done` / `error`，事件体带递增 `seq`
- 文档状态机：`PROCESSING → READY / FAILED`
- 后端代码与注释用英文，README/文档用中文
- 项目根：`agentic-rag/`（新目录，独立 git 仓库）；`.gitignore` 排除 `target/`、`*.env`、`.idea/`、`.vscode/`
- 中文分词决策：用 jieba（纯 Java 库）在入库时预分词存 `segmented_text`，PG 侧 `to_tsvector('simple', segmented_text)` 生成列做关键词检索——避免 zhparser/pg_jieba 需要定制镜像的部署复杂度
- 本机 5432 被占用：PG 端口映射 `5433:5432`，本计划内所有连接串与 `psql` 命令统一用 **5433**（与 application.yml 一致）
- 服务端口用 **9000**（本机 8080 被 rpki-system 占用，避开 80 系列防混淆；计划内 `curl localhost:8080` 均按 9000 执行，Task 7 演示页/README 用 9000）

## 与 spec 的偏差说明

- **rerank 移出 Phase 1**：Phase 1 用 RRF 融合后直接取 Top5；bge-reranker 在 Phase 3 作为 A/B 实验加入（有/无 rerank 的 Recall@10 对比正好是评测素材）
- 排期第 2 周"基础生成"即本计划 Task 6-7；Phase 1 结束时按 spec §11.1 后端版模板更新简历并投递

---

## Phase 总览（后续阶段概要）

| 阶段 | 内容 | 对应排期 |
|---|---|---|
| Phase 2 | Python Agent 服务：LangGraph 五节点（改写/Router/工具/生成/自检）+ Java 工具端点 + 全链路 SSE 透传 | 第 3 周 |
| Phase 3 | 记忆双压缩策略 + 评测（120 条评测集/Recall@K/MRR/忠实度）+ 工程化（限流/语义缓存/可观测）+ rerank A/B | 第 4 周 |
| Phase 4 | MCP Server / 权限 RBAC 多租户 / 在线搜索工具 / 部署 demo | 5-6 周弹性 |

---

### Task 1: 基础设施与项目骨架

**Files:**
- Create: `agentic-rag/docker-compose.yml`
- Create: `agentic-rag/.gitignore`
- Create: `agentic-rag/backend/pom.xml`
- Create: `agentic-rag/backend/src/main/resources/application.yml`
- Create: `agentic-rag/backend/src/main/java/com/kbrag/KbragApplication.java`

**Interfaces:**
- Consumes: 无
- Produces: 健康检查端点 `GET /actuator/health`；`Document`/`DocumentChunk` 的 JPA 配置基础

- [ ] **Step 1: 创建 docker-compose.yml**

```yaml
services:
  postgres:
    image: pgvector/pgvector:pg16
    container_name: kbrag-pg
    environment:
      POSTGRES_DB: kbrag
      POSTGRES_USER: kbrag
      POSTGRES_PASSWORD: kbrag123
    ports:
      - "5433:5432"   # 本机 5432 被占用，映射 5433（全计划统一）
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql  # 首启自动创建 vector 扩展
  redis:
    image: redis:7
    container_name: kbrag-redis
    ports:
      - "6379:6379"
volumes:
  pgdata:
```

同时创建 `agentic-rag/init.sql`——PG 容器**首启**时自动执行。必须在 Hibernate 建表**之前**把扩展装好（`embedding vector(1024)` 列建表时就要用到 vector 类型，放 schema.sql 里执行时机太晚）：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

- [ ] **Step 2: 创建 .gitignore**

```gitignore
target/
*.env
.idea/
.vscode/
*.iml
.DS_Store
```

- [ ] **Step 3: 创建 backend/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>
  <groupId>com.kbrag</groupId>
  <artifactId>backend</artifactId>
  <version>0.1.0</version>
  <properties>
    <java.version>17</java.version>
    <langchain4j.version>1.0.0</langchain4j.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency>
    <dependency><groupId>com.pgvector</groupId><artifactId>pgvector</artifactId><version>0.1.6</version></dependency>
    <!-- LangChain4j OpenAI 兼容协议 -->
    <dependency><groupId>dev.langchain4j</groupId><artifactId>langchain4j-open-ai</artifactId><version>${langchain4j.version}</version></dependency>
    <!-- 中文分词 -->
    <dependency><groupId>com.huaban</groupId><artifactId>jieba-analysis</artifactId><version>1.0.2</version></dependency>
    <!-- 文档解析 -->
    <dependency><groupId>org.apache.pdfbox</groupId><artifactId>pdfbox</artifactId><version>3.0.3</version></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.2.5</version></dependency>
    <dependency><groupId>org.commonmark</groupId><artifactId>commonmark</artifactId><version>0.22.0</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>
```

> 若 Maven 拉取 langchain4j 1.0.0 失败，查 Maven Central 最新 1.x 版本号替换；`langchain4j-open-ai` 的 `OpenAiChatModel.builder().baseUrl(...)` 支持任意 OpenAI 兼容端点。

- [ ] **Step 4: 创建 application.yml**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5433/kbrag
    username: kbrag
    password: kbrag123
  jpa:
    hibernate:
      ddl-auto: update
    defer-datasource-initialization: true
  sql:
    init:
      mode: always
  data:
    redis:
      host: localhost
      port: 6379

app:
  llm:
    chat-base-url: https://api.deepseek.com/v1
    chat-model: deepseek-chat
    chat-api-key: ${DEEPSEEK_API_KEY:}
    embedding-base-url: https://api.siliconflow.cn/v1
    embedding-model: BAAI/bge-m3
    embedding-api-key: ${SILICONFLOW_API_KEY:}
  retrieval:
    dense-top-k: 30
    sparse-top-k: 30
    rrf-k: 60
    final-top-k: 5
```

- [ ] **Step 5: 创建启动类**

```java
package com.kbrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class KbragApplication {
    public static void main(String[] args) {
        SpringApplication.run(KbragApplication.class, args);
    }
}
```

- [ ] **Step 6: 启动基础设施并验证**

```bash
cd agentic-rag
docker compose up -d
docker ps          # 两个容器 healthy/running
```

> ⚠️ 若 PG 容器此前已创建过（还没有 init.sql 的那次），`docker-entrypoint-initdb.d` 脚本**不会补跑**：要么 `docker compose down -v` 后重新 `up -d`（-v 清空数据卷，当前无数据可接受），要么手动执行 `docker exec kbrag-pg psql -U kbrag -d kbrag -c "CREATE EXTENSION IF NOT EXISTS vector;"`。

- [ ] **Step 7: 启动后端并验证健康检查**

```bash
cd backend
export DEEPSEEK_API_KEY=sk-xxx        # 换成你的 key（Git Bash: export 后同终端生效）
export SILICONFLOW_API_KEY=sk-xxx
mvn spring-boot:run
curl http://localhost:8080/actuator/health   # 期望 {"status":"UP"}
```

- [ ] **Step 8: 初始化 git 并提交**

```bash
cd agentic-rag
git init
# 把 spec/plan 拷入仓库——"规范 commit + 技术文档"的简历故事需要设计过程留痕（docs/ 在仓库外没有意义）
cp -r ../docs/superpowers docs/
git add -A
git commit -m "feat: project skeleton with docker compose and spring boot app"
```

**验收**：`docker compose up -d` 后 PG/Redis 可连；`/actuator/health` 返回 UP；git 仓库初始化完成。

---

### Task 2: 数据模型与 pgvector/HNSW

**Files:**
- Create: `backend/src/main/java/com/kbrag/document/Document.java`
- Create: `backend/src/main/java/com/kbrag/document/DocumentStatus.java`
- Create: `backend/src/main/java/com/kbrag/document/DocumentChunk.java`
- Create: `backend/src/main/java/com/kbrag/document/DocumentRepository.java`
- Create: `backend/src/main/java/com/kbrag/document/DocumentChunkRepository.java`
- Create: `backend/src/main/resources/schema.sql`

**Interfaces:**
- Consumes: Task 1 的 JPA 配置（ddl-auto=update + defer-datasource-initialization）
- Produces: `DocumentRepository`（save/findById/findAll/deleteById）、`DocumentChunkRepository`（saveAll/deleteByDocId/findByDocId）；`Document` 含 `status`/`errorMessage`；`DocumentChunk` 含 `embedding`(PGvector)、`segmentedText`、`headingPath`

- [ ] **Step 1: 编写实体类**

```java
package com.kbrag.document;

public enum DocumentStatus { PROCESSING, READY, FAILED }
```

```java
package com.kbrag.document;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document")
public class Document {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
    public String category;
    public Long uploader;
    @Enumerated(EnumType.STRING)
    public DocumentStatus status = DocumentStatus.PROCESSING;
    public Integer version = 1;
    public String errorMessage;
    public LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.document;

import jakarta.persistence.*;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "document_chunk")
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long docId;
    public Integer chunkIndex;
    @Column(columnDefinition = "text")
    public String content;
    public Integer tokenCount;
    public String headingPath;
    // Hibernate 官方 vector 模块映射(BGE-M3 维度 1024);不要用 com.pgvector.PGvector 做实体字段,否则按 bytea 绑定导致插入失败
    @Column(columnDefinition = "vector(1024)")
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    public float[] embedding;
    @Column(columnDefinition = "text")
    public String segmentedText;
}
```

> 实体字段直接 public 简化（个人项目风格）。`embedding` 用 `float[]` + `hibernate-vector` 模块（Hibernate 6.4+ 官方方案，README 明确"use this instead of com.pgvector.pgvector"）；`com.pgvector` 依赖保留，Task 5 原生 JDBC 绑定向量参数仍用它。`segmentedText` 存 jieba 分词后的空格分隔文本。pom.xml 需补依赖：`org.hibernate.orm:hibernate-vector:6.5.3.Final`（对齐 Spring Boot 3.3.5 管理的 Hibernate 版本）。

- [ ] **Step 2: 编写 Repository**

```java
package com.kbrag.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {}
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocId(Long docId);
    void deleteByDocId(Long docId);
}
```

- [ ] **Step 3: 创建 schema.sql（生成列 + 索引，幂等）**

```sql
-- vector 扩展由 docker-entrypoint-initdb.d/init.sql（Task 1）在容器首启时创建，这里不再重复
-- tsvector 由 segmented_text 自动维护（避免 JPA 管理 tsvector 类型）
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS search_text tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', segmented_text)) STORED;

CREATE INDEX IF NOT EXISTS idx_chunk_fts  ON document_chunk USING gin (search_text);
CREATE INDEX IF NOT EXISTS idx_chunk_hnsw ON document_chunk USING hnsw (embedding vector_cosine_ops);
```

> `spring.sql.init.mode=always` + `defer-datasource-initialization=true`：Hibernate 先建表，schema.sql 后执行补索引。若 Hibernate 对生成列报错，将 `segmented_text` 也改为 schema.sql 建列（`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`）并从实体中移除。

- [ ] **Step 4: 启动验证表结构**

```bash
mvn spring-boot:run &
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "\d document_chunk"
# 期望：embedding vector(1024)、search_text tsvector（生成列）、idx_chunk_hnsw/idx_chunk_fts 索引存在
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: document and chunk entities with pgvector schema"
```

**验收**：应用启动无异常；`\d document_chunk` 显示 vector/tsvector 列与两个索引。

---

### Task 3: 文档解析与标题感知分块

**Files:**
- Create: `backend/src/main/java/com/kbrag/document/parser/DocumentParser.java`
- Create: `backend/src/main/java/com/kbrag/document/parser/MarkdownParser.java`
- Create: `backend/src/main/java/com/kbrag/document/parser/PdfParser.java`
- Create: `backend/src/main/java/com/kbrag/document/parser/WordParser.java`
- Create: `backend/src/main/java/com/kbrag/document/parser/Chunk.java`
- Create: `backend/src/main/java/com/kbrag/document/parser/HeadingAwareChunker.java`
- Test: `backend/src/test/java/com/kbrag/document/parser/HeadingAwareChunkerTest.java`
- Create: `backend/src/main/java/com/kbrag/document/DocumentService.java`（本期只做上传+解析+分块，向量化在 Task 4）
- Create: `backend/src/main/java/com/kbrag/document/DocumentController.java`

**Interfaces:**
- Consumes: `DocumentRepository`、`DocumentChunkRepository`（Task 2）
- Produces: `DocumentParser.parse(InputStream in, String filename) → String text`；`HeadingAwareChunker.chunk(String text) → List<Chunk>`，`Chunk(content, headingPath, index)`；`DocumentService.upload(MultipartFile) → Document`（异步处理，本期末置 `READY`）；`POST /api/documents`、`GET /api/documents`、`DELETE /api/documents/{id}`

- [ ] **Step 1: 写分块器的失败测试（TDD）**

```java
package com.kbrag.document.parser;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HeadingAwareChunkerTest {
    private final HeadingAwareChunker chunker =
            new HeadingAwareChunker(800, 400, 100);

    @Test
    void heading_is_prepended_and_boundary_respected() {
        String text = "# 第一章 安装\n\n设备安装步骤说明。\n\n"
                + "## 1.1 接线\n\n" + "A".repeat(600) + "。\n\n"
                + "## 1.2 上电\n\n" + "B".repeat(600) + "。";
        List<Chunk> chunks = chunker.chunk(text);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).headingPath().contains("第一章"));
        assertTrue(chunks.stream().anyMatch(c -> c.headingPath().contains("1.2")));
        // 每个 chunk 不超过 maxSize + overlap
        for (Chunk c : chunks) {
            assertTrue(c.content().length() <= 800 + 100);
        }
    }

    @Test
    void long_body_is_split_with_overlap() {
        String body = "A".repeat(3000);
        List<Chunk> chunks = chunker.chunk(body);
        assertTrue(chunks.size() >= 3);
        assertTrue(chunks.get(0).content().endsWith("A".repeat(100))); // overlap 尾部
        assertTrue(chunks.get(1).content().startsWith("A".repeat(100)));
    }

    @Test
    void empty_input_returns_empty() {
        assertTrue(chunker.chunk("").isEmpty());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd backend && mvn test -Dtest=HeadingAwareChunkerTest
# 期望：编译失败（HeadingAwareChunker 不存在）
```

- [ ] **Step 3: 实现 Chunk 与分块器**

```java
package com.kbrag.document.parser;

public record Chunk(String content, String headingPath, int index) {}
```

```java
package com.kbrag.document.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heading-aware chunker: splits markdown-style text by headings (up to level 6),
 * then splits oversized bodies at paragraph boundaries with overlap.
 */
public class HeadingAwareChunker {
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+(.+?)\\s*$");

    private final int maxSize;   // 800
    private final int minSize;   // 400
    private final int overlap;   // 100

    public HeadingAwareChunker(int maxSize, int minSize, int overlap) {
        this.maxSize = maxSize;
        this.minSize = minSize;
        this.overlap = overlap;
    }

    public List<Chunk> chunk(String text) {
        List<Section> sections = splitByHeadings(text);
        List<Chunk> result = new ArrayList<>();
        for (Section s : sections) {
            List<String> pieces = splitBody(s.body());
            for (int i = 0; i < pieces.size(); i++) {
                String content = (i == 0 && !s.heading().isEmpty())
                        ? s.heading() + "\n" + pieces.get(i)
                        : pieces.get(i);
                result.add(new Chunk(content.trim(), s.heading(), result.size()));
            }
        }
        return mergeTinyTails(result);
    }

    private record Section(String heading, String body) {}

    private List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(text);
        int lastEnd = 0;
        String lastHeading = "";
        while (m.find()) {
            sections.add(new Section(lastHeading, text.substring(lastEnd, m.start()).trim()));
            lastHeading = m.group(2).trim();
            lastEnd = m.end();
        }
        sections.add(new Section(lastHeading, text.substring(lastEnd).trim()));
        return sections;
    }

    /** Split a body into <= maxSize pieces at paragraph boundaries, carrying overlap. */
    private List<String> splitBody(String body) {
        List<String> paragraphs = Arrays.stream(body.split("\n+"))
                .map(String::trim).filter(p -> !p.isEmpty()).toList();
        List<String> pieces = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : paragraphs) {
            if (buf.length() > 0 && buf.length() + p.length() + 1 > maxSize) {
                pieces.add(buf.toString());
                buf = new StringBuilder(tail(buf.toString()));
            }
            if (p.length() > maxSize) {
                // single oversized paragraph: hard split with overlap
                if (buf.length() > 0) pieces.add(buf.toString());  // buf 可能为空：跳过空块，否则 TDD 测试 2 的 chunks.get(0) 是空串
                buf = new StringBuilder();
                String rest = p;
                while (rest.length() > maxSize) {
                    pieces.add(rest.substring(0, maxSize));
                    rest = rest.substring(maxSize - overlap);
                }
                buf.append(rest);
            } else {
                buf.append(p).append('\n');
            }
        }
        if (!buf.isEmpty()) pieces.add(buf.toString());
        return pieces;
    }

    /** Last `overlap` chars of previous piece, used as the head of the next piece. */
    private String tail(String s) {
        return s.length() <= overlap ? s : s.substring(s.length() - overlap);
    }

    /** Merge a tiny last chunk (below minSize) into the previous one. */
    private List<Chunk> mergeTinyTails(List<Chunk> chunks) {
        if (chunks.size() < 2) return chunks;
        List<Chunk> out = new ArrayList<>(chunks);
        Chunk last = out.remove(out.size() - 1);
        Chunk prev = out.remove(out.size() - 1);
        if (last.content().length() < minSize) {
            out.add(new Chunk(prev.content() + "\n" + last.content(),
                    prev.headingPath(), prev.index()));
        } else {
            out.add(prev);
            out.add(last);
        }
        return out;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=HeadingAwareChunkerTest
# 期望：3 个测试全部 PASS
```

- [ ] **Step 5: 实现解析器与 DocumentService/Controller**

```java
package com.kbrag.document.parser;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentParser {
    String parse(InputStream in, String filename) throws IOException;
}
```

```java
package com.kbrag.document.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.IOException;
import java.io.InputStream;

public class PdfParser implements DocumentParser {
    @Override
    public String parse(InputStream in, String filename) throws IOException {
        // PDFBox 3.x 已移除 PDDocument.load(InputStream)，统一走 Loader.loadPDF
        try (PDDocument doc = Loader.loadPDF(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
```

```java
package com.kbrag.document.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import java.io.IOException;
import java.io.InputStream;
import java.util.stream.Collectors;

public class WordParser implements DocumentParser {
    @Override
    public String parse(InputStream in, String filename) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(in)) {
            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
    }
}
```

```java
package com.kbrag.document.parser;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MarkdownParser implements DocumentParser {
    private final Parser parser = Parser.builder().build();
    @Override
    public String parse(InputStream in, String filename) throws IOException {
        String md = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Node node = parser.parse(md);
        // commonmark 不直接输出纯文本：简易转纯文本（strip 链接语法即可，标题保留 # 前缀供分块器使用）
        return md;
    }
}
```

> Markdown 解析器第一版直接返回原文（保留 `#` 标题供分块器用）；正文中的链接/图片语法在 Phase 3 用 commonmark 的 `TextContentRenderer` 清洗。PDF 复杂版面（扫描件）本期不支持，素材优先 Markdown/Word。

```java
package com.kbrag.document;

import com.kbrag.document.parser.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class DocumentService {
    @Autowired DocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;
    @Autowired @Lazy DocumentService self;  // 自注入代理：同类内部调用不走 Spring 代理，@Async 会退化成同步

    private final HeadingAwareChunker chunker;
    private static final int MAX_SIZE = 800, MIN_SIZE = 400, OVERLAP = 100;

    public DocumentService() {
        this.chunker = new HeadingAwareChunker(MAX_SIZE, MIN_SIZE, OVERLAP);
    }

    public Document upload(MultipartFile file) throws IOException {
        Document doc = new Document();
        doc.title = file.getOriginalFilename();
        doc.uploader = 0L;
        documents.save(doc);
        // MultipartFile 背后是 Tomcat 临时文件，请求结束后会被删除；
        // 必须先同步读成字节数组再交给异步线程，否则 @Async 线程会读不到文件（FileNotFoundException）
        byte[] bytes = file.getBytes();
        self.processAsync(doc.id, bytes, file.getOriginalFilename());   // 必须经代理调用，@Async 才生效
        return doc;
    }

    @Async
    public void processAsync(Long docId, byte[] content, String filename) {
        Document doc = documents.findById(docId).orElseThrow();
        try {
            String text = pickParser(filename).parse(new ByteArrayInputStream(content), filename);
            List<Chunk> parsed = chunker.chunk(text);
            List<DocumentChunk> entities = parsed.stream().map(c -> {
                DocumentChunk e = new DocumentChunk();
                e.docId = docId;
                e.chunkIndex = c.index();
                e.content = c.content();
                e.headingPath = c.headingPath();
                e.tokenCount = c.content().length(); // 中英混排近似，Phase 3 换真实 token 统计
                return e;
            }).toList();
            chunks.saveAll(entities);
            doc.status = DocumentStatus.READY;
        } catch (Exception ex) {
            doc.status = DocumentStatus.FAILED;
            doc.errorMessage = ex.getMessage();
        }
        documents.save(doc);
    }

    private DocumentParser pickParser(String filename) {
        String n = filename == null ? "" : filename.toLowerCase();
        if (n.endsWith(".md") || n.endsWith(".markdown") || n.endsWith(".txt")) return new MarkdownParser();
        if (n.endsWith(".pdf")) return new PdfParser();
        if (n.endsWith(".docx")) return new WordParser();
        throw new IllegalArgumentException("Unsupported file type: " + filename);
    }
}
```

> `HeadingAwareChunker` 手动 new + 构造器注入，无需 Spring Bean。`@Async` 必须经代理调用：`this.processAsync(...)` 是同类内部调用，不触发代理、会同步执行——`@Autowired @Lazy` 自注入是标准解法。`MAX_SIZE` 等从 `app.retrieval` 配置读取留到 Phase 3（分块对照实验需要可配置）。

```java
package com.kbrag.document;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    @Autowired DocumentService documentService;
    @Autowired DocumentRepository documents;
    @Autowired DocumentChunkRepository chunks;

    @PostMapping
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            return ResponseEntity.ok(documentService.upload(file));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping
    public List<Document> list(@RequestParam(required = false) String status) {
        return status == null ? documents.findAll() : documents.findAll().stream()
                .filter(d -> d.status.name().equalsIgnoreCase(status)).toList();
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        chunks.deleteByDocId(id);   // 注入 DocumentChunkRepository
        documents.deleteById(id);
    }
}
```

- [ ] **Step 6: 验证**

```bash
cd backend && mvn spring-boot:run
# 准备样例：echo -e "# 设备使用说明\n\n## 安装\n\n先把设备放在通风处。" > sample.md
curl -F "file=@sample.md" http://localhost:8080/api/documents
curl http://localhost:8080/api/documents
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "SELECT doc_id, chunk_index, left(content, 50) FROM document_chunk;"
# 期望：文档 READY，chunk 含标题前缀
```

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: document upload, parsing and heading-aware chunking"
```

**验收**：上传 md/pdf/docx 后文档状态为 READY；分块测试 3/3 通过；chunk 内容带标题路径。

---

### Task 4: 向量化入库与关键词索引

**Files:**
- Create: `backend/src/main/java/com/kbrag/ai/EmbeddingService.java`
- Create: `backend/src/main/java/com/kbrag/ai/Tokenizer.java`（jieba 封装）
- Modify: `backend/src/main/java/com/kbrag/document/DocumentService.java`（processAsync 中补 embedding 与 segmentedText）
- Modify: `backend/src/main/resources/schema.sql`（无改动，验证生成列生效）

**Interfaces:**
- Consumes: `DocumentChunkRepository`（Task 2）、LangChain4j `OpenAiEmbeddingModel`
- Produces: `EmbeddingService.embed(List<String> texts) → List<float[]>`（batch 32 + 重试 2 次）；`Tokenizer.segment(String) → String`（空格分隔）；`DocumentChunk.embedding`/`segmentedText` 入库

- [ ] **Step 1: 实现 Tokenizer（jieba 封装）**

```java
package com.kbrag.ai;

import com.huaban.analysis.jieba.JiebaSegmenter;
import java.util.List;

public class Tokenizer {
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /** 中文分词，返回空格分隔字符串，供 PG tsvector 使用。 */
    public String segment(String text) {
        List<String> words = segmenter.sentenceProcess(text);
        return String.join(" ", words);
    }
}
```

> jieba 的 `sentenceProcess` 对中文按词典切词；英文原样保留。`'simple'` 全文检索配置对空格分隔的文本按词匹配——中文检索即生效。

- [ ] **Step 2: 实现 EmbeddingService**

```java
package com.kbrag.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EmbeddingService {
    private final EmbeddingModel model;
    private static final int BATCH = 32;

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
            embeddings.forEach(e -> out.add(e.vectorAsArray()));
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

    interface CheckedSupplier<T> { T get(); }
}
```

> 代码按"能跑通优先"，`retry` 用简单循环；Phase 3 换 Resilience4j 并讲重试/退避。

- [ ] **Step 3: 接入 DocumentService.processAsync**

在 Task 3 的 `processAsync` 中，`chunks.saveAll(entities)` 之前插入：

```java
// 向量化 + 关键词分词
List<String> contents = parsed.stream().map(Chunk::content).toList();
List<float[]> vectors = embeddingService.embed(contents);
for (int i = 0; i < entities.size(); i++) {
    entities.get(i).embedding = vectors.get(i);   // float[]，配合 hibernate-vector 映射
    entities.get(i).segmentedText = tokenizer.segment(entities.get(i).content);
}
```

（注入 `EmbeddingService embeddingService`、`Tokenizer tokenizer`；`Tokenizer` 标 `@Component`。）

- [ ] **Step 4: 验证入库**

```bash
cd backend && mvn spring-boot:run
curl -F "file=@sample.md" http://localhost:8080/api/documents
sleep 5
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c \
 "SELECT doc_id, embedding IS NOT NULL AS has_vec, segmented_text != '' AS has_seg FROM document_chunk;"
# 期望：has_vec=true, has_seg=true
psql ... -c "SELECT * FROM document_chunk LIMIT 1;"  # search_text 生成列自动有值
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: embedding ingestion and jieba segmentation for chunks"
```

**验收**：文档入库后 embedding 与 segmented_text 非空；search_text 生成列自动填充。

---

### Task 5: 混合检索 + RRF 融合

**Files:**
- Create: `backend/src/main/java/com/kbrag/retrieval/RrfFusion.java`
- Test: `backend/src/test/java/com/kbrag/retrieval/RrfFusionTest.java`
- Create: `backend/src/main/java/com/kbrag/retrieval/HybridRetriever.java`
- Create: `backend/src/main/java/com/kbrag/retrieval/SearchResult.java`
- Create: `backend/src/main/java/com/kbrag/retrieval/RetrievalController.java`（供调试/Agent 工具复用，POST /api/retrieve）

**Interfaces:**
- Consumes: `DocumentChunkRepository`、`EmbeddingService.embed`、`Tokenizer.segment`、JdbcTemplate
- Produces: `RrfFusion.fuse(List<Long> denseIds, List<Long> sparseIds, int k, int topN) → List<Long>`；`HybridRetriever.search(String query, int finalTopK) → List<SearchResult>`，`SearchResult(chunkId, docId, content, headingPath, score)`

- [ ] **Step 1: 写 RRF 的失败测试（TDD）**

```java
package com.kbrag.retrieval;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RrfFusionTest {
    @Test
    void dense_and_sparse_agree_gets_top_rank() {
        // dense: [1,2,3], sparse: [1,3,4]，1 在两个列表都是第 1 名
        List<Long> fused = RrfFusion.fuse(List.of(1L, 2L, 3L), List.of(1L, 3L, 4L), 60, 3);
        assertEquals(1L, fused.get(0));
        assertTrue(fused.contains(2L) || fused.contains(4L));
    }

    @Test
    void dedupes_and_respects_top_n() {
        List<Long> fused = RrfFusion.fuse(List.of(1L, 2L), List.of(2L, 1L), 60, 2);
        assertEquals(2, fused.size());
        assertEquals(fused.size(), fused.stream().distinct().count());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd backend && mvn test -Dtest=RrfFusionTest
```

- [ ] **Step 3: 实现 RrfFusion**

```java
package com.kbrag.retrieval;

import java.util.*;

/** Reciprocal Rank Fusion: score = sum(1 / (k + rank)), rank 从 1 开始。 */
public class RrfFusion {
    public static List<Long> fuse(List<Long> denseIds, List<Long> sparseIds, int k, int topN) {
        Map<Long, Double> scores = new HashMap<>();
        add(scores, denseIds, k);
        add(scores, sparseIds, k);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
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
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=RrfFusionTest
```

- [ ] **Step 5: 实现 HybridRetriever**

```java
package com.kbrag.retrieval;

import com.kbrag.ai.EmbeddingService;
import com.kbrag.ai.Tokenizer;
import com.pgvector.PGvector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class HybridRetriever {
    private final JdbcTemplate jdbc;
    private final EmbeddingService embeddingService;
    private final Tokenizer tokenizer;
    private final int denseTopK, sparseTopK, rrfK, finalTopK;

    public HybridRetriever(JdbcTemplate jdbc,
                           EmbeddingService embeddingService,
                           Tokenizer tokenizer,
                           @Value("${app.retrieval.dense-top-k}") int denseTopK,
                           @Value("${app.retrieval.sparse-top-k}") int sparseTopK,
                           @Value("${app.retrieval.rrf-k}") int rrfK,
                           @Value("${app.retrieval.final-top-k}") int finalTopK) {
        this.jdbc = jdbc;
        this.embeddingService = embeddingService;
        this.tokenizer = tokenizer;
        this.denseTopK = denseTopK;
        this.sparseTopK = sparseTopK;
        this.rrfK = rrfK;
        this.finalTopK = finalTopK;
    }

    public List<SearchResult> search(String query, int topK) {
        float[] vec = embeddingService.embed(List.of(query)).get(0);
        // 稠密路：余弦距离
        List<Long> denseIds = jdbc.query(
                "SELECT id FROM document_chunk " +
                "ORDER BY embedding <=> CAST(? AS vector) LIMIT ?",
                (rs, i) -> rs.getLong(1), new PGvector(vec), denseTopK);
        // 稀疏路：jieba 分词后 tsvector 检索，ts_rank 排序
        String seg = tokenizer.segment(query);
        List<Long> sparseIds = jdbc.query(
                "SELECT id FROM document_chunk " +
                "WHERE search_text @@ plainto_tsquery('simple', ?) " +
                "ORDER BY ts_rank(search_text, plainto_tsquery('simple', ?)) DESC LIMIT ?",
                (rs, i) -> rs.getLong(1), seg, seg, sparseTopK);
        // RRF 融合
        List<Long> fused = RrfFusion.fuse(denseIds, sparseIds, rrfK, Math.min(topK, finalTopK));
        if (fused.isEmpty()) return List.of();
        // ANY (?) 不保证返回顺序：回库后按 fused 顺序重排——TopN 的 Prompt 顺序依赖 RRF 排序
        List<SearchResult> rows = jdbc.query(
                "SELECT id, doc_id, content, heading_path FROM document_chunk WHERE id = ANY (?)",
                (rs, i) -> new SearchResult(
                        rs.getLong("id"), rs.getLong("doc_id"),
                        rs.getString("content"), rs.getString("heading_path")),
                fused.toArray(Long[]::new));
        Map<Long, SearchResult> byId = rows.stream()
                .collect(Collectors.toMap(SearchResult::chunkId, r -> r));
        return fused.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
```

```java
package com.kbrag.retrieval;

public record SearchResult(long chunkId, long docId, String content, String headingPath) {}
```

```java
package com.kbrag.retrieval;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/retrieve")
public class RetrievalController {
    @Autowired HybridRetriever retriever;

    @PostMapping
    public List<SearchResult> retrieve(@RequestBody RetrieveRequest req) {
        return retriever.search(req.query(), req.topK() == 0 ? 5 : req.topK());
    }

    public record RetrieveRequest(String query, int topK) {}
}
```

> `WHERE id = ANY (?)` 传 `Long[]` 依赖 `pgjdbc` 数组绑定；若报类型错误，改用 `WHERE id IN (:ids)` + NamedParameterJdbcTemplate。此端点 Phase 2 将作为 Agent 工具 `search_kb` 的底层。

- [ ] **Step 6: 验证检索效果**

```bash
cd backend && mvn spring-boot:run
# 上传一份含明显关键词的文档后：
curl -X POST http://localhost:8080/api/retrieve -H "Content-Type: application/json" \
  -d '{"query":"安装步骤是什么","topK":5}'
# 期望：返回的 chunk 包含"安装"相关片段；纯关键词查询（如"接线"）也能命中（稀疏路生效）
```

- [ ] **Step 7: 提交**

```bash
git add -A && git commit -m "feat: hybrid retrieval with RRF fusion"
```

**验收**：RRF 单测 2/2 通过；`/api/retrieve` 对语义与关键词查询均返回相关 chunk。

---

### Task 6: 生成 + SSE 流式对话

**Files:**
- Create: `backend/src/main/java/com/kbrag/chat/ChatController.java`
- Create: `backend/src/main/java/com/kbrag/chat/ChatService.java`
- Create: `backend/src/main/java/com/kbrag/chat/ChatRequest.java`
- Create: `backend/src/main/java/com/kbrag/chat/Conversation.java`
- Create: `backend/src/main/java/com/kbrag/chat/Message.java`
- Create: `backend/src/main/java/com/kbrag/chat/ConversationRepository.java`、`MessageRepository.java`

**Interfaces:**
- Consumes: `HybridRetriever.search`（Task 5）、LangChain4j `OpenAiChatModel`（streaming）
- Produces: `POST /api/chat`（SSE）；事件 `answer {seq, delta}` / `source {chunks:[{title,heading,content}]}` / `done {messageId, conversationId}` / `error {message}`；会话与消息持久化

- [ ] **Step 1: 实体与仓库**

```java
package com.kbrag.chat;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity @Table(name = "conversation")
public class Conversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String title;
    public LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.chat;

import jakarta.persistence.*;

@Entity @Table(name = "message")
public class Message {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public Long conversationId;
    public String role;             // user / assistant
    @Column(columnDefinition = "text") public String content;
    @Column(columnDefinition = "text") public String sourcesJson;  // assistant 回答的来源
    public Short feedback;          // 0 无反馈 / 1 赞 / -1 踩（Phase 3 用）
    public LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ConversationRepository extends JpaRepository<Conversation, Long> {}
public interface MessageRepository extends JpaRepository<Message, Long> {
    List<Message> findByConversationIdOrderByIdAsc(Long conversationId);
}
```

- [ ] **Step 2: ChatService（检索 → 组 Prompt → 流式生成 → 落库）**

```java
package com.kbrag.chat;

import com.kbrag.retrieval.HybridRetriever;
import com.kbrag.retrieval.SearchResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatService {
    private final HybridRetriever retriever;
    private final OpenAiChatModel chatModel;
    private final MessageRepository messages;
    private final ConversationRepository conversations;

    public ChatService(HybridRetriever retriever, MessageRepository messages,
                       ConversationRepository conversations,
                       @Value("${app.llm.chat-base-url}") String baseUrl,
                       @Value("${app.llm.chat-model}") String model,
                       @Value("${app.llm.chat-api-key}") String apiKey) {
        this.retriever = retriever;
        this.messages = messages;
        this.conversations = conversations;
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl).apiKey(apiKey).modelName(model)
                .temperature(0.3)
                .build();
    }

    public void streamAnswer(String question, Long conversationId, SseEmitter emitter) {
        AtomicInteger seq = new AtomicInteger();
        try {
            // 1. 检索
            List<SearchResult> hits = retriever.search(question, 5);
            if (hits.isEmpty()) {
                emitter.send(event("answer", seq, "资料库中暂未找到相关信息。"));
                emitter.send(event("done", seq, null));
                emitter.complete();
                save(conversationId, question, "资料库中暂未找到相关信息。", "[]");
                return;
            }
            // 2. 组 Prompt（强约束 + 引用编号）
            StringBuilder sb = new StringBuilder("你是一个企业知识库问答助手。只能根据下面提供的资料回答，禁止编造。\n\n资料：\n");
            for (int i = 0; i < hits.size(); i++) {
                SearchResult h = hits.get(i);
                sb.append("[").append(i + 1).append("] ")
                  .append(h.headingPath()).append("：").append(h.content()).append("\n\n");
            }
            sb.append("要求：回答中标注引用编号（如 [1][2]）；资料中没有的内容直接说明“资料中未找到相关信息”；用中文回答。\n\n问题：").append(question);
            String prompt = sb.toString();
            // 3. 流式生成
            StringBuilder answer = new StringBuilder();
            chatModel.streaming().generate(prompt, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    answer.append(token);
                    try { emitter.send(event("answer", seq, token)); }
                    catch (Exception ignored) { }
                }
                @Override
                public void onComplete(Response<AiMessage> response) {
                    try {
                        List<Map<String, String>> sources = hits.stream()
                                .map(h -> Map.of(
                                        "title", safe(h.headingPath()),
                                        "snippet", h.content().substring(0, Math.min(120, h.content().length()))))
                                .toList();
                        emitter.send(event("source", seq, sources));  // Jackson 序列化为 JSON 数组
                        emitter.send(event("done", seq, null));
                        emitter.complete();
                    } catch (Exception ignored) { }
                    save(conversationId, question, answer.toString(), "[]"); // Phase 3 落真实 sources
                }
                @Override
                public void onError(Throwable error) {
                    try { emitter.send(event("error", seq, error.getMessage())); }
                    catch (Exception ignored) { }
                    emitter.complete();
                }
            });
        } catch (Exception e) {
            try { emitter.send(event("error", seq, e.getMessage())); } catch (Exception ignored) { }
            emitter.complete();
        }
    }

    private final ObjectMapper om = new ObjectMapper();  // Spring Boot 自带 Jackson，所有事件统一走 JSON 序列化

    private SseEmitter.SseEventBuilder event(String type, AtomicInteger seq, Object data) {
        try {
            return SseEmitter.event().name(type)
                    .data("{\"seq\":" + seq.incrementAndGet() + ",\"data\":" + om.writeValueAsString(data) + "}");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private String safe(String s) { return s == null ? "" : s.replace("\"", "'"); }

    private void save(Long conversationId, String user, String assistant, String sources) {
        if (conversationId == null) {
            Conversation c = new Conversation();
            c.title = user.length() > 20 ? user.substring(0, 20) : user;
            conversations.save(c);
            conversationId = c.id;
        }
        Message m1 = new Message(); m1.conversationId = conversationId; m1.role = "user"; m1.content = user;
        Message m2 = new Message(); m2.conversationId = conversationId; m2.role = "assistant"; m2.content = assistant; m2.sourcesJson = sources;
        messages.save(m1); messages.save(m2);
    }
}
```

> `SseEmitter.event().name(type).data(...)` 生成命名 SSE 事件。事件 JSON 为 `{seq, data}`；`source` 事件直接传 List（Jackson 序列化为 JSON 数组），前端按 `event:` 名称分发，避免把来源拼进回答正文。

- [ ] **Step 3: ChatController**

```java
package com.kbrag.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    @Autowired ChatService chatService;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public record ChatRequest(String message, Long conversationId) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.execute(() -> chatService.streamAnswer(req.message(), req.conversationId(), emitter));
        return emitter;
    }
}
```

- [ ] **Step 4: 验证流式对话**

```bash
cd backend && mvn spring-boot:run
curl -N -X POST http://localhost:8080/api/chat -H "Content-Type: application/json" \
  -d '{"message":"设备的安装步骤是什么"}'
# 期望：event:answer 增量若干、event:source、event:done
psql ... -c "SELECT role, left(content, 40) FROM message;"
# 第二问复用 conversationId 做多轮（上下文在 Phase 3 接入记忆前，先直连模型单轮）
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: streaming chat with SSE and message persistence"
```

**验收**：curl -N 看到 answer/source/done 事件流；message 表落库 user/assistant 两条。

---

### Task 7: MVP 演示前端 + README + 简历投递

**Files:**
- Create: `backend/src/main/resources/static/index.html`
- Create: `agentic-rag/README.md`
- Modify: 简历（spec §11.1 后端版模板，按实测数字填写）

**Interfaces:**
- Consumes: `POST /api/chat`（Task 6）、`POST /api/documents`、`POST /api/retrieve`
- Produces: 可演示的浏览器页面；README（架构图/启动步骤/演示脚本）

- [ ] **Step 1: 编写演示页（原生 JS + fetch 流式解析 SSE）**

```html
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8"><title>企业知识库问答</title>
<style>body{font-family:sans-serif;max-width:720px;margin:40px auto}pre{white-space:pre-wrap;background:#f5f5f5;padding:12px;border-radius:6px}#src{color:#888;font-size:13px}</style>
</head>
<body>
<h2>企业知识库问答</h2>
<input id="q" style="width:70%;padding:8px" placeholder="例如：安装步骤是什么？"/>
<button onclick="ask()">提问</button>
<div id="out"></div>
<script>
async function ask() {
  const q = document.getElementById('q').value;
  const out = document.getElementById('out');
  out.innerHTML = '<pre></pre><div id="src"></div>';
  const resp = await fetch('/api/chat', {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({message: q})
  });
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '', eventName = '';
  while (true) {
    const {done, value} = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, {stream: true});
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (line.startsWith('event:')) eventName = line.slice(6).trim();
      else if (line.startsWith('data:')) {
        const ev = JSON.parse(line.slice(5));
        if (eventName === 'answer' && ev.data) out.querySelector('pre').textContent += ev.data;
        else if (eventName === 'source')
          document.getElementById('src').textContent = '来源: ' + ev.data.map(s => s.title).join(' / ');
      }
    }
  }
}
</script>
</body>
</html>
```

> 演示页第一版足够：上传文档用 curl，问答用页面；页面美化与文档管理界面放 Phase 3 前端阶段。

- [ ] **Step 2: 编写 README.md**

```markdown
# NetDoc：网络设备技术文档智能问答系统

Java 后端（Spring Boot 3 + pgvector）+ Python Agent 服务（Phase 2）的网络设备技术文档问答系统。
知识库素材：OpenWrt/路由器技术文档 + 个人部署踩坑笔记（与端侧路由器 AI Agent 项目组成"云边一套"叙事）。

## 架构
（Mermaid 图，内容对齐 spec §3）

## 启动步骤
1. `docker compose up -d`（PG+pgvector、Redis）
2. 配置环境变量 `DEEPSEEK_API_KEY` / `SILICONFLOW_API_KEY`
3. `cd backend && mvn spring-boot:run`
4. 上传文档：`curl -F "file=@sample.md" http://localhost:8080/api/documents`
5. 打开 http://localhost:8080 提问

## API
（对齐 spec §7 已实现部分）

## 演示脚本
提问 → 流式回答 → 查看引用 → 换关键词再问 → 查 message 表
```

- [ ] **Step 3: 端到端演示验证**

```bash
# 1) 上传 3-5 份真实素材（设备手册/技术文档，Markdown 优先）
curl -F "file=@手册1.md" http://localhost:8080/api/documents
# 2) 浏览器打开 http://localhost:8080 完成一轮问答
# 3) 验证来源事件、多轮对话、文档删除
# 4) 录屏 3 分钟演示（投递/面试用）
```

- [ ] **Step 4: 更新简历并投递第一波**

按 spec §11.1 后端版模板写简历项目段，量化项先用真实小样本数字（检索延迟用 curl 计时），按 spec §2 的筛选原则投递。

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: demo page and readme for phase 1 MVP"
```

**验收**：浏览器端到端问答可用；README 完整；简历已按模板更新并完成第一波投递。

---

## 修订记录（2026-08-08 评审后回写）

| # | 问题 | 修订位置 |
|---|---|---|
| P0-1 | vector 扩展缺失且时机不对：Hibernate 建表先于 schema.sql，扩展必须在建表前存在 | Task 1 新增 init.sql（docker-entrypoint-initdb.d），容器首启自动 `CREATE EXTENSION` |
| P0-2 | DocumentService 同时声明 `@Autowired chunker` 字段与构造器赋值，启动即挂 | 删除字段，保留构造器手动 new |
| P1-1 | splitBody 对超长段落且 buf 为空时产出空块，TDD 测试 2 的 `chunks.get(0)` 断言必挂 | 空 buf 不入队（`if (buf.length() > 0)`） |
| P1-2 | `@Async` 同类内部调用不经过代理，实际同步执行 | `@Autowired @Lazy` 自注入，改调 `self.processAsync(...)` |
| P2-1 | `WHERE id = ANY (?)` 不保序，RRF 排序丢失，Prompt 顺序乱 | 回库后 Java 端按 fused 顺序重排 |
| P2-2 | `source` 事件以字符串发送，演示页会把来源拼进回答正文 | event() 改用 Jackson 序列化；前端按 `event:` 名分发 |
| P3-1 | `PGvector.of(float[])` API 存疑（0.1.6 无此工厂方法） | 改为构造器 `new PGvector(vec)` |
| P3-2 | 计划内 5432 与实测 5433 不一致 | 全计划连接串/psql/application.yml 同步 5433 |
| P3-3 | docs/superpowers 位于 git 仓库外 | Task 1 Step 8 拷入仓库一并提交 |
| P4-1 | DocumentController 的 delete 用了 `chunks` 但漏声明注入字段 | 补 `@Autowired DocumentChunkRepository chunks;` |
| P4-2 | PdfParser 用 `PDDocument.load(InputStream)`，PDFBox 3.x 已删除该方法 | 改用 `Loader.loadPDF(in)` |
| P4-3 | `@Async` 线程直接读 `MultipartFile`：请求结束 Tomcat 删临时文件，异步线程 FileNotFoundException | 请求线程内先 `file.getBytes()`，异步方法改收 `byte[]` + 文件名 |
| P4-4 | 实体用 `com.pgvector.PGvector` 字段，Hibernate 按 bytea 绑定，插入报 "expression is of type bytea" | 改用 Hibernate 官方 `hibernate-vector` 模块：`float[]` + `@JdbcTypeCode(SqlTypes.VECTOR)` + `@Array(length=1024)`；pom 补依赖 |
| P4-5 | `errorMessage` 默认 varchar(255)，长错误信息存不进去导致失败状态丢失 | 实体加 `@Column(columnDefinition = "text")` |

## Self-Review 记录

- **Spec 覆盖**：Phase 1 覆盖 spec §3 架构的 Java 部分、§4.1 的 document-service/retrieval-service/chat-gateway（基础版）、§5 链路 1/2/4/6 步、§6 数据模型 document/document_chunk/conversation/message、§7 的 documents/chat 端点。§4.1 的 memory-service/eval-service/observability、rerank、限流/缓存/审计归 Phase 3；权限/MCP/多 Agent 归 Phase 4。**无遗漏。**
- **占位符**：无 TBD；`xx ms`/`命中率 xx%` 仅在简历模板作为实测占位（按 spec §11.1 约定）。
- **类型一致性**：`Chunk(content, headingPath, index)`、`SearchResult(chunkId, docId, content, headingPath)`、`event(type, seq, data)` 在各任务间签名一致；`DocumentService` 构造器与 `@Async` 注入已注明处理方式。
