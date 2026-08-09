# Agentic RAG 企业知识库问答系统 —— 设计文档

> 日期：2026-08-08
> 状态：已获用户确认的设计定稿
> 本文档为权威版本，替代同日早期文档《企业知识库RAG项目-技术方案.md》（该文档中与本设计冲突的部分以本文档为准）

---

## 1. 背景与目标

### 1.1 背景

- 委托人：2027 届硬件专业本科生（非科班软件、无 ML 论文），正值秋招
- 目标岗位：AI Agent 应用开发 / 后端开发（大模型应用方向）
- 调研结论（2026-08-08 完成，含大厂 JD 实证与面经实证）：
  - 纯 Agent 算法岗要求硕博 + 顶会论文 + 竞赛名次（京东/阿里达摩院/字节豆包 JD 实锤），本科生/非科班几乎无法进面
  - Agent 应用开发岗 80% 只要求本科及以上，不卡硕士、不要求训练经验，招聘量级远大于算法向
  - 应用向面试内容 ≈ 50-60% Java 工程八股（并发/MySQL/Redis/分布式锁）+ 30-40% Agent/RAG 应用技术（RAG 管线/Function Calling/MCP/记忆/编排/SSE/幻觉治理）
  - Java 工程能力是差异化卖点（纯 Python 选手的短板），不是劣势

### 1.2 目标

- 交付一个能深挖、有量化指标、同时覆盖"后端（大模型方向）"与"Agent 应用开发"两类岗位考察面的个人项目
- 时间约束：秋招正在进行，第 2 周末需产出可演示 MVP 开始投递，第 4 周末带量化指标投正式批

### 1.3 项目形态（已确认）

**NetDoc（网络设备技术文档智能问答系统）**：Java 主后端（Spring Boot 3）+ Python 独立 Agent 服务（FastAPI + LangGraph），前后端分离双服务架构，共享 PostgreSQL+pgvector 与 Redis。

> 命名决策（2026-08-09）：定位从泛化的"企业知识库"收窄为"网络设备技术文档"垂直领域——与作者端侧路由器 AI Agent 项目（MT799X / OpenWrt / RK NPU）形成"云边一套"的组合叙事；知识库素材用 OpenWrt 文档与个人部署踩坑记录（一手、唯一，面试可讲细节）。

### 1.4 选型论证：为什么不是纯 Python / 纯 Java（面试直接可用）

**方案 A：纯 Python 整套（FastAPI + LangGraph）——被否定的原因**
- 优点：Agent/RAG/Prompt/上下文压缩/token 管控生态开箱即用；单语言、原型快、调试 Agent 逻辑方便
- 硬伤：
  1. Python 做企业业务后端成熟度不如 Java：高并发、大量 IO 并发、连接管控、定时任务、事务、缓存管理
  2. 简历无 Java 工程背书，与投递序列（Java 后端大模型方向）不匹配，面试缺 Java 项目证据——秋招硬伤
  3. 生产环境：GIL、线程池坑、内存泄漏、多进程部署麻烦、定时任务可靠性差
  4. 后期企业功能（RBAC、部门隔离、审计日志、分布式锁、SSE 网关、异步任务）Java 生态更顺手

**方案 B：Java 主后端 + Python 独立 Agent 服务——选定**，三条理由：
1. **双序列简历背书**：一份项目同时覆盖"Java 分布式后端（Redis/MySQL/分布式锁/事务）+ Python Agent 大模型工程（RAG/上下文压缩/工具调度）"两大热门方向，面试素材翻倍
2. **技术解耦**：Agent 侧频繁调试 Prompt/分片策略/Agent 流程图，改动 Python 服务完全不影响稳定的 Java 业务层
3. **各司其职**：Java 扛并发、业务、权限、事务；Python 专注 LLM 生态、Agent 状态机，避开 Python 做业务后端的缺点

**结论**：不是"为了用 Java 而用 Java"，而是"执行层 Java 生态成熟、思考层 Python 生态成熟"的职责分工（面试话术详见 11.2）。

---

## 2. 岗位定位与投递策略

### 2.1 主投序列（按 JD 关键词筛选）

| 序列 | 关键词 | 投递 |
|---|---|---|
| 后端开发工程师（大模型/AI 应用业务组） | 高并发、分布式、中间件 + LLM 应用加分 | ✅ 主投 |
| AI 应用开发 / Agent 应用开发 | LangChain/LangGraph、RAG、MCP、SSE | ✅ 主投 |
| 大模型算法 / LLM 算法 / AI 研究员 | SFT/RLHF/预训练/论文 | ❌ 不投 |

### 2.2 筛选原则

- 投递前必看岗位偏"应用落地"还是偏"算法"，只投应用落地向（美团面经原话）
- 简历核心叙事：**Java 工程化能力 + Agent/RAG 落地能力**（Java 是入场券，Agent 是差异化）

---

## 3. 总体架构

职责分工：**Python 管"思考"（Agent 编排），Java 管"执行"（检索、存储、工程化）**

```
浏览器 (Vue3)
   │  REST + SSE（Java 侧 SseEmitter 透传流式）
   ▼
┌────────────────────────────────────────────┐
│  Java 后端 (Spring Boot 3) —— 网关/执行层     │
│  · 文档管理：上传/解析/分块/入库/状态/版本     │
│  · 检索服务：tsvector+pgvector 混合→RRF→rerank│
│  · 对话网关：SSE 透传/限流/语义缓存/重试降级    │
│  · 会话与记忆：PG 持久化 + Redis 短期          │
│  · 评测模块：Recall@K/MRR/忠实度              │
│  · 可观测：每轮 span（耗时/Token 数）          │
│  · 工具服务：/api/agent/tools/*（供 Python 调）│
└──────────────┬─────────────────────────────┘
               │ HTTP（Agent 工具调用反向调 Java API）
               ▼
┌────────────────────────────────────────────┐
│  Python Agent 模块 (FastAPI + LangGraph)    │
│  图编排：查询改写→检索决策Router→工具调用      │
│         →生成(带引用)→忠实度自检→(失败重试一次)│
│  工具集：search_kb / get_doc_detail / get_stats│
│  记忆：上下文压缩（滑动窗口+滚动摘要）          │
│  LLM：DeepSeek（Function Calling/JSON 约束） │
│  流式：LangGraph stream_events → FastAPI SSE │
└────────────────────────────────────────────┘
  共享存储：PostgreSQL+pgvector ｜ Redis
  外部服务：DeepSeek API ｜ BGE-M3 embedding(API) ｜ bge-reranker(API)
```

### 职责边界清单（面试讲分层架构用）

**Java（业务网关/主后端）——负责企业业务，不碰 AI 内部逻辑**
- 用户登录、RBAC 权限、多租户/部门隔离知识库（二期）
- 文件上传、文档管理、知识库 CRUD、异步任务队列
- Redis 限流、分布式锁、会话管理、MySQL 事务
- 请求鉴权、日志、审计（tool_call_log）、接口管理
- 接收前端问答请求 → HTTP 调 Python Agent 服务 → 流式结果转发 SSE → 记录问答日志

**Python（AI-Agent 层）——只负责 AI 逻辑，不碰企业业务**
- 文档切片、向量化、向量库检索、RAG 检索增强
- Agent 工具调用（文档检索、知识库搜索、文档溯源、可选在线搜索）
- 上下文窗口压缩（自研双策略）、token 计算、prompt 管理、多轮对话记忆
- LLM 调用、Function-call、Agent 状态机（LangGraph）

### 3.1 全链路流式（关键设计点）

```
前端 SSE ←Java 透传(WebClient)← Python(FastAPI SSE)← LangGraph stream_events← DeepSeek
```

- 事件带 sequence 序号，前端断线可重连续传；每个环节设超时兜底
- 降级预案（时间不足时）：生成阶段 Java 直连 LLM 流式，Python 仅做"思考前处理"（改写/决策），流式链路简化但 Agent 故事弱化

---

## 4. 模块设计

### 4.1 Java 后端模块（执行层）

| 模块 | 职责 | 关键实现 |
|---|---|---|
| document-service | 上传/解析/分块/入库 | PDFBox、POI、commonmark；标题感知分块（chunk 400-800 字符、overlap 80-150）；异步处理 + 状态机（processing/ready/failed） |
| retrieval-service | 混合检索 + 重排 | tsvector GIN 索引（稀疏）+ pgvector HNSW（稠密）→ RRF 融合 Top10 → rerank Top5 → 分数阈值判定"无相关知识"；embedding/rerank 外部调用统一走 LangChain4j 抽象（Java 侧语义缓存与重排依赖它，简历关键词有真实落点） |
| chat-gateway | 对话入口 | SseEmitter 流式透传；用户级令牌桶限流（Redis）；语义缓存（embedding 相似度 >0.95 命中）；重试与熔断 |
| memory-service | 会话与记忆 | PG 持久化 conversation/message；Redis 短期记忆；自研双压缩策略 micro_compact/snip_compact（Python 侧触发，见 4.2） |
| eval-service | 评测 | 评测集管理；Recall@K/MRR 计算（Java 实现）；忠实度打分（LLM-as-judge） |
| observability | 可观测 | 每轮 span：改写/检索/LLM 耗时、Token 数、缓存命中率；/actuator 指标端点 |
| tool-service | Agent 工具端点 | /api/agent/tools/search、/get-doc-detail、/get-stats；工具调用全量落库 tool_call_log（安全审计） |

### 4.2 Python Agent 模块（思考层，FastAPI + LangGraph）

- 图节点（LangGraph 有状态图）：
  1. **查询改写**：指代消解、口语转检索词、复杂问题拆子问题
  2. **检索决策 Router**：判断是否需要检索（闲聊/拒答直答）；决定检索哪个知识库
  3. **工具调用**：search_kb / get_doc_detail（文档溯源）/ get_stats（HTTP 调 Java）；可选 web_search（在线搜索，弥补知识库覆盖不足）；工具权限与超时
  4. **生成**：带引用标注的流式回答（强约束 Prompt）
  5. **忠实度自检**：LLM 校验回答是否忠实于检索片段；不通过 → 改写重检索一次，仍不过则降级拒答
- 防护：最大迭代步数 8、工具超时 10s、重复工具调用检测、错误信息自然语言化回喂 LLM
- 记忆与上下文压缩（自研双策略，拉开与普通 RAG demo 的差距）：
  - **micro_compact**：紧凑滚动摘要——旧对话压成简短摘要（保留主线/结论），控制超长对话 token 消耗
  - **snip_compact**：关键信息裁剪——保留"事实区"（数字/参数/指令/实体），裁剪寒暄与冗余，适合多轮追问场景
  - 按场景选策略（工具结果密集用 snip_compact 保事实；长对话用 micro_compact 控长度），并量化压缩前后 token 数与忠实度变化（纳入评测）
- 多 Agent 升级点：LangGraph 图结构天然支持新增节点（如质检 Agent、报表 Agent）——面试可讲"单 Agent → 多 Agent 演进"

### 4.3 共享存储

- PostgreSQL + pgvector：文档、分块（含向量与全文索引）、会话、消息、工具调用日志、评测集与结果
- Redis：会话短期记忆、限流计数、语义缓存、任务状态

---

## 5. 核心链路（一回合问答）

1. 用户提问 → Java 对话网关（限流、鉴权、会话）→ 建立 SSE 连接
2. Java 转发 Python Agent 模块 → 查询改写 → Router 决策
3. 工具调用 search_kb（HTTP 回 Java 混合检索：向量 Top30 + tsvector → RRF Top10 → rerank Top5）
4. 生成阶段带引用流式返回（token 流经 Python → Java → 前端）
5. 忠实度自检 → 通过则结束，失败则改写重检索一次
6. Java 落库（消息 + 来源 sources + 反馈按钮），更新统计与可观测 span

---

## 6. 数据模型（PostgreSQL）

```sql
document(id BIGSERIAL PK, title TEXT, category TEXT, uploader BIGINT,
         status TEXT /*processing/ready/failed*/, version INT, created_at TIMESTAMP)

document_chunk(id BIGSERIAL PK, doc_id BIGINT REFERENCES document(id),
               chunk_index INT, content TEXT, token_count INT,
               title_path TEXT, embedding vector(1024),
               search_text tsvector)          -- BGE-M3 稠密向量
CREATE INDEX ON document_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_fts_idx ON document_chunk USING GIN (search_text);

conversation(id BIGSERIAL PK, user_id BIGINT, title TEXT, created_at TIMESTAMP)

message(id BIGSERIAL PK, conversation_id BIGINT, role TEXT /*user/assistant*/,
        content TEXT, sources JSONB, feedback SMALLINT /*0/1/-1*/, created_at TIMESTAMP)

-- Agent 工具调用审计
tool_call_log(id BIGSERIAL PK, conversation_id BIGINT, tool_name TEXT,
              input JSONB, output_summary TEXT, latency_ms INT, ok BOOLEAN, created_at TIMESTAMP)

-- 评测
eval_case(id BIGSERIAL PK, question TEXT, relevant_doc_ids JSONB, category TEXT)
eval_result(id BIGSERIAL PK, case_id BIGINT, strategy JSONB /*分块/检索参数*/, 
            recall_10 FLOAT, mrr FLOAT, faithfulness FLOAT, run_at TIMESTAMP)
```

---

## 7. API 设计

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/documents | 上传文档（multipart），异步解析分块入库 |
| GET | /api/documents?status= | 文档列表 |
| DELETE | /api/documents/{id} | 删除文档（连带分块） |
| POST | /api/documents/{id}/reprocess | 重新解析（版本更新） |
| POST | /api/chat | 对话，SSE 流式：事件 answer(增量)/source/done/error |
| GET | /api/chat/history?conversationId= | 历史消息 |
| POST | /api/feedback | 回答点赞/点踩（积累评测素材） |
| GET | /api/stats | 知识库统计（文档数/分块数/检索延迟/缓存命中率） |
| POST | /api/eval/run | 跑评测集 |
| GET | /api/eval/report | 最近评测报告 |
| POST | /api/agent/tools/search | Agent 工具：混合检索（query, topK, kbId） |
| POST | /api/agent/tools/get-doc-detail | Agent 工具：文档详情 |
| POST | /api/agent/tools/get-stats | Agent 工具：知识库统计 |
| GET | /api/agent/health | Python 模块健康检查 |

---

## 8. 错误处理与可靠性

- **Agent 防护**：最大步数 8、工具超时 10s、重复工具调用检测、错误自然语言化
- **LLM 输出约束**：Function Calling JSON Schema 强约束；解析失败重试 1 次，仍失败降级规则回答
- **检索无结果**：rerank 分数低于阈值 → 明确拒答/反问澄清，不硬答
- **限流与熔断**：用户级令牌桶（Redis）；DeepSeek API 超时/5xx 全局熔断降级
- **语义缓存**：embedding 相似度 >0.95 命中直接返回缓存回答（省 Token、降延迟）
- **流式可靠性**：事件带 sequence；前端断线重连续传；各环节（改写/检索/生成）独立超时
- **可观测**：每轮 span 记录各阶段耗时与 Token 数；工具调用全量审计日志

---

## 9. 评测体系（拉开差距的关键）

1. **评测集**：100-150 条「问题 → 相关文档/片段ID」标注数据（人工出题 + LLM 辅助生成 + 用户点踩回收，人工抽检过滤）
2. **检索指标**：Recall@10、MRR（Java 实现，离线可跑）
3. **生成指标**：忠实度 faithfulness（LLM-as-judge，DeepSeek 打分）+ 人工抽样
4. **对照实验**：分块策略（固定 vs 标题感知 vs 语义）、TopK、分数阈值、缓存命中率——每轮实验记录 eval_result，简历写结论
5. **目标量化句模板**：`构建 120 条标注评测集，混合检索+重排使 Recall@10 从 0.62 提升至 0.87，平均检索延迟 180ms，忠实度 92%`（按实测填写）

---

## 10. 排期与里程碑（主线 4 周 + 弹性 2 周，每天 3-4 小时）

| 阶段 | 内容 | 里程碑 |
|---|---|---|
| 第 1 周 | 环境（Docker：PG+pgvector、Redis）+ Java 骨架 + 文档管线（上传/解析/分块/入库） | 文档能进库 |
| 第 2 周 | Java 混合检索（tsvector+向量+RRF）+ 基础生成 + SSE 对话 + 会话存储 | **MVP 完成，投第一波** |
| 第 3 周 | Python Agent 模块（LangGraph 五节点）+ Java↔Python 通信 + 全链路流式 | Agent 故事成立 |
| 第 4 周 | 记忆（自研双压缩策略 micro/snip_compact）+ 评测（120 条评测集 + 指标 + 对照实验）+ 工程化（限流/缓存/可观测） | **带指标投正式批** |
| 5-6 周（弹性） | MCP Server 模块 / 权限（JWT+文档过滤）/ 多 Agent 升级（质检 Agent）/ 部署 demo 链接 | 简历加分项 |

> 投递节奏：第 2 周末投提前批/第一波；第 4 周末更新简历指标投正式批。项目写"持续迭代中"即可，不等待全部完成。

---

## 11. 简历与面试准备

### 11.1 简历项目描述（双版本）

**投后端（大模型方向）版**：
```
NetDoc：网络设备技术文档智能问答系统（Agentic RAG）｜ 2026.08 - 至今
- 技术栈：Spring Boot 3 / LangChain4j / PostgreSQL+pgvector / Redis / FastAPI / LangGraph / DeepSeek / BGE-M3
- 核心内容：
  · 高并发对话网关：SSE 流式透传、用户级令牌桶限流、语义缓存（命中率 xx%）、重试与熔断降级
  · 混合检索链路：tsvector 全文 + pgvector 向量 + RRF 融合 + rerank，检索延迟 xx ms
  · 文档管线：标题感知分块、异步处理状态机、版本管理，支持 PDF/Word/Markdown
  · AI 集成：LangGraph 编排（查询改写/检索决策/工具调用/忠实度自检）、工具调用审计、Token 成本治理
```

**投 Agent 应用开发版**：
```
NetDoc：网络设备技术文档智能问答系统（Agentic RAG）｜ 2026.08 - 至今
- 技术栈：FastAPI / LangGraph / Spring Boot / pgvector / DeepSeek / BGE-M3 / SSE / Redis
- 核心内容：
  · LangGraph 五节点编排：查询改写 → 检索决策 Router → 工具调用 → 生成（带引用）→ 忠实度自检
  · 工具系统：search_kb/get_doc_detail/get_stats，全量调用审计，超时/重复调用/死循环防护
  · RAG 全链路：标题感知分块、混合检索（稀疏+稠密+RRF）、rerank、反幻觉三件套
  · 记忆系统：Redis 短期 + 自研 micro_compact/snip_compact 双压缩策略（token 消耗降低 xx%，纳入评测），多轮指代消解
  · 评测：120 条标注评测集，Recall@10 0.62→0.87，忠实度 92%（LLM-as-judge）
  · 预留多 Agent 升级：图结构可扩展质检/报表 Agent；可选 MCP 协议暴露检索服务
```

### 11.2 面试准备双线

**Java 工程线（约 60% 权重）**：JUC（AQS/ReentrantLock/线程池参数）、MySQL（索引/B+Tree/深分页/事务隔离）、Redis（分布式锁 setnx 与问题/缓存三兄弟）、Spring（IOC/AOP/事务）

**Agent/RAG 线（约 40% 权重）**：RAG 管线全流程、Function Calling/MCP 原理、LangGraph vs LangChain vs Spring AI 选型、记忆与上下文压缩、幻觉治理、评测指标（Recall@K/MRR/忠实度）、高并发下 LLM 服务设计（缓存/限流/连接池）

**高频追问应答要点**（面经验证）：
| 追问 | 应答要点 |
|---|---|
| 为什么 RAG 不用微调？ | 知识更新分钟级、成本低、可溯源；LoRA 微调可作二期扩展（答概念级即可） |
| 为什么 Java 调 Python Agent？ | Java 管执行（检索/网关/审计/工程化），Python 管思考（LangGraph 编排生态成熟）；职责边界清晰，各用强项 |
| **为什么用 Java 不用纯 Python？** | 三层理由：① 求职定位——投 Java 序列岗必须证明 Java 工程能力；② 职责分工——执行层（高并发 SSE/限流/缓存/检索/审计/评测）的服务化生态 Java 最成熟（Spring/Sentinel/Redisson），思考层（LangGraph）Python 最成熟；③ 价值主张——"AI 应用的生产级落地"，纯 Python 版易被归入"调 API 的 demo"无区分度。收尾加"如果我只投纯 Python Agent 岗就全用 Python"——诚实权衡显深度 |
| 为什么选 pgvector 不选 Milvus？ | 当前规模单机够用、零额外组件；千万级迁 Milvus，讲 HNSW 原理 |
| 流式链路怎么保证不丢？ | SSE 事件序列号 + 断线重连续传 + 各环节超时兜底 |
| Agent 死循环怎么防？ | 最大步数、工具超时、重复调用检测、错误自然语言化 |
| 工具调用安全怎么保证？ | 权限校验、全量审计、敏感操作 Human-in-the-Loop（二期） |
| 上下文太长怎么办？ | 自研双策略：micro_compact 滚动摘要控长度，snip_compact 保留事实区（数字/参数/指令）；配合滑动窗口；量化 token 节约与忠实度损失 |

### 11.3 加分动作

- GitHub 仓库：规范 commit（feat/fix/docs）、中文 README + Mermaid 架构图
- 技术博客 ×3：①混合检索与分块实践 ②LangGraph Agentic RAG 编排 ③RAG 评测体系搭建
- 部署上线（云服务器 Docker Compose 一键起），简历附 demo 链接
- 3 分钟演示脚本：提问 → 流式回答 → 点击引用 → 多轮追问 → 点踩 → 看评测报告
- 知识库素材用「网络设备技术文档（OpenWrt/路由器手册）+ 个人部署踩坑笔记」：面试带出硬件与网络实践（差异化彩蛋，素材一手唯一）

---

## 12. 风险控制

| 风险 | 对策 |
|---|---|
| Python/LangGraph 学习成本 | 第 3 周先跑通最小图（改写→检索→生成），再逐步加节点 |
| 流式链路复杂度 | 降级预案：生成阶段 Java 直连 LLM，Python 只做前处理 |
| 时间失控 | 严格按排期砍减配项（先砍权限/MCP/多 Agent），MVP 优先，投递不等人 |
| API 成本 | DeepSeek + 硅基流动 BGE-M3 免费额度，开发期成本个位数 |
| 中文 PDF 解析乱码 | 素材优先 Markdown/Word；PDF 用 PDFBox 抽文本，复杂 PDF 先转 Word |
| SSE 流式中文断字 | 按 UTF-8 边界缓冲输出，不按字节切 |
| 硬件背景被质疑"非科班" | 简历显眼处写硬件背景 + 软件补课路径；面试话术：网络/系统级思维 + IoT 领域知识（设备手册素材正是证明） |

---

## 13. 二期范围（按时间裁剪，不属于 MVP 承诺）

- MCP Server：把 Java 检索能力封装为标准 MCP Server（Java SDK），任意 MCP 客户端可接入
- 权限系统：Spring Security + JWT + RBAC，多租户/部门隔离知识库（文档按租户+部门可见范围过滤，检索元数据过滤）
- 多 Agent 升级：质检 Agent / 报表 Agent / 数据分析 Agent
- 在线搜索工具（web_search，需搜索 API，弥补知识库覆盖不足）
- 微调实验（可选）：LoRA 概念级对比实验记录，不承诺训练
