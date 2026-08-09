# NetDoc:网络设备技术文档智能问答系统(Agentic RAG)

Java 后端(Spring Boot 3 + pgvector)+ Python Agent 服务(FastAPI + LangGraph)的网络设备技术文档智能问答系统。
知识库素材:OpenWrt/路由器技术文档 + 个人部署踩坑笔记——与作者端侧路由器 AI Agent 项目(MT799X / OpenWrt / RK NPU)组成"云边一套"叙事。

> 设计文档见 `docs/superpowers/specs/2026-08-08-agentic-rag-project-design.md`,实施计划见 `docs/superpowers/plans/`。

## 架构

职责分工:**Java 管"执行"(检索、存储、工程化),Python 管"思考"(Agent 编排)**。

```mermaid
graph TD
    B[浏览器] -->|REST + SSE 流式| J[Java 后端 Spring Boot 3]
    J -->|Agent 工具调用 HTTP| P[Python Agent 服务 FastAPI + LangGraph]
    J -->|混合检索/落库| PG[(PostgreSQL 16 + pgvector)]
    J -->|限流/缓存/会话| R[(Redis 7)]
    P -->|search_kb / get_doc_detail / get_stats| J
    P -->|LLM 调用| D[DeepSeek API]
    P -->|向量化| E[硅基流动 BGE-M3]
    PG --> P
    R --> P
```

## 技术栈

- **Java 17 / Spring Boot 3.3**:文档管线(PDFBox/POI/commonmark + 标题感知分块)、混合检索(tsvector + pgvector HNSW + RRF)、SSE 流式对话(LangChain4j)、jieba 中文分词
- **PostgreSQL 16 + pgvector**:向量(1024 维 HNSW 索引)+ 全文(tsvector GIN 索引)双路检索
- **Redis 7**:会话、限流、语义缓存(Phase 3 启用)
- **LLM**:DeepSeek `deepseek-chat`(OpenAI 兼容协议);Embedding:BGE-M3(1024 维)

## 启动步骤

```bash
# 1. 基础设施(PG+pgvector 映射 5433、Redis 6379)
docker compose up -d

# 2. API Key:backend/.env 配置(application.yml 已配 spring.config.import 自动读取)
#    DEEPSEEK_API_KEY=sk-xxx
#    SILICONFLOW_API_KEY=sk-xxx

# 3. 启动后端(端口 9000)
cd backend && mvn spring-boot:run

# 4. 验证
curl http://localhost:9000/actuator/health        # {"status":"UP"}
curl -F "file=@sample.md" http://localhost:9000/api/documents   # 上传文档
curl http://localhost:9000/api/documents          # 文档列表

# 5. 打开 http://localhost:9000 提问(演示页)

# 6.(Phase 2)启动 Python Agent 服务(端口 9100,供 /api/chat 全链路透传)
cd python && uv sync && uv run uvicorn app.main:app --port 9100
```

> Python 依赖用 uv 管理(`pyproject.toml` + `uv.lock` 可复现构建);本机 8080 被其他项目占用,服务固定使用 **9000**(Java)/ **9100**(Python Agent)端口,同属 9 系列避开 80 系列防混淆;PG 使用 5433(5432 冲突)。

## API(Phase 1 已实现)

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/documents` | 上传文档(multipart),异步解析→分块→向量化入库 |
| GET | `/api/documents?status=` | 文档列表(可按状态过滤) |
| DELETE | `/api/documents/{id}` | 删除文档(连带分块) |
| POST | `/api/retrieve` | 混合检索调试(向量 Top30 + 关键词 Top30 → RRF → TopN) |
| POST | `/api/chat` | SSE 流式对话:事件 `answer`(增量)/`source`(引用)/`done`/`error`,事件体带递增 `seq` |
| GET | `/actuator/health` | 健康检查 |

## 演示脚本

1. 上传 3-5 份真实素材(OpenWrt 手册/技术文档,Markdown 优先)
2. 打开 http://localhost:9000 提问 → 流式回答 → 查看引用来源
3. 换关键词再问(验证混合检索:语义 + 关键词双路命中)
4. 查库验证落库:`psql postgresql://kbrag:kbrag123@localhost:5433/kbrag -c "SELECT role, left(content, 40) FROM message;"`

## 路线图

- **Phase 1(已完成)**:文档管线 + 混合检索 + SSE 流式对话
- **Phase 2(进行中)**:Python Agent 服务——LangGraph 五节点(查询改写 → 检索决策 Router → 工具调用 → 生成 → 忠实度自检)、Java 工具端点 `/api/agent/tools/*`、全链路 SSE 透传
- **Phase 3**:限流/语义缓存/可观测/评测体系(120 条评测集 + Recall@10/MRR/忠实度)
