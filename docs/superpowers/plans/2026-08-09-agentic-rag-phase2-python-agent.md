# Agentic RAG Phase 2:Python Agent 基础功能 + Java 工程化同步实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本项目按 HANDOFF 协作协议执行:**用户自己动手写代码**,助手负责任务拆解、验收、报错拆解、机械性修复。任务推进用 executing-plans 逐任务验收。
> 双线并行约定(2026-08-09):**Python 侧基础功能优先**(新功能先跑通),**Java 侧工程化同步推进**(Java 基础功能 Phase 1 已完成,限流/语义缓存/可观测即时插入);Task 5/8/9 为 Java 工程化,与 Python 任务(Task 1-4/6/7)可并行开发。
>
> **对齐说明(2026-08-10)**:本计划对应新 spec《2026-08-10-agentic-rag-enterprise-design.md》第 3 周里程碑(Agent 基础 + 工具端点 + 限流)。与本计划相关的 spec 变更:① 工具调用幂等键(本计划 Task 2 已含,spec §9 双层防线);② 认证/多知识库/KG/语义缓存一致性 → Plan B(2026-08-10-plan-b,第 4 周);③ 评测/知识包/部署 → Plan C(第 5-6 周)。Task 8 语义缓存的一致性增强(kb 命名空间/版本戳)由 Plan B 改造。

**Goal:** 交付 Python Agent 服务(FastAPI + LangGraph 五节点 + 全链路 SSE 透传)的同时,Java 侧工程优化同步推进(用户级令牌桶限流、语义缓存、每轮 span 可观测)——"Python 管思考、Java 管执行"的完整 Agent 故事,工程化指标可量化、可面试讲。

**Architecture:** Python 独立服务(`python/` 目录,FastAPI,端口 **9100**)承载 LangGraph 有状态图;图节点通过 HTTP 反向调用 Java 的工具端点(search_kb/get_doc_detail/get_stats)完成检索与溯源;Java 的 `/api/chat` 网关用 WebClient 把 SSE 事件原样透传给浏览器;Java 负责会话落库与历史回放,Python 负责 Agent 编排与 LLM 调用。Java 工程化在对话网关同步落地:Redis 用户级令牌桶限流(IP 粒度)、语义缓存(embedding 相似度 >0.95 命中)、每轮 span 落库(各阶段耗时/缓存命中率)。

**Tech Stack:** Python 3.10+、FastAPI、uvicorn、sse-starlette、LangGraph(0.2+)、langchain-openai(OpenAI 兼容协议)、httpx、python-dotenv、pytest、pytest-asyncio;Java 侧新增 spring-boot-starter-webflux(仅用 WebClient)。

## Global Constraints

- LLM:DeepSeek,base_url `https://api.deepseek.com/v1`,model `deepseek-chat`;Embedding:硅基流动 BGE-M3,base_url `https://api.siliconflow.cn/v1`,model `BAAI/bge-m3`,维度 1024(与 Phase 1 一致)
- API Key 只从环境变量读取:复用 `backend/.env`(DEEPSEEK_API_KEY / SILICONFLOW_API_KEY),禁止写进代码/git
- Python 依赖用 uv 管理(pyproject.toml + uv.lock):`uv sync` 安装、`uv run pytest/uvicorn/python` 执行——不用 pip/venv 手动激活;本计划内所有 Python 命令均带 `uv run` 前缀
- DDL 托管(2026-08-10 用户决策):Hibernate `ddl-auto=none`,所有表结构由 `backend/src/main/resources/schema.sql` 全量维护(生成列与 update 迁移冲突的解法);**新增表/列必须同步写进 schema.sql**(兼容已有库用 `ADD COLUMN IF NOT EXISTS` + `CREATE INDEX IF NOT EXISTS` 幂等写法)
- 端口:Java 9000、Python **9100**、PG 5433、Redis 6379;**8080 被 rpki-system 占用,永远别用**;Python 与 Java 同属 9 系列(隔开 100),完全避开 80 系列防混淆(与 Java 选 9000 同理)
- SSE 事件名统一:`answer`(delta)/`source`/`done`/`error` + Agent 过程事件 `rewrite`/`router`/`tool`(可选,前端忽略未知事件),事件体带递增 `seq`
- 防护(与 spec §8 一致):最大步数 8(recursion_limit)、工具超时 10s(httpx timeout)、重复工具调用检测、错误信息自然语言化回喂 LLM
- 检索无结果:rerank 阈值判定 Phase 3 做;Phase 2 沿用"检索为空 → 明确拒答/反问",不硬答
- 降级预案(spec §3.1):Python 服务不可用时,Java 侧发 `error` 事件告知"Agent 服务暂不可用",不静默失败
- 后端代码与注释用英文,README/文档用中文(与 Phase 1 实际一致:保持各文件现有注释语言)
- 图节点:rewrite/router/verify 用 `chat.ainvoke`(非流式),**只有 generate 用 `chat.astream`**——astream_events 只对流式调用产生 `on_chat_model_stream` 事件,天然过滤掉其他节点的 LLM 调用
- LangGraph 版本适配:计划代码按 `langgraph>=0.2` 的 StateGraph + `astream_events(version="v2")` 编写(事件里用 `metadata.langgraph_node` 识别节点);若安装到 1.x 遇 API 报错,按官方迁移指南调整,优先固定 0.2/0.3 系列以与本计划代码保持一致
- 记忆:Phase 2 仅滑动窗口(Java 查最近 N 轮历史传给 Python);micro_compact/snip_compact 双压缩策略归 Phase 3

## 与 spec 的偏差说明

- 记忆双压缩策略(micro_compact/snip_compact)从 spec §4.2 移入 Phase 3(与 Phase 1 plan 的 Phase 总览一致,Phase 2 只做滑动窗口)
- web_search 在线搜索工具归 Phase 4(spec §13 弹性范围)
- rerank(bge-reranker)归 Phase 3 A/B 实验(Phase 1 plan 已定)

---

## Phase 总览

| 任务 | 内容 | 验收 |
|---|---|---|
| Task 1 | Python 服务骨架(FastAPI + 配置 + /health) | `curl :9100/health` 返回 UP |
| Task 2 | Java Agent 工具端点(search/get-doc-detail/get-stats + tool_call_log + /api/agent/health) | curl 三个工具端点可用,tool_call_log 落库 |
| Task 3 | Python LLM/Embedding 客户端封装 | pytest mock 通过 + 手动真调通 chat/embedding |
| Task 4 | Python 工具层(JavaApiClient + 工具 schema + 超时/重复检测) | Python 能真调通 Java 工具端点 |
| Task 5 | **Java 工程化#1:用户级令牌桶限流(Redis Lua)** | 超限 429,令牌补充后恢复;TokenBucket 单测 2/2 |
| Task 6 | LangGraph 五节点图(TDD:Router/重复检测/自检重试) | pytest 通过,图单测全绿 |
| Task 7 | 全链路 SSE(Java WebClient 透传 + Python stream_events + phase 事件带耗时) | 浏览器提问流式回答,message 落库 |
| Task 8 | **Java 工程化#2:语义缓存(embedding 相似度 >0.95)** | 同问题二次命中,命中率可查 |
| Task 9 | **Java 工程化#3:可观测(每轮 span 落库)+ /api/stats** | 每轮 span 落库,/api/stats 出指标 |

---

### Task 1: Python 服务骨架

**Files:**
- Create: `python/pyproject.toml`(uv 依赖管理:声明依赖,uv.lock 锁版本可复现构建)
- Create: `python/app/__init__.py`
- Create: `python/app/config.py`
- Create: `python/app/main.py`
- Create: `python/tests/__init__.py`
- Create: `python/tests/test_health.py`
- Create: `python/.env.example`(仅占位说明,真实密钥在 backend/.env)
- Modify: `agentic-rag/.gitignore`(忽略 python/.venv 与 Python 缓存,防止误提交)

**Interfaces:**
- Consumes: 无(独立新服务)
- Produces: `GET /health → {"status": "UP"}`;`app.config` 导出全部环境配置常量(DEEPSEEK_BASE_URL/DEEPSEEK_API_KEY/DEEPSEEK_MODEL/SILICONFLOW_BASE_URL/SILICONFLOW_API_KEY/EMBEDDING_MODEL/JAVA_BASE_URL/TOOL_TIMEOUT_SECONDS/MAX_STEPS)——Task 3/4/5 从 `app.config` 导入

- [ ] **Step 1: 创建 pyproject.toml(uv 依赖管理)**

```toml
[project]
name = "netdoc-agent"
version = "0.2.0"
description = "NetDoc enterprise RAG platform - Python agent service"
requires-python = ">=3.10"
dependencies = [
    "fastapi>=0.115",
    "uvicorn[standard]>=0.32",
    "sse-starlette>=2.1",
    "langgraph>=0.2",
    "langchain-openai>=0.2",
    "httpx>=0.27",
    "python-dotenv>=1.0",
]

[dependency-groups]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.24",
]
```

安装:`cd python && uv sync`(自动建 .venv + 装依赖 + 生成 uv.lock)
日常:`uv add <pkg>` 增依赖、`uv add --dev <pkg>` 增 dev 依赖、`uv run <cmd>` 在环境内执行
面试点:uv = 极速包管理 + lock 文件可复现构建(pip 无锁文件,poetry 慢),现代 Python 工程链

- [ ] **Step 2: 创建 config.py(读取 backend/.env)**

```python
import os
from pathlib import Path

from dotenv import load_dotenv

# python/app/config.py → parents[2] = agentic-rag/,密钥文件与 Java 共用 backend/.env
ENV_FILE = Path(__file__).resolve().parents[2] / "backend" / ".env"
load_dotenv(ENV_FILE)

DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
SILICONFLOW_BASE_URL = os.getenv("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")
SILICONFLOW_API_KEY = os.getenv("SILICONFLOW_API_KEY", "")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:9000")
TOOL_TIMEOUT_SECONDS = float(os.getenv("TOOL_TIMEOUT_SECONDS", "10"))
MAX_STEPS = int(os.getenv("MAX_STEPS", "8"))
```

> `python/.env.example`:文档占位,列出可覆盖的环境变量(`JAVA_BASE_URL`/`TOOL_TIMEOUT_SECONDS`/`MAX_STEPS`);默认值已内联在 config.py,且从 backend/.env 读取真实密钥,一般无需创建 python/.env。

- [ ] **Step 3: 创建 main.py(最小 FastAPI 应用)**

```python
from fastapi import FastAPI

app = FastAPI(title="NetDoc Agent Service", version="0.2.0")


@app.get("/health")
def health():
    return {"status": "UP"}
```

- [ ] **Step 4: 创建健康检查测试**

```python
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "UP"}
```

- [ ] **Step 5: 运行测试与启动验证**

```bash
# .gitignore 补 Python 忽略项(防止误提交 .venv/缓存)
cat >> ../.gitignore <<'EOF'

# Python
python/.venv/
__pycache__/
*.pyc
.pytest_cache/
EOF

cd python && uv run pytest -q
# 期望:1 passed
uv run uvicorn app.main:app --port 9100
curl http://localhost:9100/health   # {"status":"UP"}
```

- [ ] **Step 6: 提交**

```bash
cd ../agentic-rag && git add python/ && git commit -m "feat: python agent service skeleton with health check"
```

**验收**:`pytest` 全绿;`curl :9100/health` 返回 `{"status":"UP"}`。

---

### Task 2: Java Agent 工具端点

**Files:**
- Modify: `backend/pom.xml`(加 webflux,仅用 WebClient)
- Modify: `backend/src/main/resources/application.yml`(app.agent.base-url,统一 Python 服务地址)
- Create: `backend/src/main/java/com/kbrag/tool/ToolCallLog.java`
- Create: `backend/src/main/java/com/kbrag/tool/ToolCallLogRepository.java`
- Create: `backend/src/main/java/com/kbrag/tool/ToolController.java`
- Create: `backend/src/main/java/com/kbrag/agent/AgentHealthController.java`

**Interfaces:**
- Consumes: `HybridRetriever.search(String query, int topK) → List<SearchResult>`(Task 5 Phase 1)、`DocumentRepository`/`DocumentChunkRepository`(Phase 1)、`ToolCallLogRepository`(本任务)
- Produces: `POST /api/agent/tools/search {query, topK} → List<SearchResult>`;`POST /api/agent/tools/get-doc-detail {docId} → {id, title, status, chunks:[{id, content, headingPath}]}`;`POST /api/agent/tools/get-stats {} → {docCount, chunkCount}`;`GET /api/agent/health → {java:"UP", agent:"UP"|"DOWN"}`——Task 4 的 Python JavaApiClient 按此契约调用

- [ ] **Step 1: pom.xml 加 webflux(仅用 WebClient 调 Python)**

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

> Spring Boot 3 中 spring-webmvc 与 spring-webflux 同时在 classpath 时 MVC 服务器优先,WebClient 单独使用不受影响。

- [ ] **Step 2: 创建 ToolCallLog 实体与仓库**

```java
package com.kbrag.tool;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Agent 工具调用审计日志(spec §6 tool_call_log)。
 * idempotentKey = conversationId + ":" + agentStepId:幂等键,唯一索引防重复执行
 * (spec §9 双层防线:Python 内存层 + Java DB 层)。
 */
@Data
@Entity
@Table(name = "tool_call_log",
       uniqueConstraints = @UniqueConstraint(name = "uk_tool_call_idem", columnNames = "idempotent_key"))
public class ToolCallLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    private String toolName;
    private String idempotentKey;   // conversationId:agentStepId;网络重试/Agent 重复调用时幂等返回
    @Column(columnDefinition = "text")
    private String inputJson;
    @Column(columnDefinition = "text")
    private String outputSummary;
    private Integer latencyMs;
    private Boolean ok;
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.tool;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {
    Optional<ToolCallLog> findFirstByIdempotentKey(String idempotentKey);   // 幂等校验
}
```

- [ ] **Step 3: 创建 ToolController(三个工具端点 + 全量审计)**

```java
package com.kbrag.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.document.Document;
import com.kbrag.document.DocumentChunk;
import com.kbrag.document.DocumentChunkRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.retrieval.HybridRetriever;
import com.kbrag.retrieval.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 工具端点(spec §4.1 tool-service):供 Python Agent 服务反向调用。
 * 每次调用全量落库 tool_call_log(安全审计)+ 幂等键防重复执行(spec §9)。
 */
@RestController
@RequestMapping("/api/agent/tools")
public class ToolController {
    @Autowired private HybridRetriever retriever;
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private ToolCallLogRepository logs;
    private final ObjectMapper om = new ObjectMapper();

    @PostMapping("/search")
    public Object search(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        try {
            List<SearchResult> hits = retriever.search(req.query(), req.topK() == 0 ? 5 : req.topK());
            log("search_kb", req, hits.size() + " hits", t0, true, req);
            return hits;
        } catch (Exception e) {
            log("search_kb", req, e.getMessage(), t0, false, req);
            throw e;
        }
    }

    @PostMapping("/get-doc-detail")
    public Object getDocDetail(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        Long docId = req.docId();
        try {
            Document doc = documents.findById(docId).orElseThrow();
            List<DocumentChunk> chunkList = chunks.findByDocId(docId);
            Map<String, Object> result = Map.of(
                    "id", doc.getId(), "title", doc.getTitle(), "status", doc.getStatus(),
                    "chunks", chunkList.stream().map(c -> Map.of(
                            "id", c.getId(), "content", c.getContent(), "headingPath", c.getHeadingPath())).toList());
            log("get_doc_detail", Map.of("docId", docId), chunkList.size() + " chunks", t0, true, req);
            return result;
        } catch (Exception e) {
            log("get_doc_detail", Map.of("docId", docId), e.getMessage(), t0, false, req);
            throw e;
        }
    }

    @PostMapping("/get-stats")
    public Object getStats(@RequestBody ToolRequest req) {
        Optional<Object> dup = idempotent(req);
        if (dup.isPresent()) return dup.get();
        long t0 = System.currentTimeMillis();
        try {
            long docCount = documents.count();
            long chunkCount = chunks.count();
            log("get_stats", Map.of(), docCount + " docs / " + chunkCount + " chunks", t0, true, req);
            return Map.of("docCount", docCount, "chunkCount", chunkCount);
        } catch (Exception e) {
            log("get_stats", Map.of(), e.getMessage(), t0, false, req);
            throw e;
        }
    }

    /** 幂等校验:conversationId+agentStepId 已执行过 → 直接返回上次结果,不重复执行。 */
    private Optional<Object> idempotent(ToolRequest req) {
        if (req.conversationId() == null || req.agentStepId() == null) return Optional.empty();
        String key = req.conversationId() + ":" + req.agentStepId();
        Optional<ToolCallLog> prev = logs.findFirstByIdempotentKey(key);
        if (prev.isPresent()) {
            return Optional.of(Map.of("idempotent", true, "output", prev.get().getOutputSummary()));
        }
        return Optional.empty();
    }

    private void log(String tool, Object input, String output, long t0, boolean ok, ToolRequest req) {
        ToolCallLog l = new ToolCallLog();
        l.setToolName(tool);
        if (req.conversationId() != null) l.setConversationId(req.conversationId());
        if (req.conversationId() != null && req.agentStepId() != null) {
            l.setIdempotentKey(req.conversationId() + ":" + req.agentStepId());
        }
        l.setInputJson(write(input));
        l.setOutputSummary(output);
        l.setLatencyMs((int) (System.currentTimeMillis() - t0));
        l.setOk(ok);
        logs.save(l);
    }

    private String write(Object o) {
        try { return om.writeValueAsString(o); }
        catch (Exception e) { return "{}"; }
    }

    public record ToolRequest(String query, Integer topK, Long docId, Long conversationId, Integer agentStepId) {}
}
```

> Python 侧 JavaClient 每次工具调用带上 `conversation_id` 与递增的 `agent_step_id`(单轮图执行内计数);Java 幂等命中时返回 `{"idempotent":true,"output":"上次结果摘要"}`——网络重试/Agent 重复调用不再重复执行。

- [ ] **Step 4: 创建 AgentHealthController(Java 侧健康端点,顺带检查 Python)**

```java
package com.kbrag.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * /api/agent/health:检查 Python Agent 服务连通性(供运维/前端探测)。
 */
@RestController
public class AgentHealthController {
    private final WebClient webClient;

    public AgentHealthController(WebClient.Builder builder,
                                 @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
    }

    @GetMapping("/api/agent/health")
    public Map<String, String> health() {
        String agent = "DOWN";
        try {
            Map<?, ?> body = webClient.get().uri("/health")
                    .retrieve().bodyToMono(Map.class).block(Duration.ofSeconds(2));
            if (body != null && "UP".equals(body.get("status"))) agent = "UP";
        } catch (Exception ignored) { }
        return Map.of("java", "UP", "agent", agent);
    }
}
```

> application.yml 的 `app:` 下新增(Java 侧唯一配置点,Task 7 的 AgentChatService 复用,勿再硬编码):

```yaml
  agent:
    base-url: http://localhost:9100   # Python Agent 服务地址
```

- [ ] **Step 5: 验证**

```bash
cd backend && mvn spring-boot:run
# 先上传一份文档(Phase 1 已验),然后:
curl -X POST http://localhost:9000/api/agent/tools/search -H "Content-Type: application/json" -d '{"query":"安装步骤","topK":3}'
curl -X POST http://localhost:9000/api/agent/tools/get-stats -H "Content-Type: application/json" -d '{}'
curl -X POST http://localhost:9000/api/agent/tools/get-doc-detail -H "Content-Type: application/json" -d '{"docId":1}'
curl http://localhost:9000/api/agent/health
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "SELECT tool_name, ok, latency_ms FROM tool_call_log ORDER BY id DESC LIMIT 5;"
# 期望:三个工具返回数据;health 中 agent 为 DOWN(Task 1 未启动 Python 时正常);tool_call_log 有记录
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: agent tool endpoints with tool call audit log"
```

**验收**:三个工具端点 curl 可用;tool_call_log 落库(ok/latency 正确);`/api/agent/health` 返回 java UP。

---

### Task 3: Python LLM/Embedding 客户端封装

**Files:**
- Create: `python/app/llm.py`
- Create: `python/tests/test_llm.py`

**Interfaces:**
- Consumes: `app.config` 的 DEEPSEEK_*/SILICONFLOW_*/EMBEDDING_MODEL(Task 1)
- Produces: `chat_model`(langchain_openai ChatOpenAI,streaming=True);`embeddings_model`(OpenAIEmbeddings);`async def embed(texts: list[str]) -> list[list[float]]`(batch 32,重试 2 次)——Task 4/5 使用

- [ ] **Step 1: 创建 llm.py**

```python
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from app.config import (
    DEEPSEEK_API_KEY,
    DEEPSEEK_BASE_URL,
    DEEPSEEK_MODEL,
    EMBEDDING_MODEL,
    SILICONFLOW_API_KEY,
    SILICONFLOW_BASE_URL,
)

# 聊天模型:DeepSeek,OpenAI 兼容协议;temperature 0.3 与 Java 侧 Phase 1 一致
chat_model = ChatOpenAI(
    base_url=DEEPSEEK_BASE_URL,
    api_key=DEEPSEEK_API_KEY,
    model=DEEPSEEK_MODEL,
    temperature=0.3,
    streaming=True,
)

embeddings_model = OpenAIEmbeddings(
    base_url=SILICONFLOW_BASE_URL,
    api_key=SILICONFLOW_API_KEY,
    model=EMBEDDING_MODEL,
)


async def embed(texts: list[str]) -> list[list[float]]:
    """批量向量化(batch 32,失败重试 2 次),供语义检索/语义缓存(Phase 3)使用。"""
    result: list[list[float]] = []
    for i in range(0, len(texts), 32):
        batch = texts[i : i + 32]
        vectors = None
        for attempt in range(3):  # 重试 2 次
            try:
                vectors = await embeddings_model.aembed_documents(batch)
                break
            except Exception:
                if attempt == 2:
                    raise
        result.extend(vectors)
    return result
```

- [ ] **Step 2: 创建测试(mock,不真调 API)**

```python
from unittest.mock import AsyncMock, patch

import pytest

from app.llm import embed


@pytest.mark.asyncio
async def test_embed_batches_and_retries():
    with patch("app.llm.embeddings_model.aembed_documents", new_callable=AsyncMock) as m:
        m.side_effect = [Exception("boom"), [[0.1, 0.2]]]
        result = await embed(["hello"])
        assert len(result) == 1
        assert m.await_count == 2  # 第一次失败,重试成功


@pytest.mark.asyncio
async def test_embed_raises_after_three_failures():
    with patch("app.llm.embeddings_model.aembed_documents", new_callable=AsyncMock) as m:
        m.side_effect = Exception("boom")
        with pytest.raises(Exception):
            await embed(["hello"])
        assert m.await_count == 3
```

- [ ] **Step 3: 运行测试**

```bash
cd python && uv run pytest -q
# 期望:test_health + 2 个 embed 测试全 PASS
```

- [ ] **Step 4: 手动真调通(可选但推荐,验证 .env 读取)**

```bash
cd python && python -c "
import asyncio
from app.llm import embed
async def main():
    v = await embed(['测试'])
    print('embed dim:', len(v[0]))
asyncio.run(main())
"
# 期望:embed dim: 1024(BGE-M3)
```

- [ ] **Step 5: 提交**

```bash
git add -A && git commit -m "feat: python llm and embedding client wrappers"
```

**验收**:pytest 3 个用例全绿;手动调用 BGE-M3 返回 1024 维向量。

---

### Task 4: Python 工具层(JavaApiClient + 工具 schema)

**Files:**
- Create: `python/app/java_client.py`
- Create: `python/app/tools.py`
- Create: `python/tests/test_java_client.py`
- Create: `python/tests/test_tools.py`

**Interfaces:**
- Consumes: `app.config` 的 JAVA_BASE_URL/TOOL_TIMEOUT_SECONDS(Task 1);Java 工具端点契约(Task 2)
- Produces: `JavaClient.search_kb(query, top_k=5) → list`、`JavaClient.get_doc_detail(doc_id) → dict`、`JavaClient.get_stats() → dict`;`TOOL_SCHEMAS`(OpenAI function calling 格式);`execute_tool(name, args) → str`(返回自然语言结果文本);`JavaClient.is_duplicate(name, args) -> bool`(重复调用检测)——Task 5 图节点使用

- [ ] **Step 1: 创建 java_client.py(带超时 + 重复调用检测)**

```python
import json

import httpx

from app.config import JAVA_BASE_URL, TOOL_TIMEOUT_SECONDS


class JavaClient:
    """反向调用 Java 工具端点;记录已执行调用,重复调用检测。
    conversation_id 非空时,每次调用携带递增 agentStepId → Java 侧幂等键(spec §9 双层防线)。
    约束:一个实例只服务一个会话(单线程图执行),_seen/_step 是会话级状态;
    重试轮次间必须复用同一实例,否则 agentStepId 重置会命中 Java 侧幂等缓存、返回降级摘要。"""

    def __init__(self, base_url: str = JAVA_BASE_URL, timeout: float = TOOL_TIMEOUT_SECONDS,
                 conversation_id: int | None = None):
        self.base_url = base_url
        self.timeout = timeout
        self.conversation_id = conversation_id
        self._step = 0
        self._seen: set[tuple[str, str]] = set()

    def _call(self, tool: str, payload: dict) -> dict:
        key = (tool, json.dumps(payload, sort_keys=True, ensure_ascii=False))
        if key in self._seen:
            raise RuntimeError(f"工具 {tool} 已用相同参数调用过,已跳过重复调用")
        if self.conversation_id is not None:
            self._step += 1
            payload = {**payload, "conversationId": self.conversation_id, "agentStepId": self._step}
        with httpx.Client(timeout=self.timeout) as client:
            r = client.post(f"{self.base_url}/api/agent/tools/{tool}", json=payload)
            r.raise_for_status()
        self._seen.add(key)
        return r.json()

    def search_kb(self, query: str, top_k: int = 5) -> list:
        return self._call("search", {"query": query, "topK": top_k})

    def get_doc_detail(self, doc_id: int) -> dict:
        return self._call("get-doc-detail", {"docId": doc_id})

    def get_stats(self) -> dict:
        return self._call("get-stats", {})
```

- [ ] **Step 2: 创建 tools.py(工具 schema + 执行入口)**

```python
from app.java_client import JavaClient

# OpenAI function calling 格式工具定义(DeepSeek 兼容)
TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "search_kb",
            "description": "在网络设备技术文档知识库中检索与问题最相关的文档片段(混合检索:向量 + 关键词)。回答技术问题时必须先调用它。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "检索关键词或完整问题"},
                    "top_k": {"type": "integer", "description": "返回片段数,默认 5"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_doc_detail",
            "description": "获取某篇文档的完整详情(标题、状态、全部分块内容),用于溯源与深挖细节。",
            "parameters": {
                "type": "object",
                "properties": {"doc_id": {"type": "integer"}},
                "required": ["doc_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_stats",
            "description": "获取知识库统计信息(文档数、分块数)。",
            "parameters": {"type": "object", "properties": {}},
        },
    },
]

_FUNC_MAP = {
    "search_kb": lambda c, args: c.search_kb(args["query"], args.get("top_k", 5)),
    "get_doc_detail": lambda c, args: c.get_doc_detail(args["doc_id"]),
    "get_stats": lambda c, args: c.get_stats(),
}


def execute_tool(name: str, args: dict, client: JavaClient | None = None) -> str:
    """执行工具并返回自然语言化结果文本(LLM 可读);错误也自然语言化回喂。"""
    client = client or JavaClient()
    try:
        raw = _FUNC_MAP[name](client, args)
    except Exception as e:
        return f"[工具 {name} 执行失败] {e}"
    if isinstance(raw, list):
        if not raw:
            return "知识库中未检索到相关内容。"
        lines = []
        for i, hit in enumerate(raw, 1):
            lines.append(
                f"[{i}] 片段ID={hit.get('chunkId')} 文档ID={hit.get('docId')} 标题={hit.get('headingPath') or ''}\n{(hit.get('content') or '')[:500]}"
            )
        return "\n\n".join(lines)
    return str(raw)
```

- [ ] **Step 3: 创建测试(重复检测 + 错误自然语言化,用 mock 不发真实请求)**

```python
import pytest

from app.java_client import JavaClient
from app.tools import execute_tool


class FakeClient:
    """模拟 Java 工具响应。"""

    def __init__(self, hits=None):
        self.hits = hits or []
        self.calls = []

    def search_kb(self, query, top_k=5):
        self.calls.append(("search_kb", query, top_k))
        return self.hits

    def get_stats(self):
        self.calls.append(("get_stats",))
        return {"docCount": 2, "chunkCount": 10}


def test_duplicate_call_detected():
    c = JavaClient(base_url="http://fake:1")
    c._seen.add(("search_kb", '{"query": "安装", "topK": 5}'))
    with pytest.raises(RuntimeError, match="重复调用"):
        c.search_kb("安装", 5)


def test_execute_tool_empty_result_message():
    c = FakeClient(hits=[])
    text = execute_tool("search_kb", {"query": "不存在的词"}, c)
    assert "未检索到" in text


def test_execute_tool_formats_hits():
    c = FakeClient(hits=[{"chunkId": 1, "docId": 2, "headingPath": "第一章", "content": "内容"}])
    text = execute_tool("search_kb", {"query": "安装"}, c)
    assert "片段ID=1" in text and "第一章" in text


def test_execute_tool_error_naturalized():
    class Boom:
        def search_kb(self, *a, **k):
            raise RuntimeError("connection refused")

    text = execute_tool("search_kb", {"query": "x"}, Boom())
    assert "执行失败" in text and "connection refused" in text
```

- [ ] **Step 4: 运行测试**

```bash
cd python && uv run pytest -q
# 期望:全部 PASS
```

- [ ] **Step 5: 真调通验证(Java 后端 + 文档已在库)**

```bash
# 终端 A:cd backend && mvn spring-boot:run(Java 9000)
# 终端 B:
cd python && python -c "
from app.tools import execute_tool
print(execute_tool('get_stats', {}))
print(execute_tool('search_kb', {'query': '安装', 'top_k': 2})[:300])
"
# 期望:stats 返回文档/分块数;search 返回片段列表;重复调用会提示已跳过
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: python tool layer with java client and duplicate-call guard"
```

**验收**:pytest 4 个用例全绿;Python 真调通 Java 三个工具端点。

---

### Task 5: Java 工程化#1:用户级令牌桶限流(Redis Lua)

> 工程优化与 Python 基础功能同步推进:本任务不依赖 Python 侧,可随时并行开发。

**Files:**
- Create: `backend/src/main/java/com/kbrag/ratelimit/TokenBucket.java`(令牌桶纯算法,可单测)
- Test: `backend/src/test/java/com/kbrag/ratelimit/TokenBucketTest.java`
- Create: `backend/src/main/java/com/kbrag/ratelimit/RateLimiter.java`(Redis 存储层,Lua 原子)
- Modify: `backend/src/main/resources/application.yml`(app.rate-limit 配置)
- Modify: `backend/src/main/java/com/kbrag/chat/ChatController.java`(入口限流,超限 429)

**Interfaces:**
- Consumes: `StringRedisTemplate`(Phase 1 已引入 spring-boot-starter-data-redis)
- Produces: `TokenBucket.tryAcquire() -> boolean`;`RateLimiter.tryAcquire(String userId) -> boolean`(Lua 原子:读令牌→按时间补充→消耗);ChatController 在 SSE 入口限流(超限 HTTP 429)

- [ ] **Step 1: 写失败测试(TDD)**

```java
package com.kbrag.ratelimit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenBucketTest {
    @Test
    void burst_allowed_up_to_capacity() {
        TokenBucket b = new TokenBucket(3, 1);   // 容量 3,每秒补 1
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());              // 第 4 个被拒
    }

    @Test
    void refills_over_time() throws InterruptedException {
        TokenBucket b = new TokenBucket(1, 10);   // 容量 1,每秒补 10
        assertTrue(b.tryAcquire());
        assertFalse(b.tryAcquire());
        Thread.sleep(200);                        // 200ms 补充 2 个(容量封顶 1)
        assertTrue(b.tryAcquire());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd backend && mvn test -Dtest=TokenBucketTest
# 期望:编译失败(TokenBucket 不存在)
```

- [ ] **Step 3: 实现 TokenBucket(算法与存储分离,单测不依赖 Redis——面试点)**

```java
package com.kbrag.ratelimit;

/**
 * 令牌桶纯算法:容量 capacity,每秒补充 refillPerSecond 个令牌。
 * 与存储解耦,单测可跑;Redis 层只做状态持久化与原子性。
 */
public class TokenBucket {
    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /** 尝试消耗 1 个令牌;不足返回 false。synchronized 保证并发安全。 */
    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillNanos) / 1_000_000_000.0;
        tokens = Math.min(capacity, tokens + elapsed * refillPerSecond);
        lastRefillNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=TokenBucketTest
# 期望:2 个测试 PASS
```

- [ ] **Step 5: 实现 RateLimiter(Redis Lua 原子操作)**

```java
package com.kbrag.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 令牌桶:key = rate:{userId} 存当前令牌数,rate:{userId}:ts 存上次补充时间戳。
 * Lua 脚本单次原子执行(Redis 单线程保证)——面试点:为什么不用 get+set(竞态:并发请求同时读到旧令牌)。
 */
@Service
public class RateLimiter {
    private final StringRedisTemplate redis;
    private final double capacity;
    private final double refillPerSecond;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${app.rate-limit.capacity:10}") double capacity,
                       @Value("${app.rate-limit.refill-per-second:1}") double refillPerSecond) {
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local now = tonumber(ARGV[1])
            local cap = tonumber(ARGV[2])
            local refill = tonumber(ARGV[3])
            local tokens = tonumber(redis.call('GET', key) or cap)
            local last = tonumber(redis.call('GET', key .. ':ts') or now)
            tokens = math.min(cap, tokens + (now - last) / 1000.0 * refill)
            if tokens < 1 then
                redis.call('SET', key .. ':ts', now)
                return 0
            end
            redis.call('SET', key, tokens - 1)
            redis.call('SET', key .. ':ts', now)
            return 1
            """, Long.class);

    /** userId 粒度限流;false = 超限。 */
    public boolean tryAcquire(String userId) {
        Long r = redis.execute(SCRIPT,
                List.of("rate:" + userId),
                // 可变参数逐个展开,不能包成 List(否则整个 List 被当成 args[0],序列化抛 ClassCastException)
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(capacity), String.valueOf(refillPerSecond));
        return r != null && r == 1L;
    }
}
```

- [ ] **Step 6: application.yml 配置 + ChatController 接入**

```yaml
  rate-limit:
    capacity: 10        # 桶容量:允许突发 10 次
    refill-per-second: 2  # 每秒补充 2 个令牌
```

> ⚠️ 前置依赖:本步 ChatController 引用了 `AgentChatService`(Task 7 Step 5 创建)。**Java 侧 Task 5 与 Task 7 必须同批完成**——先建 AgentChatService 再改 ChatController,否则编译不过。完整 import 如下,照抄即可:

```java
package com.kbrag.chat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.kbrag.ratelimit.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/chat")
public class ChatController {
    @Autowired private AgentChatService agentChatService;
    @Autowired private RateLimiter rateLimiter;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public record ChatRequest(String message, Long conversationId) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        String userId = clientIp(http);   // 无登录态,用 IP 作为用户标识(登录后换 userId,维度不变)
        if (!rateLimiter.tryAcquire(userId)) {
            return ResponseEntity.status(429).body("请求过于频繁,请稍后再试");
        }
        SseEmitter emitter = new SseEmitter(180_000L);
        executor.execute(() -> agentChatService.stream(req.message(), req.conversationId(), emitter));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        return fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    }
}
```

> 演示页 index.html 顺手加 429 提示(机械性小改):`if (!resp.ok) { out.textContent = await resp.text(); return; }`。

- [ ] **Step 7: 验证**

```bash
cd backend && mvn spring-boot:run
# 临时调低配置便于压测:capacity=3, refill-per-second=1
for i in $(seq 1 6); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:9000/api/chat \
    -H "Content-Type: application/json" -d '{"message":"测试限流"}')
  echo "第 $i 次: $code"
done
# 期望:前 3 次 200,后 3 次 429(令牌耗尽);等待 1 秒后再请求恢复 200
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: user-level token bucket rate limiting with redis lua"
```

**验收**:TokenBucket 单测 2/2 PASS;连续请求超限返回 429,令牌补充后恢复;限流粒度按 IP(用户级,预留 userId 维度)。

---

### Task 6: LangGraph 五节点图(TDD)

**Files:**
- Create: `python/app/state.py`
- Create: `python/app/nodes/__init__.py`
- Create: `python/app/nodes/rewrite.py`
- Create: `python/app/nodes/router.py`
- Create: `python/app/nodes/tools_node.py`
- Create: `python/app/nodes/generate.py`
- Create: `python/app/nodes/verify.py`
- Create: `python/app/graph.py`
- Create: `python/tests/test_graph.py`

**Interfaces:**
- Consumes: `app.llm.chat_model`(Task 3)、`app.tools.execute_tool/TOOL_SCHEMAS/JavaClient`(Task 4)
- Produces: `AgentState`(TypedDict);`build_graph() -> CompiledStateGraph`;`run_agent(input: dict) -> AsyncIterator[AgentEvent]`(流式事件:rewrite/router/tool/answer/source/done/error)——Task 6 的 SSE 端点使用

**图结构(spec §4.2):**

```
START → rewrite → router ──retrieve──→ tools → generate → verify ──pass──→ END
                       │                 │                      │
                       └──direct(闲聊)───┘                      └─retry(≤1次,FAIL理由回喂rewrite)→ rewrite
                                                                 └─give_up──→ END

tools 节点内部两阶段:① LLM function calling 决定调用哪些工具(TOOL_SCHEMAS)
                    ② 循环执行 tool_calls(重复检测/10s 超时/错误自然语言化)
```

- [ ] **Step 1: 创建 state.py**

```python
from typing import Optional, TypedDict


class AgentState(TypedDict, total=False):
    question: str              # 原始问题
    history: list              # 滑动窗口历史 [{role, content}]
    conversation_id: Optional[int]  # 会话 ID(Java 透传,工具幂等键用)
    rewritten: Optional[str]   # 改写后问题
    needs_retrieval: bool      # Router 决策
    contexts: list             # 检索到的片段 [{chunkId, docId, headingPath, content}]
    answer: str                # 最终回答
    sources: list              # 引用来源 [{title, snippet}]
    attempts: int              # 自检重试计数(最多 1 次)
    verified: bool             # 忠实度自检是否通过
    error: Optional[str]       # 错误信息(自然语言化)
    tool_calls: list           # 本次会话已执行调用记录(重复检测依据)
```

- [ ] **Step 2: 写失败测试(TDD 先红)**

```python
import pytest
from types import SimpleNamespace

from app.state import AgentState


class FakeChat:
    """mock LLM:按调用次数返回预置响应,record 调用参数。
    返回 SimpleNamespace(content, tool_calls) 模拟 LangChain AIMessage 形态。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def ainvoke(self, messages, **kwargs):
        self.calls.append((messages, kwargs))
        r = self.responses.pop(0)
        if isinstance(r, str):
            return SimpleNamespace(content=r, tool_calls=[])
        return r


@pytest.fixture
def base_state():
    return AgentState(question="OpenWrt 如何设置无线?", history=[], needs_retrieval=True,
                      contexts=[], answer="", sources=[], attempts=0, verified=False,
                      error=None, tool_calls=[])


# ---- Router ----

async def test_router_direct_chat_skips_retrieval(base_state):
    from app.nodes.router import router_node
    fake = FakeChat(['{"needs_retrieval": false}'])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is False


async def test_router_invalid_json_defaults_to_retrieve(base_state):
    from app.nodes.router import router_node
    fake = FakeChat(["不是 JSON"])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is True  # 解析失败降级走检索


# ---- Tools(两阶段:LLM function calling 决定 → 执行)----

async def test_tools_node_decides_and_executes(base_state):
    from app.nodes.tools_node import tools_node
    call = {"name": "search_kb", "args": {"query": "安装", "top_k": 5}}
    fake_chat = FakeChat([SimpleNamespace(content="", tool_calls=[call])])
    fake_client = FakeJavaClient()
    state = dict(base_state)
    out = await tools_node(state, fake_chat, fake_client)
    assert len(out["contexts"]) == 1  # LLM 决定调用 search_kb,结果解析进 contexts
    assert out["tool_calls"] == [call]


async def test_tools_node_duplicate_call_deduped(base_state):
    from app.nodes.tools_node import tools_node
    call = {"name": "search_kb", "args": {"query": "安装", "top_k": 5}}
    fake_client = FakeJavaClient()
    state = dict(base_state)
    out1 = await tools_node(state, FakeChat([SimpleNamespace(content="", tool_calls=[call])]), fake_client)
    # 第二次 LLM 又请求相同调用:JavaClient._seen 拦截,contexts 不再增加
    out2 = await tools_node(out1, FakeChat([SimpleNamespace(content="", tool_calls=[call])]), fake_client)
    assert len(out1["contexts"]) == 1
    assert len(out2["contexts"]) == 1


# ---- Verify(attempts 递增 + PASS/FAIL 判定 + FAIL 理由回喂)----

async def test_verify_fail_marks_retry(base_state):
    from app.nodes.verify import verify_node
    fake = FakeChat(["FAIL 回答中包含了资料没有的信息"])
    state = await verify_node(dict(base_state), fake)
    assert state["verified"] is False
    assert state["attempts"] == 1
    assert "未通过" in state["error"]  # FAIL 理由写入 error,回喂 rewrite


async def test_verify_pass(base_state):
    from app.nodes.verify import verify_node
    fake = FakeChat(["PASS 回答忠实于资料"])
    state = await verify_node(dict(base_state), fake)
    assert state["verified"] is True
    assert state["attempts"] == 1


def test_verify_decision_give_up_after_two_failures():
    from app.nodes.verify import verify_decision
    assert verify_decision({"verified": False, "attempts": 1}) == "retry"
    assert verify_decision({"verified": False, "attempts": 2}) == "give_up"
    assert verify_decision({"verified": True, "attempts": 2}) == "pass"


# ---- Generate(检索无果拒答 / 闲聊直答)----

class FakeStreamChat:
    """模拟流式 LLM:astream 逐 token 产出。"""

    def __init__(self, tokens):
        self.tokens = tokens

    async def astream(self, messages):
        for t in self.tokens:
            yield SimpleNamespace(content=t)


async def test_generate_rejects_when_no_contexts_and_retrieval_needed(base_state):
    from app.nodes.generate import generate_node
    state = dict(base_state)  # needs_retrieval=True
    out = await generate_node(state, FakeStreamChat(["资料"]))
    assert "未找到" in out["answer"]
    assert out["sources"] == []


async def test_generate_answers_direct_chat_without_contexts(base_state):
    from app.nodes.generate import generate_node
    state = dict(base_state)
    state["needs_retrieval"] = False  # Router 判定闲聊,直接自然回答
    out = await generate_node(state, FakeStreamChat(["你好!"]))
    assert out["answer"] == "你好!"


# ---- 图结构 ----

def test_graph_has_five_nodes():
    from app.graph import build_graph
    graph = build_graph()
    node_names = {n for n, _ in graph.get_graph().nodes.items()}
    assert {"rewrite", "router", "tools", "generate", "verify"} <= node_names


class FakeJavaClient:
    """模拟 JavaClient:含重复调用检测,命中返回固定片段。"""

    def __init__(self):
        self.hits = [{"chunkId": 1, "docId": 1, "headingPath": "第一章", "content": "内容"}]
        self.seen = set()

    def search_kb(self, query, top_k=5):
        key = ("search_kb", query, top_k)
        if key in self.seen:
            raise RuntimeError("工具 search_kb 已用相同参数调用过,已跳过重复调用")
        self.seen.add(key)
        return self.hits
```

- [ ] **Step 3: 运行确认失败**

```bash
cd python && pytest tests/test_graph.py -q
# 期望:ModuleNotFoundError(节点模块不存在)
```

- [ ] **Step 4: 实现 rewrite 节点**

```python
from app.state import AgentState

SYSTEM = (
    "你是网络设备技术文档问答助手的查询改写器。把用户问题改写成适合知识库检索的形式:"
    "消解指代(它/这个/上面 → 具体对象)、口语转检索词、保持技术术语。"
    "只输出改写后的问题本身,不要解释。若问题已清晰,原样返回。"
)


async def rewrite_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    history = state.get("history") or []
    history_text = "\n".join(f"{h['role']}: {h['content']}" for h in history[-6:])
    # 忠实度自检失败重试时,把 FAIL 理由回喂,指导改写检索词(spec §4.2 "改写重检索一次")
    retry_hint = ""
    if state.get("error"):
        retry_hint = f"\n注意:上次回答因忠实度审查未通过,理由:{state['error']}。请调整检索词以获取更充分的资料。"
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"对话历史:\n{history_text or '(无)'}\n\n当前问题:{state['question']}{retry_hint}"},
    ]
    resp = await chat.ainvoke(messages)
    state["rewritten"] = (getattr(resp, "content", "") or "").strip() or state["question"]
    return state
```

- [ ] **Step 5: 实现 router 节点(JSON 约束,解析失败降级检索)**

```python
import json

from app.state import AgentState

SYSTEM = (
    "你是检索决策器。判断用户问题是否需要检索知识库:"
    "涉及设备配置/参数/操作步骤/故障排查等技术内容 → needs_retrieval=true;"
    "纯寒暄(你好/谢谢)或与设备技术无关 → needs_retrieval=false 直接回答。"
    '只输出 JSON:{"needs_retrieval": true|false, "reason": "一句话理由"}'
)


async def router_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    resp = await chat.ainvoke([
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"问题:{state['rewritten']}"},
    ])
    text = getattr(resp, "content", "") or ""
    try:
        decision = json.loads(text.strip().strip("`"))
        state["needs_retrieval"] = bool(decision.get("needs_retrieval", True))
    except Exception:
        state["needs_retrieval"] = True  # 解析失败降级:宁可多检索,不可漏检索
    return state


def route_decision(state: AgentState) -> str:
    return "retrieve" if state["needs_retrieval"] else "direct"
```

- [ ] **Step 6: 实现 tools 节点(两阶段:LLM function calling 决定 → 执行,带重复检测与错误自然语言化)**

```python
from app.state import AgentState

TOOLS_SYSTEM = (
    "你是网络设备技术文档问答助手。回答技术问题前必须调用 search_kb 检索知识库;"
    "需要溯源时调用 get_doc_detail;用户问知识库规模时调用 get_stats。"
)

_clients: dict = {}   # conversation_id → JavaClient(进程内会话级缓存;单进程 uvicorn 适用)


async def tools_node(state: AgentState, chat=None, client=None) -> AgentState:
    """阶段1:LLM function calling 决定工具调用;阶段2:循环执行(去重/超时/错误自然语言化)。"""
    from app.java_client import JavaClient
    from app.llm import chat_model
    from app.tools import TOOL_SCHEMAS, execute_tool

    chat = chat or chat_model
    # 会话级 client 缓存:verify→rewrite→tools 重试轮次间必须复用同一实例,_seen/_step 才能延续;
    # 否则 _step 重置,Java 侧幂等缓存命中旧 step,返回降级摘要(无 content,检索丢失)
    conversation_id = state.get("conversation_id")
    if client is None:
        client = _clients.get(conversation_id) or JavaClient(conversation_id=conversation_id)
        _clients[conversation_id] = client
    contexts = list(state.get("contexts") or [])

    # 阶段 1:LLM 携带工具 schema 决定调用哪些工具(DeepSeek Function Calling)
    resp = await chat.ainvoke(
        [
            {"role": "system", "content": TOOLS_SYSTEM},
            {"role": "user", "content": f"问题:{state.get('rewritten') or state['question']}"},
        ],
        tools=TOOL_SCHEMAS,
    )
    raw_calls = getattr(resp, "tool_calls", None) or []
    requested = [{"name": c.get("name"), "args": c.get("args") or {}} for c in raw_calls]

    # 阶段 2:执行;重复调用由 JavaClient._seen 拦截,错误自然语言化写入执行结果
    for call in requested:
        name, args = call["name"], call["args"]
        text = execute_tool(name, args, client)
        if name == "search_kb" and not text.startswith("[工具"):
            contexts.extend(_parse_hits(text))  # 把检索结果文本解析回结构化

    state["contexts"] = contexts
    state["tool_calls"] = list(state.get("tool_calls") or []) + requested  # 累积记录(供审计/去重)
    return state


def _parse_hits(text: str) -> list:
    """从 execute_tool 的输出解析片段:逐行扫描、锚定 meta 行。
    content 内若含 "[N] " 交叉引用不会被误切分(round-trip 安全)。"""
    import re

    meta_re = re.compile(r"^\[\d+\] 片段ID=(\d+) 文档ID=(\d+) 标题=(.*)$")
    hits, current = [], None
    for line in text.splitlines():
        m = meta_re.match(line)
        if m:
            if current is not None:
                hits.append(current)
            current = {"chunkId": int(m.group(1)), "docId": int(m.group(2)),
                       "headingPath": m.group(3), "content": ""}
        elif current is not None:
            current["content"] += line + "\n"
    if current is not None:
        hits.append(current)
    for h in hits:
        h["content"] = h["content"].rstrip("\n")
    return hits
```

> 格式契约(execute_tool ↔ _parse_hits,改格式必须同步改解析):每个片段 = 一行 meta(`[N] 片段ID=.. 文档ID=.. 标题=..`)+ 后续若干行 content;解析器逐行扫描锚定 meta 行,content 内的 `[N]` 交叉引用不误切。
> DeepSeek 的 tool_calls 结构与 OpenAI 一致:`[{"name": ..., "args": {...}}]`(langchain 解析后)。

- [ ] **Step 7: 实现 verify 节点(PASS/FAIL 判断 + attempts 递增 + FAIL 理由回喂)**

```python
import re

from app.state import AgentState

SYSTEM = (
    "你是忠实度审查员。判断回答是否严格忠于给定的资料片段:"
    "回答中的事实必须在资料中有依据,禁止编造。"
    '先输出 PASS 或 FAIL,再输出一句理由。'
)


async def verify_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    context_text = "\n".join(
        f"[{i + 1}] {c.get('headingPath', '')}: {c.get('content', '')[:800]}"
        for i, c in enumerate(state.get("contexts") or [])
    )
    resp = await chat.ainvoke([
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"资料片段:\n{context_text or '(无)'}\n\n回答:{state.get('answer', '')}"},
    ])
    text = getattr(resp, "content", "") or ""
    state["attempts"] = (state.get("attempts") or 0) + 1  # 每次审查 +1,保证 retry 最多一次
    if re.search(r"\bPASS\b", text.upper()):  # 容忍 "审查结果:PASS" 等前缀
        state["verified"] = True
    else:
        state["verified"] = False
        state["error"] = f"忠实度审查未通过:{text[:200]}"  # FAIL 理由写入 error,回喂 rewrite
    return state


def verify_decision(state: AgentState) -> str:
    if state["verified"]:
        return "pass"
    return "retry" if (state.get("attempts") or 0) < 2 else "give_up"
```

- [ ] **Step 8: 实现 generate 节点(带引用流式;按 needs_retrieval 分支:检索无果拒答 / 闲聊直答)**

```python
from app.state import AgentState

SYSTEM = (
    "你是网络设备技术文档智能问答助手。只能根据提供的资料片段回答,禁止编造。"
    "回答中用 [1][2] 标注引用编号;资料中没有的内容直接说'资料中未找到相关信息'。"
    "用中文回答。"
)


async def generate_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    contexts = state.get("contexts") or []
    # 检索分支但没拿到资料 → 明确拒答/反问,不硬答(spec §8);闲聊分支无 contexts 直接自然回答
    if not contexts and state.get("needs_retrieval", True):
        state["answer"] = "资料库中暂未找到相关信息,请换一种问法或补充文档。"
        state["sources"] = []
        return state

    prompt_parts = []
    if contexts:
        prompt_parts.append("资料:\n")
        for i, c in enumerate(contexts):
            prompt_parts.append(f"[{i + 1}] {c.get('headingPath', '')}: {c.get('content', '')}")
    if state.get("needs_retrieval", True):
        prompt_parts.append("\n要求:回答中标注引用编号;资料中没有的内容直接说明;用中文回答。")
    else:
        prompt_parts.append("\n要求:这是闲聊,无需引用资料,自然回答;用中文。")
    prompt_parts.append(f"\n问题:{state.get('rewritten') or state['question']}")

    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": "\n\n".join(prompt_parts)},
    ]

    answer_parts = []
    async for chunk in chat.astream(messages):
        answer_parts.append(chunk.content or "")
    state["answer"] = "".join(answer_parts)
    state["sources"] = [
        {"title": c.get("headingPath", ""), "snippet": (c.get("content") or "")[:120]}
        for c in contexts
    ]
    return state
```

- [ ] **Step 9: 组装图 graph.py(含 run_agent 流式事件采集)**

```python
import time

from langgraph.graph import END, START, StateGraph

from app.nodes.generate import generate_node
from app.nodes.router import route_decision, router_node
from app.nodes.rewrite import rewrite_node
from app.nodes.tools_node import tools_node
from app.nodes.verify import verify_decision, verify_node
from app.state import AgentState

NODE_NAMES = ("rewrite", "router", "tools", "generate", "verify")


def build_graph():
    g = StateGraph(AgentState)
    g.add_node("rewrite", rewrite_node)
    g.add_node("router", router_node)
    g.add_node("tools", tools_node)
    g.add_node("generate", generate_node)
    g.add_node("verify", verify_node)
    g.add_edge(START, "rewrite")
    g.add_edge("rewrite", "router")
    g.add_conditional_edges("router", route_decision, {"retrieve": "tools", "direct": "generate"})
    g.add_edge("tools", "generate")
    g.add_edge("generate", "verify")
    g.add_conditional_edges("verify", verify_decision, {"pass": END, "retry": "rewrite", "give_up": END})
    return g.compile()


async def run_agent(input_state: dict):
    """流式执行 Agent;单次 astream_events 同时采集 token 与最终状态(勿二次 ainvoke,会重复执行图)。
    recursion_limit=MAX_STEPS 防死循环。产出 (kind, payload):answer/phase/sources/done。"""
    from app.config import MAX_STEPS

    graph = build_graph()
    final_answer, sources, verified, error = "", [], False, None
    start_ns: dict[str, int] = {}
    async for event in graph.astream_events(
        input_state,
        version="v2",
        config={"recursion_limit": MAX_STEPS},
    ):
        kind = event.get("event")
        node = event.get("metadata", {}).get("langgraph_node", "")  # v2 事件标准字段,比 name 更可靠
        if kind == "on_chat_model_stream":
            chunk = event["data"].get("chunk")
            token = chunk.content if chunk else ""
            if token:
                yield ("answer", token)
        elif kind == "on_chain_start" and node in NODE_NAMES:
            start_ns[node] = time.monotonic_ns()
        elif kind == "on_chain_end" and node in NODE_NAMES:
            output = event["data"].get("output") or {}
            if node == "generate":
                final_answer = output.get("answer", "")
                sources = output.get("sources") or []
            elif node == "verify":
                verified = bool(output.get("verified"))
                error = output.get("error")
            # 阶段耗时事件(Java 可观测 Task 9 汇总用;前端忽略未知事件)
            yield ("phase", {"node": node, "elapsedMs": (time.monotonic_ns() - start_ns.get(node, 0)) // 1_000_000})
    yield ("sources", sources)
    yield ("done", {"answer": final_answer, "verified": verified, "error": error})
```

- [ ] **Step 10: 运行测试(Step 2 的用例 + 补 tools 解析单测)**

```bash
cd python && uv run pytest -q
# 期望:test_graph.py 全部 PASS(router/verify 用 FakeChat,不真调 LLM)
```

> 若 FakeChat 与节点签名不匹配(节点内 `from app.llm import chat_model` 而非参数注入),调整单测为 monkeypatch `app.nodes.router.chat_model` 等模块级引用。

- [ ] **Step 11: 提交**

```bash
git add -A && git commit -m "feat: langgraph five-node agent graph with guards"
```

**验收**:pytest 全绿(Router 决策/JSON 降级/重复检测/verify 重试分支);`build_graph()` 可编译,图结构含 5 节点 + 3 条件边。

---

### Task 7: 全链路 SSE(Java WebClient 透传 + Python stream_events + phase 事件带耗时)

**Files:**
- Create: `python/app/sse.py`(AgentChatRequest + SSE 事件生成器)
- Modify: `python/app/main.py`(挂 /agent/chat SSE 端点)
- Create: `python/tests/test_sse.py`
- Modify: `backend/src/main/java/com/kbrag/chat/ChatController.java`(WebClient 转发)
- Create: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`
- Modify: `backend/src/main/java/com/kbrag/chat/ChatService.java`(保留,降级用)

**Interfaces:**
- Consumes: `run_agent`(Task 5)、`AgentState`、Phase 1 的 `MessageRepository`/`ConversationRepository`
- Produces: `POST http://localhost:9100/agent/chat {message, conversation_id, history} → SSE(answer/sources/done/error)`;Java `POST /api/chat` 行为变为:透传 Python SSE 事件,`done` 后落库 message(行为与 Phase 1 前端兼容)

- [ ] **Step 1: 创建 sse.py**

```python
import json
from typing import AsyncIterator

from pydantic import BaseModel

from app.graph import run_agent


class AgentChatRequest(BaseModel):
    message: str
    conversation_id: int | None = None
    history: list[dict] = []


def build_input(req: AgentChatRequest) -> dict:
    return {
        "question": req.message,
        "history": req.history[-10:],
        "conversation_id": req.conversation_id,   # 工具幂等键上下文
        "needs_retrieval": True,
        "contexts": [],
        "answer": "",
        "sources": [],
        "attempts": 0,
        "verified": False,
        "error": None,
        "tool_calls": [],
    }


async def event_stream(req: AgentChatRequest) -> AsyncIterator[dict]:
    """LangGraph 事件 → SSE 事件流:{event, seq, data}。"""
    seq = 0

    def emit(event: str, data):
        nonlocal seq
        seq += 1
        return {"event": event, "data": json.dumps({"seq": seq, "data": data}, ensure_ascii=False)}

    try:
        async for kind, payload in run_agent(build_input(req)):
            if kind == "answer":
                yield emit("answer", payload)
            elif kind == "phase":
                yield emit("phase", payload)   # 阶段耗时透传(Java 可观测用;前端忽略)
            elif kind == "sources":
                yield emit("source", payload)
            elif kind == "done":
                yield emit("done", {"verified": payload["verified"], "error": payload["error"]})
    except Exception as e:
        yield emit("error", f"Agent 服务异常:{e}")
    finally:
        yield emit("done", None)
```

- [ ] **Step 2: main.py 挂 SSE 端点**

```python
from fastapi import FastAPI
from sse_starlette.sse import EventSourceResponse

from app.sse import AgentChatRequest, event_stream

app = FastAPI(title="NetDoc Agent Service", version="0.2.0")


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/agent/chat")
async def agent_chat(req: AgentChatRequest):
    return EventSourceResponse(event_stream(req), headers={"Cache-Control": "no-cache"})
```

- [ ] **Step 3: 创建 test_sse.py(用 mock run_agent 验证事件序列)**

```python
import json

import pytest

from app.sse import AgentChatRequest, event_stream


@pytest.mark.asyncio
async def test_event_stream_sequence():
    async def fake_run_agent(state):
        yield ("phase", "rewrite")
        yield ("answer", "你好")
        yield ("sources", [{"title": "第一章", "snippet": "..."}])
        yield ("done", {"verified": True, "error": None})

    import app.sse
    app.sse.run_agent = fake_run_agent  # monkeypatch

    events = [e async for e in event_stream(AgentChatRequest(message="你好"))]
    names = [e["event"] for e in events]
    assert names == ["answer", "source", "done"]
    # seq 递增
    seqs = [json.loads(e["data"])["seq"] for e in events]
    assert seqs == [1, 2, 3]
```

- [ ] **Step 4: 运行 Python 测试**

```bash
cd python && uv run pytest -q
# 期望:全绿
```

- [ ] **Step 5: 创建 Java AgentChatService(WebClient 透传 + 落库)**

```java
package com.kbrag.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全链路 SSE 透传:前端 ← Java ← Python Agent。
 * 透传 answer/source/done/error;done 后把完整回答落库 message(行为与 Phase 1 兼容)。
 */
@Service
public class AgentChatService {
    private final WebClient webClient;
    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final ObjectMapper om = new ObjectMapper();

    public AgentChatService(WebClient.Builder builder,
                            MessageRepository messages,
                            ConversationRepository conversations,
                            @Value("${app.agent.base-url:http://localhost:9100}") String agentBaseUrl) {
        this.webClient = builder.baseUrl(agentBaseUrl).build();
        this.messages = messages;
        this.conversations = conversations;
    }

    public void stream(String question, Long conversationId, SseEmitter emitter) {
        List<Map<String, String>> history = conversationId == null ? List.of()
                : messages.findByConversationIdOrderByIdAsc(conversationId).stream()
                        .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                        .toList();
        // 滑动窗口:只取最近 10 条(升序列表取尾部)
        history = history.size() > 10 ? history.subList(history.size() - 10, history.size()) : history;
        Map<String, Object> body = Map.of(
                "message", question,
                "conversation_id", conversationId,
                "history", history);

        Flux<ServerSentEvent<String>> stream = webClient.post()
                .uri("/agent/chat")
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .timeout(Duration.ofSeconds(180));

        StringBuilder answer = new StringBuilder();
        Map<String, Integer> phaseMs = new HashMap<>();   // 各阶段耗时(供 Task 9 可观测落库)
        stream.subscribe(
                sse -> {
                    try {
                        String event = sse.event();
                        String data = sse.data();
                        if ("answer".equals(event)) {
                            // data 形如 {"seq":n,"data":"token"}
                            String token = om.readTree(data).path("data").asText("");
                            answer.append(token);
                        } else if ("phase".equals(event) && data != null) {
                            // data 形如 {"seq":n,"data":{"node":"rewrite","elapsedMs":123}}
                            JsonNode d = om.readTree(data).path("data");
                            phaseMs.put(d.path("node").asText(), d.path("elapsedMs").asInt(0));
                        } else if ("done".equals(event) && data != null) {
                            // data 形如 {"seq":n,"data":{...}}
                            boolean verified = om.readTree(data).path("data").path("verified").asBoolean(false);
                            String error = om.readTree(data).path("data").path("error").asText("");
                            if (error != null && !error.isEmpty()) {
                                emitter.send(SseEmitter.event().name("error").data(data));
                            }
                        }
                        emitter.send(SseEmitter.event().name(event).data(data));
                        if ("done".equals(event)) {
                            emitter.complete();
                            save(conversationId, question, answer.toString());
                        }
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                },
                err -> {
                    try {
                        emitter.send(SseEmitter.event().name("error")
                                .data("{\"seq\":-1,\"data\":\"Agent 服务暂不可用:" + err.getMessage() + "\"}"));
                    } catch (Exception ignored) { }
                    emitter.complete();
                },
                emitter::complete);
    }

    private void save(Long conversationId, String user, String assistant) {
        if (conversationId == null) {
            Conversation c = new Conversation();
            c.setTitle(user.length() > 20 ? user.substring(0, 20) : user);
            conversations.save(c);
            conversationId = c.getId();
        }
        Message m1 = new Message();
        m1.setConversationId(conversationId); m1.setRole("user"); m1.setContent(user);
        Message m2 = new Message();
        m2.setConversationId(conversationId); m2.setRole("assistant"); m2.setContent(assistant);
        messages.save(m1);
        messages.save(m2);
    }
}
```

- [ ] **Step 6: ChatController 确认(已含限流)**

> ⚠️ 不重复创建:**ChatController 已在 Task 5 Step 6 完成**(含限流 + AgentChatService 透传 + 429 处理),本任务**无需改动**。若按序执行到本步时尚未写 Task 5,按 Task 5 Step 6 的版本创建(带 RateLimiter 的完整版)。本步只验证:`/api/chat` 能透传 Python SSE(端到端见 Step 8)。

> `ChatService`(Phase 1 直连 LLM)保留不删——降级预案(spec §3.1)后续按开关切换。

- [ ] **Step 7: Java 编译 + Python 测试**

```bash
cd backend && mvn -q compile          # 期望:BUILD SUCCESS
cd ../python && uv run pytest -q             # 期望:全绿
```

- [ ] **Step 8: 端到端验证**

```bash
# 终端 A:cd backend && mvn spring-boot:run      (Java 9000)
# 终端 B:cd python && uv run uv run uvicorn app.main:app --port 9100   (Agent 9100)
# 终端 C:
curl -N -X POST http://localhost:9000/api/chat -H "Content-Type: application/json" \
  -d '{"message":"OpenWrt 无线配置的安装步骤是什么?"}'
# 期望:event:answer 增量、event:phase(带 {"node","elapsedMs"})、event:source、event:done
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "SELECT role, left(content, 40) FROM message ORDER BY id DESC LIMIT 2;"
# 期望:user/assistant 两条落库
curl http://localhost:9000/api/agent/health   # {"java":"UP","agent":"UP"}
```

- [ ] **Step 9: 浏览器演示验证**

打开 http://localhost:9000 → 提问 → 流式回答 → 引用来源;多轮追问(复用 conversationId 由 Java 自动带历史)。

- [ ] **Step 10: 提交**

```bash
git add -A && git commit -m "feat: end-to-end SSE proxy from java gateway to python agent"
```

**验收**:curl -N 看到 answer/source/done 事件流(含 phase 耗时);message 落库;浏览器端到端可用;`/api/agent/health` 双 UP。

---

### Task 8: Java 工程化#2:语义缓存(embedding 相似度 >0.95)

> 命中直接返回缓存回答,不调 Python/LLM——省 Token、降延迟(面试量化点:命中率、省 token 数)。

**Files:**
- Create: `backend/src/main/java/com/kbrag/cache/CosineSimilarity.java`(纯算法,可单测)
- Test: `backend/src/test/java/com/kbrag/cache/CosineSimilarityTest.java`
- Create: `backend/src/main/java/com/kbrag/cache/ChatCacheService.java`(含静态纯方法 selectBest,可单测)
- Modify: `backend/src/main/resources/application.yml`(app.cache)
- Modify: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`(lookup 命中直返 / 未命中完成后 put)

**Interfaces:**
- Consumes: `EmbeddingService.embed(List<String>) -> List<float[]>`(Phase 1)、StringRedisTemplate
- Produces: `CosineSimilarity.cosine(float[] a, float[] b) -> double`;`ChatCacheService.lookup(String question) -> Optional<CacheHit>`、`put(String question, String answer, String sourcesJson)`;`CacheHit(answer, sourcesJson, embeddingJson)`;SSE 新增事件 `cache_hit`

- [ ] **Step 1: 写失败测试(TDD)**

```java
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
        assertEquals("a", ChatCacheService.selectBest(candidates, new float[]{1f, 0f}, 0.95));
    }

    @Test
    void none_above_threshold_returns_null() {
        assertNull(ChatCacheService.selectBest(Map.of("a", new float[]{0f, 1f}), new float[]{1f, 0f}, 0.95));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
cd backend && mvn test -Dtest=CosineSimilarityTest
```

- [ ] **Step 3: 实现 CosineSimilarity 与 selectBest**

```java
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
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn test -Dtest=CosineSimilarityTest
# 期望:5 个测试 PASS
```

- [ ] **Step 5: 实现 ChatCacheService(Redis 存储 + 候选裁剪)**

```java
package com.kbrag.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbrag.ai.EmbeddingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 语义缓存:hash chat:cache:{id} 存 question/embedding/answer/sourcesJson;
 * 索引 list chat:cache:recent(最近 recentMax 条问题 id)——只对候选集算余弦,避免全量遍历(面试讲取舍)。
 */
@Service
public class ChatCacheService {
    private final StringRedisTemplate redis;
    private final EmbeddingService embeddingService;
    private final ObjectMapper om = new ObjectMapper();
    private final double threshold;
    private final int recentMax;

    public ChatCacheService(StringRedisTemplate redis, EmbeddingService embeddingService,
                            @Value("${app.cache.similarity-threshold:0.95}") double threshold,
                            @Value("${app.cache.recent-max:200}") int recentMax) {
        this.redis = redis;
        this.embeddingService = embeddingService;
        this.threshold = threshold;
        this.recentMax = recentMax;
    }

    public record CacheHit(String answer, String sourcesJson) {}

    /** 语义命中:embed 问题 → 与最近 recentMax 条算余弦 → 超过 threshold 取最高。 */
    public Optional<CacheHit> lookup(String question) {
        float[] qv = embeddingService.embed(List.of(question)).get(0);
        List<String> ids = redis.opsForList().range("chat:cache:recent", 0, -1);
        if (ids == null || ids.isEmpty()) return Optional.empty();
        Map<String, float[]> candidates = new LinkedHashMap<>();
        Map<String, String> answers = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String id : ids) {
            Map<Object, Object> entry = redis.opsForHash().entries("chat:cache:" + id);
            if (entry.isEmpty()) continue;
            candidates.put(id, parseEmbedding((String) entry.get("embedding")));
            answers.put(id, (String) entry.get("answer"));
            sources.put(id, (String) entry.get("sourcesJson"));
        }
        String best = CosineSimilarity.selectBest(candidates, qv, threshold);
        if (best == null) return Optional.empty();
        return Optional.of(new CacheHit(answers.get(best), sources.get(best)));
    }

    public void put(String question, String answer, String sourcesJson) {
        float[] qv = embeddingService.embed(List.of(question)).get(0);
        String id = String.valueOf(System.nanoTime());
        Map<String, String> entry = Map.of(
                "question", question, "embedding", toJson(qv),
                "answer", answer, "sourcesJson", sourcesJson,
                "ts", String.valueOf(System.currentTimeMillis()));
        redis.opsForHash().putAll("chat:cache:" + id, entry);
        redis.opsForList().leftPush("chat:cache:recent", id);
        redis.opsForList().trim("chat:cache:recent", 0, recentMax - 1);   // 只留最近 recentMax 条
    }

    private String toJson(float[] v) {
        try { return om.writeValueAsString(v); } catch (Exception e) { return "[]"; }
    }

    private float[] parseEmbedding(String json) {
        try {
            double[] d = om.readValue(json, double[].class);
            float[] f = new float[d.length];
            for (int i = 0; i < d.length; i++) f[i] = (float) d[i];
            return f;
        } catch (Exception e) { return new float[0]; }
    }
}
```

- [ ] **Step 6: application.yml 配置 + AgentChatService 接入**

```yaml
  cache:
    similarity-threshold: 0.95   # 命中阈值(spec §8)
    recent-max: 200              # 候选集上限(算余弦的条目数)
```

AgentChatService.stream 开头加缓存分支,完成时 put:

```java
public void stream(String question, Long conversationId, SseEmitter emitter) {
    Optional<ChatCacheService.CacheHit> hit = chatCache.lookup(question);
    if (hit.isPresent()) {                 // 命中:直返缓存,不调 Python(省 Token/降延迟)
        emitCacheAnswer(hit.get(), emitter);
        // 命中计数由 Task 9 接入(ObservabilityService.saveSpan(..., cacheHit=true)),本任务不依赖 Task 9
        return;
    }
    // ...原有透传流程
}

private void emitCacheAnswer(ChatCacheService.CacheHit hit, SseEmitter emitter) {
    try {
        emitter.send(SseEmitter.event().name("cache_hit").data("{\"seq\":1,\"data\":true}"));
        emitter.send(SseEmitter.event().name("answer").data("{\"seq\":2,\"data\":" + om.writeValueAsString(hit.answer()) + "}"));
        emitter.send(SseEmitter.event().name("source").data("{\"seq\":3,\"data\":" + hit.sourcesJson() + "}"));
        emitter.send(SseEmitter.event().name("done").data("{\"seq\":4,\"data\":null}"));
        emitter.complete();
    } catch (Exception e) { emitter.completeWithError(e); }
}
```

> 透传分支的 `source` 事件到达时暂存 `sourcesJson` 字符串,`done` 时 `chatCache.put(question, answer.toString(), sourcesJson)`(缓存完整回答)。
> 依赖边界:本任务只依赖 Task 5 的 ChatController/AgentChatService 与 Phase 1 的 EmbeddingService,**不依赖 Task 9**(ObservabilityService 在 Task 9 Step 3 才注入 AgentChatService,届时把 cacheHit 计数一并接上)。

- [ ] **Step 7: 验证**

```bash
cd backend && mvn spring-boot:run
# 第一次(未命中,正常走 Agent):curl -N ... -d '{"message":"OpenWrt 无线配置步骤"}'
# 第二次(命中):curl -N ... -d '{"message":"OpenWrt 无线配置步骤"}'
# 期望:第二次立即返回 cache_hit + 整段 answer(无流式逐字),无 Python 调用(Java 日志无转发)
# 相似问题(换措辞)也能命中:curl -N ... -d '{"message":"OpenWrt 无线怎么配置"}'
redis-cli LLEN chat:cache:recent          # 缓存条数
```

- [ ] **Step 8: 提交**

```bash
git add -A && git commit -m "feat: semantic cache with embedding similarity >0.95"
```

**验收**:CosineSimilarityTest 5/5 PASS;同问题二次命中(cache_hit 事件、无流式);换措辞相似问题命中;缓存条数在 recent-max 内。

---

### Task 9: Java 工程化#3:可观测(每轮 span 落库)+ /api/stats

> 最小化 span 概念,不引 OpenTelemetry:每轮对话一条 rag_span 落库(各阶段耗时/缓存命中),/api/stats 出聚合指标——面试量化素材来源。

**Files:**
- Create: `backend/src/main/java/com/kbrag/obs/RagSpan.java`(实体)
- Create: `backend/src/main/java/com/kbrag/obs/RagSpanRepository.java`
- Create: `backend/src/main/java/com/kbrag/obs/ObservabilityService.java`
- Modify: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`(done 时落 span;已收集 phaseMs)
- Create: `backend/src/main/java/com/kbrag/stats/StatsController.java`

**Interfaces:**
- Consumes: phaseMs(Task 7 已收集:rewrite/router/tools/generate/verify 各阶段耗时)、`MessageRepository`/`ConversationRepository`
- Produces: `ObservabilityService.saveSpan(conversationId, question, gatewayMs, phaseMs, cacheHit)`;`GET /api/stats -> {docCount, chunkCount, avgRetrievalMs, cacheHitRate}`

- [ ] **Step 1: 创建 RagSpan 实体与仓库**

```java
package com.kbrag.obs;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 每轮对话的可观测 span(spec §4.1 observability)。
 */
@Data
@Entity
@Table(name = "rag_span")
public class RagSpan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    @Column(columnDefinition = "text")
    private String question;
    private Integer gatewayMs;    // Java 转发全程耗时
    private Integer rewriteMs;    // 以下来自 Python phase 事件
    private Integer routerMs;
    private Integer toolsMs;      // 检索阶段(≈检索耗时)
    private Integer generateMs;   // LLM 生成
    private Integer verifyMs;
    private Boolean cacheHit;     // 是否语义缓存命中
    private LocalDateTime createdAt = LocalDateTime.now();
}
```

```java
package com.kbrag.obs;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RagSpanRepository extends JpaRepository<RagSpan, Long> {
    List<RagSpan> findTop100ByOrderByIdDesc();   // 最近 100 轮(聚合指标用)
}
```

- [ ] **Step 2: 实现 ObservabilityService**

```java
package com.kbrag.obs;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ObservabilityService {
    @Autowired private RagSpanRepository spans;

    public void saveSpan(Long conversationId, String question, long gatewayMs,
                         Map<String, Integer> phaseMs, boolean cacheHit) {
        RagSpan s = new RagSpan();
        s.setConversationId(conversationId);
        s.setQuestion(question);
        s.setGatewayMs((int) gatewayMs);
        s.setRewriteMs(phaseMs.getOrDefault("rewrite", 0));
        s.setRouterMs(phaseMs.getOrDefault("router", 0));
        s.setToolsMs(phaseMs.getOrDefault("tools", 0));
        s.setGenerateMs(phaseMs.getOrDefault("generate", 0));
        s.setVerifyMs(phaseMs.getOrDefault("verify", 0));
        s.setCacheHit(cacheHit);
        spans.save(s);
    }
}
```

- [ ] **Step 3: AgentChatService done 时落 span**

```java
// stream 方法:记录 t0,透传分支 done 事件里:
if ("done".equals(event)) {
    emitter.complete();
    save(conversationId, question, answer.toString());
    observabilityService.saveSpan(conversationId, question,
            System.currentTimeMillis() - t0, phaseMs, false);
}
```

- [ ] **Step 4: 实现 StatsController**

```java
package com.kbrag.stats;

import com.kbrag.document.DocumentChunkRepository;
import com.kbrag.document.DocumentRepository;
import com.kbrag.obs.RagSpan;
import com.kbrag.obs.RagSpanRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库统计(spec §7 GET /api/stats):文档/分块数、平均检索耗时、缓存命中率。
 */
@RestController
public class StatsController {
    @Autowired private DocumentRepository documents;
    @Autowired private DocumentChunkRepository chunks;
    @Autowired private RagSpanRepository spans;

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        List<RagSpan> recent = spans.findTop100ByOrderByIdDesc();
        double avgTools = recent.stream().mapToInt(RagSpan::getToolsMs)
                .filter(v -> v > 0).average().orElse(0);
        long hitCount = recent.stream().filter(RagSpan::getCacheHit).count();
        double hitRate = recent.isEmpty() ? 0 : hitCount * 1.0 / recent.size();
        return Map.of(
                "docCount", documents.count(),
                "chunkCount", chunks.count(),
                "avgRetrievalMs", Math.round(avgTools),      // 近 100 轮检索(工具阶段)均值
                "cacheHitRate", Math.round(hitRate * 100) / 100.0);
    }
}
```

- [ ] **Step 5: 验证**

```bash
cd backend && mvn spring-boot:run && cd ../python && uv run uv run uvicorn app.main:app --port 9100 &
# 问 3 轮(含 1 次缓存命中):
curl -N -X POST http://localhost:9000/api/chat -H "Content-Type: application/json" -d '{"message":"OpenWrt 无线配置步骤"}' > /dev/null
curl -N -X POST http://localhost:9000/api/chat -H "Content-Type: application/json" -d '{"message":"OpenWrt 无线配置步骤"}' > /dev/null  # 命中缓存
psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "SELECT rewrite_ms, router_ms, tools_ms, generate_ms, verify_ms, cache_hit FROM rag_span ORDER BY id DESC LIMIT 3;"
# 期望:有记录的 span 各阶段耗时 >0;命中那轮 cache_hit=true 且其他字段为 0
curl http://localhost:9000/api/stats
# 期望:{"docCount":N,"chunkCount":N,"avgRetrievalMs":xx,"cacheHitRate":0.xx}
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: per-turn rag spans and stats endpoint"
```

**验收**:每轮 rag_span 落库(各阶段耗时可查);/api/stats 返回四项指标;命中轮 cache_hit=true。

---

## 修订记录(2026-08-10 评审后回写)

| # | 问题 | 修订位置 |
|---|---|---|
| P5-1 | langchain-openai 1.x 的 OpenAIEmbeddings 是 pydantic v2 模型,`__delattr__` 拦截实例属性删除,`patch("...aembed_documents")` 退出 with 时抛 AttributeError | Task 3 Step 2:mock 目标改为模块级对象 `patch("app.llm.embeddings_model", fake)` |
| P5-2 | pytest 9.x 默认 importlib 导入模式,不再自动把项目根加入 sys.path,`uv run pytest` 报 `ModuleNotFoundError: No module named 'app'` | Task 1:pyproject.toml 加 `[tool.pytest.ini_options] pythonpath=["."] testpaths=["tests"]` |
| P5-3 | plan 测试代码幂等 key 工具名笔误("search_kb" vs 端点名 "search"),去重穿透导致测试真发 HTTP 到 fake 地址报 502 | Task 4 Step 3:测试 key 用端点名 + 与实现同源的 `json.dumps(sort_keys=True, ensure_ascii=False)` 构造 |
| P5-4 | `execute_tool` 对 null content 崩溃(`None[:500]` TypeError),且格式化循环在 try/except 外,错误逃逸自然化会炸下游 tools_node | Task 4 Step 2:`(hit.get('content') or '')[:500]`、`hit.get('headingPath') or ''` |
| P5-5 | 命中文本格式不能 round-trip:content 含 "[N] " 交叉引用会打碎 `re.split(r"\[\d+\] ")` 解析 | Task 6 Step 6:`_parse_hits` 改逐行扫描、锚定完整 meta 行;格式契约单点注释 |
| P5-6 | JavaClient "每会话一个实例"假设未固化:tools_node 每次 new → 重试轮 `_seen`/`_step` 重置 → Java 幂等缓存命中旧 step 返回降级摘要(检索丢失) | Task 6 Step 6:会话级 client 缓存(进程内 dict keyed by conversation_id,单进程 uvicorn 适用);Task 4 Step 1 docstring 记录约束 |
| P5-7 | `ddl-auto=update` 与 PG 生成列冲突:数据卷重建后 Hibernate 迁移发 `alter segmented_text set data type text`,PG 拒绝(cannot alter type of a column used by a generated column)→ 启动失败 | 已由实施者修复(commit 056e397):`ddl-auto=none` + schema.sql 全量建表(含 tool_call_log 幂等键字段);面试讲"DDL 全托管" |
| P5-8 | Task 5 的 ChatController 代码块引用 **Task 7 才创建的 AgentChatService**,且 `contentType(MediaType.TEXT_EVENT_STREAM_VALUE)` 用了 String 常量(编译不过),照抄必失败 | Task 5 Step 6:补完整 import + 前置依赖标注(Java 侧 Task 5/7 同批完成);contentType 改 `MediaType.TEXT_EVENT_STREAM`;Task 7 Step 6 改为"确认不改"(ChatController 已在 Task 5 完成) |
| P5-9 | Task 8 Step 6 引用 Task 9 的 ObservabilityService,单独做 Task 8 编译不过 | 去掉 `observabilityService.saveSpan` 调用,标注"命中计数由 Task 9 接入";Task 8 依赖边界:只依赖 Task 5 + Phase 1,不依赖 Task 9 |
| P5-10 | `redis.execute(script, keys, args...)` 的 args 是可变参数,plan 把 `List.of(...)` 整体传入被当成单个 args[0],StringRedisSerializer 序列化 List 抛 ClassCastException(限流 500) | Task 5 Step 5:args 逐个展开传入;运行期验证过(6 连发应 200×3 + 429×3) |

## Self-Review 记录

**Spec 覆盖(2026-08-08 spec):**
- §4.2 五节点图(改写/Router/工具/生成/自检)→ Task 6
- §4.2 防护(最大步数 8 / 工具超时 10s / 重复调用检测 / 错误自然语言化)→ Task 4 + Task 6(recursion_limit)
- §4.2 记忆滑动窗口 → Task 7 Step 5(Java 取最近 10 条);双压缩策略 → 移 Phase 3(见偏差说明)
- §4.1 tool-service(`/api/agent/tools/search|get-doc-detail|get-stats` + tool_call_log)→ Task 2
- §7 `GET /api/agent/health` → Task 2 Step 4
- §3.1 全链路 SSE 透传 → Task 7
- §8 检索无结果拒答 → Task 6 Step 8(generate 空 contexts 直接说明)
- §8 事件 seq → Task 7 Step 1(emit 递增 seq)
- §8 限流与熔断:用户级令牌桶限流(Redis Lua)→ **Task 5**;熔断/重试(Resilience4j)→ 留 Phase 3(与 rerank A/B 同批,偏差说明已标注)
- §8 语义缓存(embedding 相似度 >0.95)→ **Task 8**
- §4.1 observability(每轮 span)→ **Task 9**(最小化:落库 rag_span,不引 OpenTelemetry,后续可平滑换 Micrometer/OTel)
- §7 `GET /api/stats` → **Task 9**
- 降级预案(Java 直连 LLM)→ ChatService 保留,Phase 3 做开关。**无遗漏。**

**占位符扫描:** 无 TBD/TODO;tools_node 为 Task 6 Step 6 两阶段完整实现(LLM function calling 决定 + 执行,含 _parse_hits);run_agent 单次 astream_events 完成采集(无二次 ainvoke);所有节点对 LLM 响应统一用 `getattr(resp, "content", "")` 防御性取值,与 FakeChat 的 SimpleNamespace 形态一致;Task 8 的 emitCacheAnswer 引用 observabilityService 已注明与 Task 9 的同批提交关系。

**类型一致性:**
- `JavaClient` 方法 `search_kb/get_doc_detail/get_stats` 与 `execute_tool` 映射、Java 端点路径一致
- LLM tool_calls 契约:`[{"name": str, "args": dict}]`——langchain 解析 DeepSeek function calling 后的标准形态,tools_node 消费、测试用 SimpleNamespace(tool_calls=[...]) 对齐
- `run_agent` 产出 `(kind, payload)` 元组:answer/phase/sources/done;phase payload 为 `{"node", "elapsedMs"}`——sse.py `emit("phase", payload)` 原样透传,Java 解析 `{"seq":n,"data":{"node","elapsedMs"}}` 三方一致
- SSE 事件名:`answer/source/done/error`(Phase 1 前端解析)+ `phase`/`cache_hit`(前端忽略未知事件,兼容)
- `AgentState` 字段在 nodes/graph/sse 间一致(`question/history/needs_retrieval/contexts/answer/sources/attempts/verified/error/tool_calls`)
- Java `AgentChatService.stream` 签名与 ChatController 调用一致;`Message`/`Conversation` 为 Lombok getter/setter(Phase 1 已统一)
- `app.agent.base-url` 为 Java 侧唯一配置点(AgentHealthController 与 AgentChatService 同源,application.yml);`app.rate-limit`/`app.cache` 各自独立配置块
- TokenBucket/RateLimiter、CosineSimilarity/ChatCacheService 均"纯算法类可单测 + 存储层薄封装"模式,与 Phase 1 的 RrfFusion/HeadingAwareChunker 一致
- **幂等契约(2026-08-10 对齐 spec §9)**:Python `JavaClient` 携带 `conversationId + agentStepId`(单轮图内递增)→ Java `ToolController.idempotent()` 查 `tool_call_log.idempotent_key` 唯一索引,命中返回 `{"idempotent":true,"output":上次摘要}`;`conversation_id` 经 AgentChatRequest → build_input → AgentState → tools_node 全链路传递,测试的 FakeJavaClient 不涉及该字段(conversation_id 为空时跳过幂等)
