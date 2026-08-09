# 企业级 RAG 中台 + 知识图谱 —— 设计文档(2026-08-10 修订版)

> 日期:2026-08-10
> 状态:已获用户确认的设计定稿
> 取代关系:本文档为权威版本,取代《2026-08-08-agentic-rag-project-design.md》(NetDoc 垂直问答版)。2026-08-08 文档中与本文档冲突的部分以本文档为准;Phase 1 已实现内容(文档管线/混合检索/SSE)继续复用,仅按本文档做多知识库化改造。
> 架构参考:本设计对标企业级 RAG Agent 落地架构(六层分层 + Java/Python 职责边界),按单人可交付规模裁剪,裁剪对照见 §3.4。

---

## 1. 背景与目标

### 1.1 定位(2026-08-10 决策)

**企业级 RAG 中台**:一套功能完整、可服务化的 RAG 落地系统——文档治理 → 混合检索 → Agent 问答 → 知识图谱增强 → 评测 → 认证权限,通过 REST API 对外提供服务;端侧路由器 AI Agent 项目(MT799X/OpenWrt/RK NPU,概念阶段)是下游消费者之一,形成"云侧中台 + 边侧消费"的云边一体叙事。

### 1.2 目标

- 交付一个**可深挖、有量化指标、双序列简历背书**(Java 工程线 + Agent/RAG 线)的企业级 RAG 中台
- 知识图谱以"召回增强"为切入点落地(不一步到位做图推理),保留升级路径
- 时间约束:秋招进行中——2026-08-16 Agent 全链路可演示,08-23 认证+KG 可演示,08-30 评测指标出炉,09-06 部署上线 + 简历正式批投递

### 1.3 关键决策记录(2026-08-10)

| 决策点 | 结论 | 理由 |
|---|---|---|
| 系统形态 | 企业级 RAG 中台(非垂直窄域) | 平台型叙事,端侧/多下游可接入 |
| 知识图谱深度 | 轻量图谱增强(PG 三元组,阶段 2 召回增强) | 成熟度高/性价比最优;Neo4j 与 GraphRAG 推理为升级路径 |
| KG 实现边界 | 构建(抽取/归一/融合)在 Python;存储/检索/权限在 Java | 构建是 AI 逻辑(Python 生态强),执行是系统底盘(Java 必须闭环) |
| 认证权限 | 完整 JWT + RBAC0(功能权限 + 数据权限两层) | 企业级标配,Java 面试核心考点 |
| 知识素材 | 双库混合:通用企业文档主库 + OpenWrt 副库 | 验证多库隔离/权限模型,端侧数据同源 |
| 排期 | 6 周全量执行,不裁剪(用户确认加班) | 质量优先 |
| 架构来源 | 对标企业级六层架构,按单人规模裁剪 | 详见 §3.4 裁剪对照表 |

---

## 2. 岗位定位与投递策略

- 主投:后端开发工程师(大模型/AI 应用方向)+ AI 应用开发 / Agent 应用开发
- 简历核心叙事升级:**"对标企业级架构的 RAG 中台落地"**——Java 工程化能力(Spring Security/RBAC/限流/缓存/可观测)+ Agent/RAG 能力(LangGraph 五节点/知识图谱增强/评测体系)+ 架构视野(六层分层/云边一体/裁剪决策)

---

## 3. 总体架构

### 3.1 六层架构叙事(企业级蓝图)

| 层 | 职责 | 主导 | 本系统落点 |
|---|---|---|---|
| 接入层 | 前端页面、API 网关、三方系统集成 | Java | 浏览器演示页 + REST/SSE 入口 + JWT 认证 |
| 业务服务层 | 权限会话、Agent 业务编排、工具执行、管控台 | Java | Spring Boot 单体(模块化包结构),认证/知识库/文档/会话/工具 |
| AI 能力层 | LLM 推理、RAG 检索、图谱推理、Agent 智能决策 | Python | FastAPI + LangGraph 五节点;检索结果由 Java 提供 |
| 知识加工层 | 文档解析、向量化、知识抽取、图谱构建 | Python 为主,Java 调度 | Python:解析增强/抽取;Java:解析/分块(Phase 1 已有) |
| 存储层 | 关系库、向量库、图数据、缓存 | 共建,Java 主管控 | PG+pgvector(向量+三元组表)+ Redis;不引入 Neo4j/Milvus |
| 基础设施层 | 监控、日志、部署、安全、可观测 | Java | Docker Compose、/api/stats、rag_span 可观测 |

### 3.2 落地架构(双服务)

```
浏览器 / 端侧下游
   │ REST + SSE(JWT 认证 + RBAC 过滤)
   ▼
┌─────────────────────────────────────────────────┐
│ Java 中台(Spring Boot 3)——业务底盘/执行层           │
│  · 认证权限:JWT + RBAC0(功能权限 + 知识库数据权限)     │
│  · 文档治理:上传/解析/分块/入库(多知识库维度)           │
│  · 混合检索:tsvector + pgvector + RRF(Phase 1 已有) │
│  · 对话网关:SSE 透传 + 令牌桶限流 + 语义缓存 + 可观测    │
│  · 图谱执行:三元组存储/实体匹配/邻居扩展/可视化 API      │
│  · 评测:Recall@K/MRR/忠实度(Phase 3)               │
│  · 服务化:知识库管理 API + 知识包导出(端侧消费)        │
└───────────────┬─────────────────────────────────┘
                │ HTTP(Agent 工具调用 / 图谱抽取调度,带服务凭证)
                ▼
┌─────────────────────────────────────────────────┐
│ Python Agent(FastAPI + LangGraph)——AI 能力层       │
│  五节点:查询改写→检索决策Router→工具调用→生成→忠实度自检 │
│  图谱构建:实体/关系抽取、归一化、三元组输出(入库时批量)   │
│  LLM:DeepSeek(Function Calling/JSON 约束)          │
└─────────────────────────────────────────────────┘
共享存储:PostgreSQL+pgvector(KG 三元组表)| Redis 7
外部:DeepSeek API | 硅基流动 BGE-M3 | (可选 bge-reranker,Phase 3 A/B)
```

### 3.3 职责边界(豆包架构采纳 + 修正)

**Java(业务底盘,系统唯一入口)**:
- 认证鉴权(JWT + RBAC0 两层权限)、限流熔断、审计日志
- 知识库/文档/会话管理、多轮会话持久化、权限校验(数据访问不绕过权限层)
- 业务工具实际执行(search_kb/get_doc_detail/get_stats + 工具调用全量落库 tool_call_log)
- 混合检索执行、图谱存储与查询入口、评测执行
- 缓存/可观测/统计指标、知识包导出

**Python(AI 能力,智能大脑)**:
- 文档解析增强、向量化(复用 Phase 1 的 Java 侧 embedding 亦可,分工按实现方便)
- Agent 编排(LangGraph 五节点)、LLM 调用、工具调用决策
- **知识图谱构建**:实体抽取、关系抽取、实体归一化、三元组输出(入库时批量,调 Java API 落库)
- 答案生成、忠实度自检、拒答逻辑

**关键修正(与豆包原稿的差异)**:
1. **纯算法留在 Java**(RRF/余弦/令牌桶等)——求职 Java 后端的差异化面试素材,Python 只承担"模型相关"(LLM/抽取/推理决策)
2. **图查询入口在 Java**(权限闭环),Python 只产查询意图/抽取结果,不直连存储
3. 单体 Spring Boot(不 Spring Cloud)——"单体起步、按域拆分"叙事

### 3.4 企业级蓝图 → 单人落地裁剪对照

| 企业级蓝图 | 本系统落地 | 面试叙事 |
|---|---|---|
| Spring Cloud 微服务 | 单体 Spring Boot + 模块化包 | "单体起步,压力到了按域拆服务" |
| OAuth2 + 多租户 | JWT + RBAC0 + 知识库级可见性 | "对标企业权限模型,单系统可交付" |
| Milvus/Chroma 向量库 | PG + pgvector(零新组件) | "当前规模单机够用,迁移路径已设计" |
| Neo4j/Nebula 图数据库 | PG 三元组表(实体+关系) | "轻量落地,规模化/多跳再迁图库" |
| vLLM 本地推理 | DeepSeek + BGE-M3 API | "LLM 服务化接入,可替换" |
| K8s 部署 | Docker Compose 单机 | Java/Python 均无状态、配置外置(环境变量注入)、存储依赖独立 PG/Redis 服务,可直接转 Deployment+Service,水平扩容仅需调整副本数;Compose 编排可一键转换 K8s 资源清单 |
| 数据脱敏/内容审核 | 文档级元数据 + 权限控制 | "企业级增强项,已规划未实现" |

---

## 4. 知识图谱设计(轻量图谱增强)

### 4.1 三阶段路径(面试讲行业共识)

1. **阶段 1 纯向量 RAG**(已有):文档切块 → 向量化 → 相似度检索 → LLM 生成
2. **阶段 2 图谱召回增强**(本期):向量检索 + 图谱实体召回双路并行,结果共同进 Prompt——实体精准性/术语查询/简单关系类问题显著提升,大幅减少幻觉
3. **阶段 3 图谱推理增强**(二期):Text2Cypher 在图库执行精确查询(多跳/统计/路径)——依赖图谱质量,本期不落地

### 4.2 图谱构建链路(入库时批量,Python 构建 Java 落库)

```
Java DocumentService.processAsync(入库完成)
   → 调 Python POST /extract(kbId, docId, chunks)
      Python:LLM 实体抽取(JSON 约束)→ 归一化(别名合并)→ 关系抽取 → 置信度过滤
   → 返回三元组 JSON
   → Java 写 kg_entity / kg_relation(权限/审计闭环)
```

- 抽取时机:入库时批量(一次成本摊多次收益),非查询时
- 抽取质量:LLM 抽取 + 实体词典归一(OpenWrt/openwrt/旁路由 → 同一实体)+ 低置信关系丢弃
- 抽取器接口化:Python 侧实现可替换(后续换 UIE/领域模型)

### 4.3 图谱检索链路(查询时零 LLM,毫秒级)——三路召回 + RRF 融合

```
用户问题
   → Java 实体链接:jieba 分词 + 实体匹配(词典即 kg_entity 表的 name/normalized_name 列,
     按 kb_id 过滤,无需独立维护词典)→ 命中实体
   → 一跳邻居扩展:SQL 取命中实体的关系与邻居实体 → 关联 doc_id 集合(图谱路)
   → 三路召回统一 RRF 融合:
       ① 关键词路(tsvector 检索, TopK=20)
       ② 向量路(pgvector 检索, TopK=20)
       ③ 图谱路(实体邻居关联文档, TopK=20)
       RRF(k=60,与 Phase 1 一致)融合 → Top10 → 截断 Top5
   → 图谱上下文段:命中实体 + 关系 + 邻居实体拼接为结构化上下文
     (如 "实体[OpenWrt] -[USES]-> 实体[luci]"),随文本片段一起进 Prompt
```

**融合规则(面试必问,代码留可配置参数)**:
- 三路平等进 RRF(无人工权重,鲁棒、面试好讲);RRF k=60;各路 TopK=20;融合后取 Top5 进 Prompt
- 全部参数在 application.yml:`app.retrieval.dense-top-k / sparse-top-k / kg-top-k / rrf-k / final-top-k / kg-context-enabled`
- 图谱路只提供"关联文档召回 + 图谱上下文段",不参与打分排序(排序仍由 RRF 决定)
- 查询路径零 LLM 依赖,不增加对话延迟(面试量化点)
- 检索结果不理想时可选升级:LLM 实体识别(配置开关,默认关)

### 4.4 图谱数据模型

```sql
kg_entity(id BIGSERIAL PK, kb_id BIGINT, name TEXT, type TEXT /*DEVICE/SOFTWARE/CMD/CONFIG/...*/,
          normalized_name TEXT, doc_id BIGINT, embedding vector(1024), created_at)
-- 索引:name(精确/分词)、embedding HNSW(实体语义检索)、kb_id
kg_relation(id BIGSERIAL PK, kb_id BIGINT, source_id BIGINT REFERENCES kg_entity,
            target_id BIGINT REFERENCES kg_entity, relation TEXT /*USES/REQUIRES/CONFIGURES/...*/,
            source_text TEXT, created_at)
-- 索引:source_id、target_id(邻居扩展走索引)
```

### 4.5 图谱可视化(演示记忆点)

- `GET /api/kg/graph?kbId=` → {nodes:[{id,name,type}], edges:[{source,target,relation}]}
- 前端力导向图渲染(演示页新增图谱 Tab)
- 实体搜索 `GET /api/kg/entities?kbId=&q=`

### 4.6 升级路径(可插拔)

- 图谱增强器接口(Java 侧抽象),阶段 3 可替换为 Text2Cypher + 图库执行
- 数据量/多跳需求出现时迁 Neo4j(实体/关系导出脚本),向量实体检索保留 pgvector
- GraphRAG 社区摘要:Python 侧可独立加社区检测(需要时),不阻塞主线

---

## 5. 认证与权限(RBAC0 两层)

### 5.1 模型

```sql
user(id, username UNIQUE, password_hash TEXT /*BCrypt*/, enabled BOOL)
role(id, name UNIQUE /*ADMIN/USER/AGENT_SERVICE*/)
user_role(user_id, role_id)
permission(id, code UNIQUE /*KB_CREATE/KB_WRITE/KB_READ/CHAT/KG_VIEW/STATS_VIEW*/)
role_permission(role_id, permission_id)
role_kb_access(role_id, kb_id, access /*READ/WRITE/ADMIN*/)
```

- **功能权限**(permission):能做什么操作(建库/上传/问答/看图谱)
- **数据权限**(role_kb_access):能访问哪些知识库、什么级别(READ 只读检索/WRITE 可传文档/ADMIN 可管理)
- ADMIN 角色:全部 permission + 全部库 ADMIN;USER:按角色授予
- 端侧接入:AGENT_SERVICE 角色 + 长期 token(工具端点/知识包导出)

### 5.2 链路

```
POST /api/auth/login(username, password) → JWT(HS256, 24h 过期, 含 userId)
→ 请求带 Authorization: Bearer <jwt>
→ JwtAuthenticationFilter 校验签名/过期 → SecurityContext 放 userId/roles
→ AOP 切面 KbAccessCheck:所有带 kbId 的接口统一校验 role_kb_access
   (校验不过 403;数据访问不绕过权限层——面试点)
→ 业务执行
```

### 5.3 面试要点

- JWT 无状态 vs Session(扩容/登出黑名单的取舍)
- BCrypt 不可逆 + salt;token 过期与刷新(预留)
- 功能权限 + 数据权限两层拆法(企业级权限模型标准)
- 切面统一校验(一处实现,全部接口生效,审计留痕)

---

## 6. 数据模型(全量)

```sql
-- 认证与权限(新增,§5.1)
user / role / user_role / permission / role_permission / role_kb_access

-- 知识库(新增,贯穿全系统)
knowledge_base(id BIGSERIAL PK, name TEXT, description TEXT, owner_id BIGINT, created_at)

-- 文档与分块(已有,加 kb 维度)
document(id, kb_id REFERENCES knowledge_base, title, category, uploader, status, version,
         error_message TEXT, created_at)
document_chunk(id, kb_id, doc_id, chunk_index, content TEXT, token_count,
               heading_path TEXT, embedding vector(1024), segmented_text TEXT,
               search_text tsvector GENERATED ALWAYS AS (to_tsvector('simple', segmented_text)) STORED)
-- 索引:idx_chunk_fts(GIN search_text)、idx_chunk_hnsw(HNSW embedding)、kb_id

-- 会话与消息(已有,加 kb 维度)
conversation(id, kb_id, user_id, title, created_at)
message(id, conversation_id, role, content TEXT, sources_json TEXT, feedback SMALLINT, created_at)

-- 知识图谱(新增,§4.4)
kg_entity / kg_relation

-- 工具调用审计(已有,加幂等键)
tool_call_log(id, conversation_id, tool_name, idempotent_key TEXT UNIQUE /*conversation_id+agent_step_id*/,
              input_json JSONB, output_summary TEXT, latency_ms INT, ok BOOL, created_at)

-- 可观测(已有)
rag_span(id, conversation_id, question TEXT, gateway_ms, rewrite_ms, router_ms,
         tools_ms, generate_ms, verify_ms, cache_hit BOOL, created_at)

-- 评测(已有规划)
eval_case(id, question TEXT, relevant_doc_ids JSONB, category TEXT)
eval_result(id, case_id, strategy JSONB, recall_10 FLOAT, mrr FLOAT, faithfulness FLOAT, run_at)
```

---

## 7. API 设计

| 方法 | 路径 | 说明 | 权限 |
|---|---|---|---|
| POST | /api/auth/login | 登录发 JWT | 公开 |
| GET | /api/auth/me | 当前用户/角色/可访问库 | 登录 |
| POST | /api/kbs | 建知识库 | KB_CREATE |
| GET | /api/kbs | 我的可访问库列表(含 access 级别) | 登录 |
| DELETE | /api/kbs/{id} | 删库(连带文档/分块/图谱) | 库 ADMIN |
| POST | /api/documents?kbId= | 上传文档,异步解析/分块/向量化/图谱抽取 | 库 WRITE |
| GET | /api/documents?kbId=&status= | 文档列表 | 库 READ |
| DELETE | /api/documents/{id} | 删除文档(连带分块/实体) | 库 WRITE |
| POST | /api/documents/{id}/reprocess | 重新解析(版本更新) | 库 WRITE |
| POST | /api/retrieve | 混合检索(带 kbId,权限过滤) | 库 READ |
| POST | /api/chat | SSE 流式问答(带 kbId) | 库 READ + CHAT |
| GET | /api/chat/history?conversationId= | 历史消息 | 会话属主 |
| POST | /api/feedback | 回答点赞/点踩 | 登录 |
| GET | /api/kg/graph?kbId= | 图谱可视化(节点+边) | 库 READ + KG_VIEW |
| GET | /api/kg/entities?kbId=&q= | 实体搜索 | 库 READ + KG_VIEW |
| POST | /api/kg/rebuild?kbId= | 触发图谱重建 | 库 WRITE |
| POST | /api/agent/tools/search | Agent 工具:混合检索 | AGENT_SERVICE |
| POST | /api/agent/tools/get-doc-detail | Agent 工具:文档详情 | AGENT_SERVICE |
| POST | /api/agent/tools/get-stats | Agent 工具:知识库统计 | AGENT_SERVICE |
| GET | /api/agent/health | 健康检查(含 Python) | 公开 |
| GET | /api/stats | 知识库统计(文档/分块/延迟/命中率) | STATS_VIEW |
| POST | /api/eval/run | 跑评测集 | ADMIN |
| GET | /api/eval/report | 最近评测报告 | ADMIN |
| POST | /api/export/knowledge-pack?kbId= | 导出知识包(文档+三元组+向量摘要,端侧消费) | AGENT_SERVICE |

---

## 8. 核心链路

### 8.1 在线问答(含图谱增强)

1. 用户提问 → Java 网关(JWT 认证 + 限流 + 语义缓存检查)→ 建立 SSE
2. 语义缓存命中(embedding 相似度 >0.95)→ 直返缓存回答(省 Token)
3. Java 查历史(滑动窗口 10 条)→ 调 Python Agent
4. Python:查询改写 → Router 决策 → 工具调用(Java search_kb,含图谱实体召回扩展)
5. 生成阶段带引用流式返回(token 流 Python → Java → 前端,phase 事件带耗时)
6. 忠实度自检 → 通过结束 / 失败改写重检索一次
7. Java 落库(message + rag_span + 工具审计)

### 8.2 离线知识更新(含图谱构建)

1. Java 定时任务/手动触发 → 文档解析分块向量化(Phase 1 已有)
2. 调 Python /extract 批量抽取实体关系 → 归一化
3. Python 返回三元组 → Java 写 kg_entity/kg_relation(先清旧实体再写新)
4. Java 更新文档状态 READY + 元数据

### 8.3 Agent 工具调用

Python 决策要调工具(调用带 agent_step_id)→ 请求 Java 工具端点(带服务凭证)→ Java 先校验幂等键(已执行直接返回上次结果)→ 执行(权限/审计)→ 返回结果 → Python 继续推理

---

## 9. 错误处理与可靠性

- **Agent 防护**:最大步数 8(recursion_limit)、工具超时 10s、重复工具调用检测、错误自然语言化回喂
- **工具幂等(双层防线,面试点)**:Python 侧会话内重复检测(JavaClient._seen,内存快速层)+ Java 侧持久化幂等键(`conversation_id + agent_step_id` 唯一索引,DB 兜底层)——防网络重试/崩溃恢复导致的重复执行,已执行直接返回上次结果
- **LLM 输出约束**:Function Calling JSON Schema 强约束;解析失败重试 1 次,仍失败降级规则回答
- **检索无结果**:图谱/向量均无命中 → 明确拒答/反问,不硬答
- **限流**:用户级令牌桶(Redis Lua),超限 429
- **语义缓存**:embedding 相似度 >0.95 命中直接返回(省 Token、降延迟)
  - 缓存条目:问题向量 + 回答 + sourcesJson + **kb_id + 库版本戳(kb 内文档 max(updated_at)) + TTL 24h**
  - 一致性:缓存 key 带 kb_id 命名空间(`chat:cache:{kbId}:*`);**文档上传/删除/重建成功后清该 kb 命名空间**(主动失效)+ 版本戳比对(命中后校验,库变了即失效)+ **TTL 兜底**(防漏清)——面试讲"缓存一致性三件套"
- **流式可靠性**:事件带 seq;前端断线可重连;各环节超时兜底
- **降级预案**:Python 不可用 → Java 发 error 事件告知,不静默失败;ChatService(Java 直连 LLM)保留为降级通道
- **可观测**:每轮 rag_span(各阶段耗时/Token 数/缓存命中);工具调用全量审计;待补:DeepSeek 5xx 熔断(Resilience4j,弹性周)

---

## 10. 评测体系(简历量化来源)

1. **评测集**:120 条「问题 → 相关文档/片段ID」标注(人工出题 + LLM 辅助生成 + 用户点踩回收,人工抽检),双库各半
2. **检索指标**:Recall@10、MRR(Java 实现,离线可跑)
3. **生成指标**:忠实度 faithfulness(LLM-as-judge,DeepSeek 打分)+ 人工抽样
4. **图谱增强对照实验**(核心亮点):同一评测集,有/无图谱实体召回 → Recall@10 与忠实度对比——量化"图谱增强的增益"
5. **其他对照**:分块策略、TopK、rerank A/B(bge-reranker)
6. **目标量化句模板**:`构建 120 条标注评测集,混合检索 + 图谱召回增强使 Recall@10 从 0.62 提升至 0.87,平均检索延迟 180ms,忠实度 92%,图谱增强增益 xx%`(按实测填写)

---

## 11. 排期与里程碑(6 周全量,2026-08-10 起)

| 周 | 内容 | 里程碑 |
|---|---|---|
| 第 3 周(8/10-16) | Python Agent 基础:骨架/LLM 客户端/工具层/五节点图(TDD)/SSE 全链路;Java 工具端点 + 令牌桶限流 | 8/16 Agent 全链路可演示 |
| 第 4 周(8/17-23) | RBAC0 认证(JWT + 两层权限)→ 多知识库改造 → KG 构建(抽取/三元组/实体链接/可视化)→ 语义缓存 | 8/23 认证+多库+KG 可演示 |
| 第 5 周(8/24-30) | 评测体系(120 条 + Recall@10/MRR/忠实度 + 图谱对照实验)→ 可观测完善(/api/stats) | 8/30 指标出炉 |
| 第 6 周(8/31-9/6) | 知识包导出(端侧接口)→ 部署 demo(Docker Compose 一键起)→ 简历正式批 + 技术博客 ×3 + 演示录屏 | 9/6 上线投递 |

**弹性(9/7 后,按时间)**:rerank A/B、DeepSeek 熔断、记忆双压缩策略(micro_compact/snip_compact)、Neo4j 迁移实验、GraphRAG 推理增强、多 Agent、MCP Server

---

## 12. 简历与面试准备

### 12.1 简历项目描述(双版本)

**投后端(大模型方向)版**:
```
企业级 RAG 中台(Agentic RAG + 知识图谱)｜ 2026.08 - 至今
- 技术栈:Spring Boot 3 / Spring Security(JWT+RBAC0)/ LangChain4j / PostgreSQL+pgvector / Redis / FastAPI / LangGraph / DeepSeek / BGE-M3
- 核心内容:
  · 企业级权限:JWT + RBAC0 两层权限(功能权限 + 知识库数据权限),切面统一校验
  · 高并发对话网关:SSE 流式透传、用户级令牌桶限流(Redis Lua)、语义缓存(命中率 xx%)
  · 混合检索链路:tsvector + pgvector + RRF + 知识图谱实体召回增强,检索延迟 xx ms
  · 文档管线:标题感知分块、异步状态机、多知识库隔离,支持 PDF/Word/Markdown
  · 可观测:每轮 span(各阶段耗时/Token 数)、工具调用全量审计
  · 评测:120 条标注评测集,Recall@10 0.62→0.87,忠实度 92%(LLM-as-judge),图谱增强增益 xx%
```

**投 Agent 应用开发版**:
```
企业级 RAG 中台(Agentic RAG + 知识图谱)｜ 2026.08 - 至今
- 技术栈:FastAPI / LangGraph / Spring Boot / pgvector / DeepSeek / BGE-M3 / SSE / Redis / 知识图谱
- 核心内容:
  · LangGraph 五节点编排:查询改写 → 检索决策 Router → 工具调用 → 生成(带引用)→ 忠实度自检
  · 知识图谱增强:LLM 实体/关系抽取 → 三元组 → 查询时实体链接 + 一跳邻居召回(召回增强,对照实验量化增益)
  · 工具系统:search_kb/get_doc_detail/get_stats,全量审计、超时/重复调用/死循环防护
  · RAG 全链路:标题感知分块、混合检索(稀疏+稠密+RRF)、反幻觉三件套
  · 记忆:滑动窗口 + 自研双压缩策略(Phase 3)、多轮指代消解
  · 评测:120 条标注评测集,Recall@10/MRR/忠实度(LLM-as-judge)
  · 云边一体:知识包导出 API 服务端侧设备(端侧路由器 AI Agent)
```

### 12.2 高频追问应答要点(新增)

| 追问 | 应答要点 |
|---|---|
| 为什么不用 Neo4j? | 当前规模单机 PG 够用、零新组件;实体/关系查询是毫秒级 SQL;多跳/路径分析出现后迁 Neo4j(导出脚本已设计);面试讲 HNSW 与图索引的区别 |
| 图谱增强到底提升了什么? | 实体精准性(术语/型号查得准)、简单关系问题、减少幻觉;有对照实验数据(Recall@10 增益 xx%) |
| 为什么构建在 Python、存储检索在 Java? | 构建是 AI 逻辑(Python LLM 生态强、迭代快);存储/权限/审计必须在执行层闭环;抽取器接口化可替换 |
| 为什么算法写在 Java? | 我主投 Java 后端,检索/融合算法是工程能力证明;Python 只承担模型相关部分——职责分工清晰 |
| 知识图谱怎么保证质量? | 归一化(别名合并)、置信度过滤、词典辅助;评测集里图谱类问题单独抽检 |
| 企业级和 demo 的区别? | 权限模型两层、全量审计、可观测、限流缓存、降级预案;对标的裁剪决策可逐条讲 |

### 12.3 加分动作

- GitHub 规范 commit + 中文 README + Mermaid 架构图(六层 + 双服务)
- 技术博客 ×3:①企业级 RAG 中台架构与 Java/Python 边界 ②知识图谱召回增强实践与对照实验 ③RAG 评测体系搭建
- 部署 demo 链接(云服务器 Docker Compose)
- 3 分钟演示脚本:登录 → 双库切换 → 问答(流式+引用)→ 图谱可视化 → 评测报告
- 端侧项目作为"云边一体"叙事挂载(概念阶段,讲清楚规划)

---

## 13. 风险控制

| 风险 | 对策 |
|---|---|
| 范围过大(6 周全量) | 弹性裁剪顺序:rerank/熔断/Neo4j 实验 → GraphRAG → 多 Agent;主线(Agent/认证/KG/评测)不动 |
| 图谱抽取质量差 | 归一化词典 + 置信度过滤 + 抽检;实体类型先限 5-6 类 |
| LLM 抽取成本 | 入库时批量、仅抽取一次;控制每 chunk 抽取 token;开发期成本个位数 |
| RBAC 复杂度失控 | 先 RBAC0 两层固定模型,不做动态权限表达式;管理接口最小集(建用户/授角色) |
| 多知识库改造破坏 Phase 1 | 分两步:先加 kb_id 列与默认库,再上权限过滤;回归验证 Phase 1 用例 |
| 时间失控 | 里程碑硬卡:8/16 演示、8/23 演示、8/30 指标;投递不等人 |

---

## 14. 二期范围(不属于本期承诺)

- 端侧真实集成(知识包下发协议落地、端侧离线检索、云边同步)
- 阶段 3 图谱推理增强(Text2Cypher + Neo4j)、GraphRAG 社区摘要
- 多 Agent(质检/报表/数据分析)、MCP Server
- 在线搜索工具(web_search)
- 数据脱敏、内容安全审核、多租户隔离(独立租户表)
- OAuth2 第三方登录、微服务拆分
