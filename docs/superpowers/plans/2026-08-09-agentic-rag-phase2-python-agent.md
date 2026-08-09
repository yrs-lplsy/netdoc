# Agentic RAG Phase 2:Python Agent 服务(LangGraph 五节点 + Java 工具端点 + 全链路 SSE)实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本项目按 HANDOFF 协作协议执行:**用户自己动手写代码**,助手负责任务拆解、验收、报错拆解、机械性修复。任务推进用 executing-plans 逐任务验收。

**Goal:** 3 周内交付 Python Agent 服务:FastAPI + LangGraph 五节点(查询改写 → 检索决策 Router → 工具调用 → 生成 → 忠实度自检)+ Java 工具端点 `/api/agent/tools/*` + 全链路 SSE 透传,形成"Python 管思考、Java 管执行"的完整 Agent 故事。

**Architecture:** Python 独立服务(`python/` 目录,FastAPI,端口 **8001**)承载 LangGraph 有状态图;图节点通过 HTTP 反向调用 Java 的工具端点(search_kb/get_doc_detail/get_stats)完成检索与溯源;Java 的 `/api/chat` 网关用 WebClient 把 SSE 事件原样透传给浏览器;Java 负责会话落库与历史回放,Python 负责 Agent 编排与 LLM 调用。

**Tech Stack:** Python 3.10+、FastAPI、uvicorn、sse-starlette、LangGraph(0.2+)、langchain-openai(OpenAI 兼容协议)、httpx、python-dotenv、pytest、pytest-asyncio;Java 侧新增 spring-boot-starter-webflux(仅用 WebClient)。

## Global Constraints

- LLM:DeepSeek,base_url `https://api.deepseek.com/v1`,model `deepseek-chat`;Embedding:硅基流动 BGE-M3,base_url `https://api.siliconflow.cn/v1`,model `BAAI/bge-m3`,维度 1024(与 Phase 1 一致)
- API Key 只从环境变量读取:复用 `backend/.env`(DEEPSEEK_API_KEY / SILICONFLOW_API_KEY),禁止写进代码/git
- 端口:Java 9000、Python **8001**、PG 5433、Redis 6379;**8080 被 rpki-system 占用,永远别用**
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
| Task 1 | Python 服务骨架(FastAPI + 配置 + /health) | `curl :8001/health` 返回 UP |
| Task 2 | Java Agent 工具端点(search/get-doc-detail/get-stats + tool_call_log + /api/agent/health) | curl 三个工具端点可用,tool_call_log 落库 |
| Task 3 | Python LLM/Embedding 客户端封装 | pytest mock 通过 + 手动真调通 chat/embedding |
| Task 4 | Python 工具层(JavaApiClient + 工具 schema + 超时/重复检测) | Python 能真调通 Java 工具端点 |
| Task 5 | LangGraph 五节点图(TDD:Router/重复检测/自检重试) | pytest 通过,图单测全绿 |
| Task 6 | 全链路 SSE(Java WebClient 透传 + Python stream_events + 端到端) | 浏览器提问流式回答,message 落库 |

---

### Task 1: Python 服务骨架

**Files:**
- Create: `python/requirements.txt`
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

- [ ] **Step 1: 创建 requirements.txt**

```txt
fastapi>=0.115
uvicorn[standard]>=0.32
sse-starlette>=2.1
langgraph>=0.2
langchain-openai>=0.2
httpx>=0.27
python-dotenv>=1.0
pytest>=8.0
pytest-asyncio>=0.24
```

安装:`cd python && pip install -r requirements.txt`(用 venv: `python -m venv .venv && source .venv/bin/activate`)

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

cd python && pytest -q
# 期望:1 passed
uvicorn app.main:app --port 8001
curl http://localhost:8001/health   # {"status":"UP"}
```

- [ ] **Step 6: 提交**

```bash
cd ../agentic-rag && git add python/ && git commit -m "feat: python agent service skeleton with health check"
```

**验收**:`pytest` 全绿;`curl :8001/health` 返回 `{"status":"UP"}`。

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
 */
@Data
@Entity
@Table(name = "tool_call_log")
public class ToolCallLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long conversationId;
    private String toolName;
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

public interface ToolCallLogRepository extends JpaRepository<ToolCallLog, Long> {}
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

/**
 * Agent 工具端点(spec §4.1 tool-service):供 Python Agent 服务反向调用。
 * 每次调用全量落库 tool_call_log(安全审计)。
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
    public List<SearchResult> search(@RequestBody ToolRequest req) {
        long t0 = System.currentTimeMillis();
        try {
            List<SearchResult> hits = retriever.search(req.query(), req.topK() == 0 ? 5 : req.topK());
            log("search_kb", req, hits.size() + " hits", t0, true);
            return hits;
        } catch (Exception e) {
            log("search_kb", req, e.getMessage(), t0, false);
            throw e;
        }
    }

    @PostMapping("/get-doc-detail")
    public Map<String, Object> getDocDetail(@RequestBody Map<String, Long> body) {
        long t0 = System.currentTimeMillis();
        Long docId = body.get("docId");
        try {
            Document doc = documents.findById(docId).orElseThrow();
            List<DocumentChunk> chunkList = chunks.findByDocId(docId);
            Map<String, Object> result = Map.of(
                    "id", doc.getId(), "title", doc.getTitle(), "status", doc.getStatus(),
                    "chunks", chunkList.stream().map(c -> Map.of(
                            "id", c.getId(), "content", c.getContent(), "headingPath", c.getHeadingPath())).toList());
            log("get_doc_detail", Map.of("docId", docId), chunkList.size() + " chunks", t0, true);
            return result;
        } catch (Exception e) {
            log("get_doc_detail", Map.of("docId", docId), e.getMessage(), t0, false);
            throw e;
        }
    }

    @PostMapping("/get-stats")
    public Map<String, Object> getStats() {
        long t0 = System.currentTimeMillis();
        try {
            long docCount = documents.count();
            long chunkCount = chunks.count();
            log("get_stats", Map.of(), docCount + " docs / " + chunkCount + " chunks", t0, true);
            return Map.of("docCount", docCount, "chunkCount", chunkCount);
        } catch (Exception e) {
            log("get_stats", Map.of(), e.getMessage(), t0, false);
            throw e;
        }
    }

    private void log(String tool, Object input, String output, long t0, boolean ok) {
        ToolCallLog l = new ToolCallLog();
        l.setToolName(tool);
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

    public record ToolRequest(String query, int topK) {}
}
```

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
                                 @Value("${app.agent.base-url:http://localhost:8001}") String agentBaseUrl) {
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

> application.yml 的 `app:` 下新增(Java 侧唯一配置点,Task 6 的 AgentChatService 复用,勿再硬编码):

```yaml
  agent:
    base-url: http://localhost:8001   # Python Agent 服务地址
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
cd python && pytest -q
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
    """反向调用 Java 工具端点;记录已执行调用,重复调用检测。"""

    def __init__(self, base_url: str = JAVA_BASE_URL, timeout: float = TOOL_TIMEOUT_SECONDS):
        self.base_url = base_url
        self.timeout = timeout
        self._seen: set[tuple[str, str]] = set()

    def _call(self, tool: str, payload: dict) -> dict:
        key = (tool, json.dumps(payload, sort_keys=True, ensure_ascii=False))
        if key in self._seen:
            raise RuntimeError(f"工具 {tool} 已用相同参数调用过,已跳过重复调用")
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
                f"[{i}] 片段ID={hit.get('chunkId')} 文档ID={hit.get('docId')} 标题={hit.get('headingPath')}\n{hit.get('content', '')[:500]}"
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
cd python && pytest -q
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

### Task 5: LangGraph 五节点图(TDD)

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

async def test_router_direct_chat_skips_retrieval():
    from app.nodes.router import router_node
    fake = FakeChat(['{"needs_retrieval": false}'])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is False


async def test_router_invalid_json_defaults_to_retrieve():
    from app.nodes.router import router_node
    fake = FakeChat(["不是 JSON"])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is True  # 解析失败降级走检索


# ---- Tools(两阶段:LLM function calling 决定 → 执行)----

async def test_tools_node_decides_and_executes():
    from app.nodes.tools_node import tools_node
    call = {"name": "search_kb", "args": {"query": "安装", "top_k": 5}}
    fake_chat = FakeChat([SimpleNamespace(content="", tool_calls=[call])])
    fake_client = FakeJavaClient()
    state = dict(base_state)
    out = await tools_node(state, fake_chat, fake_client)
    assert len(out["contexts"]) == 1  # LLM 决定调用 search_kb,结果解析进 contexts
    assert out["tool_calls"] == [call]


async def test_tools_node_duplicate_call_deduped():
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

async def test_verify_fail_marks_retry():
    from app.nodes.verify import verify_node
    fake = FakeChat(["FAIL 回答中包含了资料没有的信息"])
    state = await verify_node(dict(base_state), fake)
    assert state["verified"] is False
    assert state["attempts"] == 1
    assert "未通过" in state["error"]  # FAIL 理由写入 error,回喂 rewrite


async def test_verify_pass():
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


async def test_generate_rejects_when_no_contexts_and_retrieval_needed():
    from app.nodes.generate import generate_node
    state = dict(base_state)  # needs_retrieval=True
    out = await generate_node(state, FakeStreamChat(["资料"]))
    assert "未找到" in out["answer"]
    assert out["sources"] == []


async def test_generate_answers_direct_chat_without_contexts():
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


async def tools_node(state: AgentState, chat=None, client=None) -> AgentState:
    """阶段1:LLM function calling 决定工具调用;阶段2:循环执行(去重/超时/错误自然语言化)。"""
    from app.java_client import JavaClient
    from app.llm import chat_model
    from app.tools import TOOL_SCHEMAS, execute_tool

    chat = chat or chat_model
    client = client or JavaClient()
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
    """从 execute_tool 的自然语言输出反解出结构化片段(单测覆盖)。"""
    import re

    hits = []
    for block in re.split(r"\[\d+\] ", text):
        if not block.strip():
            continue
        lines = block.splitlines()
        meta = lines[0] if lines else ""
        content = "\n".join(lines[1:]) if len(lines) > 1 else ""
        chunk_id = int(re.search(r"片段ID=(\d+)", meta).group(1))
        doc_id = int(re.search(r"文档ID=(\d+)", meta).group(1))
        title = re.search(r"标题=(.*)", meta).group(1)
        hits.append({"chunkId": chunk_id, "docId": doc_id, "headingPath": title, "content": content})
    return hits
```

> execute_tool 的输出格式(见 Task 4 Step 2)与 _parse_hits 的正则一一对应,改格式必须同步改解析。
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
    recursion_limit=MAX_STEPS 防死循环。产出 (kind, payload):answer/sources/done。"""
    from app.config import MAX_STEPS

    graph = build_graph()
    final_answer, sources, verified, error = "", [], False, None
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
            yield ("phase", node)
        elif kind == "on_chain_end" and node == "generate":
            output = event["data"].get("output") or {}
            final_answer = output.get("answer", "")
            sources = output.get("sources") or []
        elif kind == "on_chain_end" and node == "verify":
            output = event["data"].get("output") or {}
            verified = bool(output.get("verified"))
            error = output.get("error")
    yield ("sources", sources)
    yield ("done", {"answer": final_answer, "verified": verified, "error": error})
```

- [ ] **Step 10: 运行测试(Step 2 的用例 + 补 tools 解析单测)**

```bash
cd python && pytest -q
# 期望:test_graph.py 全部 PASS(router/verify 用 FakeChat,不真调 LLM)
```

> 若 FakeChat 与节点签名不匹配(节点内 `from app.llm import chat_model` 而非参数注入),调整单测为 monkeypatch `app.nodes.router.chat_model` 等模块级引用。

- [ ] **Step 11: 提交**

```bash
git add -A && git commit -m "feat: langgraph five-node agent graph with guards"
```

**验收**:pytest 全绿(Router 决策/JSON 降级/重复检测/verify 重试分支);`build_graph()` 可编译,图结构含 5 节点 + 3 条件边。

---

### Task 6: 全链路 SSE(Java WebClient 透传 + Python stream_events + 端到端)

**Files:**
- Create: `python/app/sse.py`(AgentChatRequest + SSE 事件生成器)
- Modify: `python/app/main.py`(挂 /agent/chat SSE 端点)
- Create: `python/tests/test_sse.py`
- Modify: `backend/src/main/java/com/kbrag/chat/ChatController.java`(WebClient 转发)
- Create: `backend/src/main/java/com/kbrag/chat/AgentChatService.java`
- Modify: `backend/src/main/java/com/kbrag/chat/ChatService.java`(保留,降级用)

**Interfaces:**
- Consumes: `run_agent`(Task 5)、`AgentState`、Phase 1 的 `MessageRepository`/`ConversationRepository`
- Produces: `POST http://localhost:8001/agent/chat {message, conversation_id, history} → SSE(answer/sources/done/error)`;Java `POST /api/chat` 行为变为:透传 Python SSE 事件,`done` 后落库 message(行为与 Phase 1 前端兼容)

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
cd python && pytest -q
# 期望:全绿
```

- [ ] **Step 5: 创建 Java AgentChatService(WebClient 透传 + 落库)**

```java
package com.kbrag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;
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
                            @Value("${app.agent.base-url:http://localhost:8001}") String agentBaseUrl) {
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
        stream.subscribe(
                sse -> {
                    try {
                        String event = sse.event();
                        String data = sse.data();
                        if ("answer".equals(event)) {
                            // data 形如 {"seq":n,"data":"token"}
                            String token = om.readTree(data).path("data").asText("");
                            answer.append(token);
                        }
                        if ("done".equals(event) && data != null) {
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

- [ ] **Step 6: 改造 ChatController(指向 AgentChatService,保留 ChatService 为降级)**

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
    @Autowired private AgentChatService agentChatService;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    public record ChatRequest(String message, Long conversationId) {}

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(180_000L);
        executor.execute(() -> agentChatService.stream(req.message(), req.conversationId(), emitter));
        return emitter;
    }
}
```

> `ChatService`(Phase 1 直连 LLM)保留不删——降级预案(spec §3.1)后续按开关切换。

- [ ] **Step 7: Java 编译 + Python 测试**

```bash
cd backend && mvn -q compile          # 期望:BUILD SUCCESS
cd ../python && pytest -q             # 期望:全绿
```

- [ ] **Step 8: 端到端验证**

```bash
# 终端 A:cd backend && mvn spring-boot:run      (Java 9000)
# 终端 B:cd python && uvicorn app.main:app --port 8001   (Agent 8001)
# 终端 C:
curl -N -X POST http://localhost:9000/api/chat -H "Content-Type: application/json" \
  -d '{"message":"OpenWrt 无线配置的安装步骤是什么?"}'
# 期望:event:answer 增量(中间含 router/tools 阶段日志)、event:source、event:done
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

**验收**:curl -N 看到 answer/source/done 事件流;message 落库;浏览器端到端可用;`/api/agent/health` 双 UP。

---

## Self-Review 记录

**Spec 覆盖(2026-08-08 spec):**
- §4.2 五节点图(改写/Router/工具/生成/自检)→ Task 5
- §4.2 防护(最大步数 8 / 工具超时 10s / 重复调用检测 / 错误自然语言化)→ Task 4 Step 1 + Task 5 Step 10(recursion_limit)
- §4.2 记忆滑动窗口 → Task 6 Step 5(Java 取最近 10 条);双压缩策略 → 移 Phase 3(见偏差说明)
- §4.1 tool-service(`/api/agent/tools/search|get-doc-detail|get-stats` + tool_call_log)→ Task 2
- §7 `GET /api/agent/health` → Task 2 Step 4
- §3.1 全链路 SSE 透传 → Task 6
- §8 检索无结果拒答 → Task 5 Step 8(generate 空 contexts 直接说明)
- §8 事件 seq → Task 6 Step 1(emit 递增 seq)
- 降级预案(Java 直连 LLM)→ ChatService 保留,Phase 3 做开关。**无遗漏。**

**占位符扫描:** 无 TBD/TODO;tools_node 为 Step 6 两阶段完整实现(LLM function calling 决定 + 执行,含 _parse_hits);run_agent 单次 astream_events 完成采集(无二次 ainvoke);所有节点对 LLM 响应统一用 `getattr(resp, "content", "")` 防御性取值,与 FakeChat 的 SimpleNamespace 形态一致。

**类型一致性:**
- `JavaClient` 方法 `search_kb/get_doc_detail/get_stats` 与 `execute_tool` 映射、Java 端点路径一致
- LLM tool_calls 契约:`[{"name": str, "args": dict}]`——langchain 解析 DeepSeek function calling 后的标准形态,tools_node 消费、测试用 SimpleNamespace(tool_calls=[...]) 对齐
- `run_agent` 产出 `(kind, payload)` 元组:answer/sources/done,与 Task 6 event_stream 消费一致;SSE 事件名 `answer/source/done/error` 与 Phase 1 前端 index.html 解析一致
- `AgentState` 字段在 nodes/graph/sse 间一致(`question/history/needs_retrieval/contexts/answer/sources/attempts/verified/error/tool_calls`)
- Java `AgentChatService.stream` 签名与 ChatController 调用一致;`Message`/`Conversation` 为 Lombok getter/setter(Phase 1 已统一)
- `app.agent.base-url` 为 Java 侧唯一配置点(AgentHealthController 与 AgentChatService 同源,application.yml)
