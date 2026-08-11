# 代码审计报告(2026-08-11)

> 审计 Agent 按 docs/superpowers/CODE_AUDIT_AGENT.md 协议执行:基线采集 → 三域子代理审查(安全/性能/并发)→ 联网调研 → 分级输出。
> 审计对象:当前 main 分支 HEAD(6307132),Plan A 已完成、Plan B 执行中(RBAC 登录已提交,多库列已加,**数据权限/KG/缓存一致性未落地**)。

## 审计范围与基线

- **代码规模**:Java ~1,900 行(backend/src/main/java,13 个包)、Python ~430 行(app/ 11 文件)、schema.sql 全量 DDL
- **依赖**:Spring Boot 3.3.5 / jjwt 0.12.6 / langchain4j-open-ai / jieba / pdfbox / poi / langgraph 1.2.10 / fastapi / sse-starlette
- **配置**:JWT HS256 24h;限流 capacity=10 refill=2/s;缓存阈值 0.95 recentMax=200;检索 topK 30/30/60/5
- **已知问题(debug 档案)**:Plan A 修订记录 P5-1~P5-10(embedding mock、pytest pythonpath、幂等 key 同源、_seen 会话级缓存、ddl-auto=none 等)已排除,未重复报告
- **未完成对照(设计文档 §7)**:KG 模块(/api/kg/*、/extract)、role_kb_access 表/切面、/api/chat/history、/api/feedback、/api/eval/*、/api/export/*、/reprocess、语义缓存一致性(Task 6)均未落地

## 摘要

**总体结论:修复后上线(当前不可演示 Agent 问答链路)**。

- 4 个 Critical:Agent 工具检索链路**三重断裂**(kbId 空 / 401 无凭证 / ANY(?) 绑定错误)→ 当前 /api/chat 走 Agent 路径必然"查无资料";授权体系零落地(任意登录用户可越权访问工具端点与任意文档)。
- 关键根因:**Plan B Task 2/3/6 未完成 + 已完成代码存在回归**(限流维度、double-done 重复落库、缓存 N+1)。

### Top 5 优先项

| # | 项 | 级别 | 修复后收益 |
|---|---|---|---|
| 1 | 修复 Agent 检索链路三断点:kbId 透传、工具端点服务凭证、ANY(?) 绑定 | Critical | 问答链路恢复可用(当前完全断) |
| 2 | 落地权限:role_kb_access + @KbAccess 切面 + @PreAuthorize(工具端点 AGENT_SERVICE、/api/stats STATS_VIEW) | Critical | 消除越权/IDOR,满足设计 §5 |
| 3 | 语义缓存一致性改造(Plan B Task 6):kbId 命名空间 + 版本戳 + 主动失效 + TTL;顺带修 double-done 重复落库与 verified=false 缓存投毒 | Critical→Important | 消除跨库数据串流、Redis 内存增长、重复消息/span |
| 4 | 并发准入:有界队列/信号量 + 限流改 userId + XFF 信任收敛 + login/retrieve/upload 补限流 | Important | 防止积压死亡与限流绕过,50 并发可支撑 |
| 5 | 性能:缓存 lookup 改 pipeline(200 RTT→1)、JWT 过滤器去 DB 查询、HNSW 按 kb 分区 | Important | 首 token 延迟 -300ms+、每请求 -3 条 SQL |

## 发现明细(按域)

### 安全域

| 级别 | 域 | 位置 | 问题 | 业界方案(来源) | 建议修法 | 预估收益 |
|---|---|---|---|---|---|---|
| Critical | 授权 | SecurityConfig.java:27;ToolController.java:35-95;全部控制器 | **RBAC 第二层零实现**:0 个 @PreAuthorize / @KbAccess;role_kb_access 表与切面不存在;任意登录用户可:调 /api/agent/tools/*(设计仅 AGENT_SERVICE)、get-doc-detail 按任意 docId 读全文(IDOR)、带任意 kbId 检索/问答/上传/删除 | 企业级权限标准:功能权限(方法级)+ 数据权限(资源级)两层(设计 §5.1,来源:本项目设计文档) | 实现 role_kb_access 表+实体+切面;工具端点加 hasRole("AGENT_SERVICE");/api/stats 加 STATS_VIEW | 消除系统性越权,面试核心叙事成立 |
| Critical | 认证 | java_client.py:31;SecurityConfig.java:27-29 | 工具端点要求认证,但 Python JavaClient **不带任何凭证** → 每次工具调用 401 → Agent 无检索;AGENT_SERVICE 账号(agent-secret-123)已建但从未使用 | 服务间调用用独立服务凭证/共享密钥(设计 §3.2 "带服务凭证") | Python 侧配 AGENT_SERVICE 长 token,JavaClient 带 Authorization 头;或内网 IP 白名单 | Agent 链路恢复 |
| Important | 认证 | AuthController.java:22-32;ChatController.java:41-44 | **登录无限流**(BCrypt 暴力破解无防护);唯一限流在 /api/chat 且盲信 `X-Forwarded-For` 首段 → 伪造头即绕过 | XFF 只在可信代理后采信并覆写(Spring 生产配置惯例) | login 挂 IP+用户名维度限流;XFF 收敛为 `server.forward-headers-strategy`;限流 key 改用 userId | 防爆破、防限流绕过 |
| Important | 密钥 | application.yml:45;DataInitializer.java:30 | JWT secret 硬编码默认值**提交仓库**(`netdoc-demo-jwt-secret-...`);默认账号 admin/admin123、agent-secret-123 仅注释"生产必改" | 密钥只进环境变量/secret 管理,不留默认值(12-factor) | secret 移 .env(已 ignore)+ 启动校验拒绝默认值;DataInitializer 检测默认口令强制改 | 防 token 伪造 |
| Important | 越权 | AgentChatService.java:81-84,169-184 | **conversationId IDOR**:任意已登录用户可传他人 conversationId 加载其历史喂 LLM;save() 不写 userId/kbId → 会话无属主 | 资源属主校验(设计 §7 "会话属主") | save 绑定 userId/kbId;历史加载校验属主 | 防会话劫持 |
| Important | 输入 | 各 Controller:无 @Valid;RetrievalController.java:15;DocumentController upload | topK 负数→500、message 无长度上限、上传无显式大小限制(默认 1MB,无并发限制)、文件仅按扩展名判类型 | 输入校验层(spring-boot-starter-validation 已在依赖中) | @Valid + 边界钳制;multipart 显式 max-file-size;魔数/白名单校验 | 防参数攻击与内存 DoS |
| Minor | 信息泄漏 | AgentChatService.java:177;DocumentController.java:29 | error 事件/错误响应透传 err.getMessage()(含 URL、内部类名) | 错误详情走日志,对外泛化(Spring 惯例) | 泛化文案 + 错误码 | 防信息收集 |
| Minor | 部署 | docker-compose.yml | PG(5433)/Redis 6379 0.0.0.0 暴露、弱口令 kbrag123、Redis 无密码 | 容器只绑本机回环/内网 | 127.0.0.1 绑定 + 强口令 | 防局域网渗透 |

### 性能域

| 级别 | 域 | 位置 | 问题 | 业界方案(来源) | 建议修法 | 预估收益 |
|---|---|---|---|---|---|---|
| Critical | 检索正确性 | HybridRetriever.java:62 | `jdbc.query(sql, mapper, fused.toArray(Long[]::new))`:Long[] 被 varargs **展开为 N 个绑定参数**,SQL 只有 1 个 `ANY (?)` 占位符 → 必然 PSQLException(500);N=1 时绑定标量给 ANY 同样报错。**直连 /api/retrieve 全部失败** | JdbcTemplate varargs 陷阱:数组需 `(Object)` 强转或走 NamedParameterJdbcTemplate(Spring JDBC 惯例) | `namedJdbc.query(sql, Map.of("ids", fused), ...)`;补端到端检索测试 | 检索链路恢复 |
| Important | 缓存 | ChatCacheService.java:47-58 | **N+1 Redis 往返**:lookup 每轮 1 LRANGE + 最多 200 次 HGETALL(串行),且拉全量 hash(含 answer)只为算余弦;未命中也要 201 次 RTT(远端≈400ms 固定税) | 批量读取用 pipeline/MGET(Redis 官方批量模式) | executePipelined 批量 HGETALL;或单 hash(score 只存 embedding)+ 命中后 1 次 HGET | 首 token 延迟 -300ms+ |
| Important | 缓存 | ChatCacheService.java:73-83 | 缓存 hash **无 TTL 永不删除**(LTRIM 只裁 list)→ Redis 内存无界增长(每条约 10-20KB,万轮 ≈ 100-200MB) | TTL + 淘汰一致性(设计 §9 "TTL 兜底") | put 时 expire 24h;evict 时同步 DEL | 防内存泄漏 |
| Important | 鉴权链路 | JwtAuthenticationFilter.java:34-40;User.java:19;Role.java:16 | 每个请求 `users.findById` + **EAGER roles + EAGER permissions 两级展开 ≈ 2-3 条 SQL/请求**,permissions 实际未用,还加载 passwordHash | 无状态 JWT 的访问令牌应免 DB(角色已在 claims) | roles 写进 JWT claims(已生成),过滤器不再查库;roles 改 LAZY | 每请求 -2~3 条 SQL |
| Important | 数据库 | DocumentController.java:36-39 | `list()` findAll 全表加载 + 内存过滤,无分页 | 派生查询 + Pageable(Spring Data 惯例) | findByStatusAndKbId + Pageable | 大数据量线性开销消除 |
| Important | 数据库 | schema.sql;AgentChatService.java:82 | message 表无 (conversation_id, id) 索引 → 历史加载 seq scan + 整表拉取再截 10 条 | 索引 + Top-N 查询(Spring Data 惯例) | 加索引;findTop10ByConversationIdOrderByIdDesc | 长会话提速 |
| Important | 向量索引 | schema.sql:112;HybridRetriever.java:47-50 | HNSW 不含 kb_id:过滤在近似扫描**之后**,多库下 top-30 可能全是别库 → 召回空/质量下降,或放弃索引 | pgvector 官方:多租户建议分区/部分索引;或过滤列建普通索引走精确 NN(pgvector README) | 按 kb 建独立 HNSW 或 list 分区;ef_search 调优 | 多库检索质量恢复 |
| Important | LLM 成本 | AgentChatService.java:59,147;HybridRetriever.java:45 | 同一问题每轮 **embed 3 次**(lookup + search + put),缓存未命中时白付 2 次 | 向量复用(单次计算多消费者) | lookup 复用 qv 给 put;retriever 支持预计算向量 | 省 2/3 embedding 成本 |
| Minor | LLM | EmbeddingService.java:38-49 | 注释称"512 token 截断"但**无实现**;tokenCount=字符数;重试 3 次×langchain4j 默认重试=9 次无退避 | 与注释对齐或改注释;指数退避仅重试 5xx | 显式 maxRetries(1)+退避;截断或改注释 | 防 400/抖动放大 |
| Minor | Python | llm.py:22-26;graph.py:36;java_client.py:30 | OpenAIEmbeddings 无超时且为**死代码**(Java 侧做 embedding);graph 每轮重编译;httpx 每次新建连接 | 移除死代码/补超时;模块级 graph 缓存;Client 复用 | 见左 | 小 | 

### 并发域

| 级别 | 域 | 位置 | 问题 | 业界方案(来源) | 建议修法 | 预估收益 |
|---|---|---|---|---|---|---|
| Critical | 链路功能 | sse.py:50-53;AgentChatService.java:137-149 | **double-done 契约**(test_sse.py 断言 done 两次:正常 done + finally 兜底 done)→ Java 对每个 done 都 save():每轮成功问答**重复落库 2 条 user + 2 条 assistant、2 条 rag_span、2 份缓存** | 事件幂等消费(消费者对重复事件去重) | Java 端 doneHandled 标志去重;或 Python 只在异常时发兜底 done | 消除数据翻倍 |
| Critical | 链路功能 | AgentChatService.java:131-135 | done 里 verified 读出即丢:忠实度 FAIL(give_up)的回答**照常落库 + 写缓存** → 未验证(可能幻觉)答案可被缓存重放 | 未通过质检的产物不进生产数据(设计 §9 忠实度自检) | verified=false 时不写缓存、落库标记 | 防缓存投毒 |
| Critical | 线程模型 | ChatController.java:26,37 | 8 固定线程 + **无界队列** + 无拒绝策略;SseEmitter(180s 墙钟)入队前已创建 → 排队长于 180s 的请求**未被处理就超时死亡**,任务白跑(embedding/Redis/DB 全做) | Spring 文档:async 阻塞写走 AsyncTaskExecutor,"默认执行器不适合生产负载";SSE 必须心跳检测断连(Spring Framework 文档) | Semaphore(目标并发)准入,tryAcquire 失败 503;或虚拟线程(spring.threads.virtual.enabled);心跳 15-30s | 消除排队死亡/无效工作 |
| Important | 限流 | ChatController.java:32,41-44;RateLimiter.java:55-59 | 限流 key=IP(已有 JWT 却注释"无登录态");XFF 盲信可伪造绕过;fail-open 无本地兜底 → Redis 故障+突发=裸奔;429 无 Retry-After | 认证后按主体限流;fail-open 需告警+容量闸门兜底 | userId 为 key;XFF 收敛;加 Semaphore 兜底;429 带 Retry-After | 限流真正生效 |
| Important | 幂等 | ToolController.java:98-106,108-120 | 幂等 check-then-act 非原子:并发同 key 双请求都执行,第二个 INSERT 撞 uk_tool_call_idem → 500(无 @RestControllerAdvice) | 唯一约束兜底需配合冲突捕获(设计 §9 "DB 兜底层") | INSERT ... ON CONFLICT DO NOTHING 先占位 + 冲突回查返回上次结果;或 Lua 占位 | 幂等真兜底 |
| Important | 异步 | KbragApplication.java:8;DocumentService.java:54 | @Async 无自定义 Executor:用 Boot 默认 applicationTaskExecutor(core=8, max=Integer.MAX_VALUE, 队列无界)——并发上传时线程/队列无上限 | 有界线程池 + 拒绝策略 + 线程名前缀(Boot 任务执行文档) | 定义 ThreadPoolTaskExecutor(core/max/queue 有界)+ @Async("docExecutor") | 防线程爆炸 |
| Important | Python | tools_node.py:41;java_client.py:30 | **async 节点内同步 httpx 阻塞**(超时 10s):单 uvicorn worker 事件循环被堵 → 所有并发 SSE 流 token 转发停顿 | 异步 I/O 不进事件循环阻塞(标准 asyncio 实践) | asyncio.to_thread / AsyncClient;uvicorn --workers N | 并发流不互相拖累 |
| Minor | 事务 | AgentChatService.java:169-184 | save() @Transactional 自调用(订阅 lambda 内)→ 代理绕过,事务不生效,user/assistant 非原子 | 自调用不走代理(Spring 常识) | 拆独立 bean 或 TransactionTemplate | 落库原子性 |
| Minor | 可观测 | AgentChatService.java:144 | saveSpan 在 Reactor onNext 线程做阻塞 JPA | 阻塞写不进事件循环 | 异步化/独立 executor | 事件循环干净 |
| Minor | 背压 | AgentChatService.java:93-105 | WebClient bodyToFlux 默认无界订阅,无 limitRate | 响应式背压:request(n)(Reactor 惯例) | limitRate + onBackpressureBuffer 上限 | 防内存堆积 |

## 上线风险清单

**并发容量模型(当前 vs 目标)**
- 当前:8 线程仅覆盖"流前阻塞段"(embed 0.2-2s + 缓存 200 RTT + 历史查询),真实吞吐 ≈ 5 req/s;限流 2 req/s/IP × 180s → 单 IP 可合法维持 360 个并发连接;20 IP 即逼近 Tomcat maxConnections → 连接耗尽前表现为挂起(无拒绝)
- 目标(50 并发问答):Semaphore(50) 准入 + userId 限流 + 有界队列/CallerRuns + 虚拟线程或 50 线程;Python 多 worker + to_thread
- 排队死亡放大器:排队长于 180s 的请求处理前已超时,任务仍执行(白花外部 API 费用)

**外部依赖风险**
- DeepSeek/SiliconFlow:无熔断(设计弹性周),chat 有 60s 超时、embedding 9 次重试无退避;故障时链路变慢但不会挂死(有降级路径)
- Redis:缓存与限流均 fail-open(服务可用优先),但叠加无界队列=完全无防护,需容量闸门兜底
- Python:单 worker 同步阻塞点(httpx),任一工具调用 10s 超时期间全服务停顿

## 待验证项

1. `ANY (?)` varargs 绑定错误:逻辑与 spring-jdbc 源码核对成立,需端到端检索测试(修后立刻补)实证
2. Boot 默认 @Async 执行器细节(applicationTaskExecutor 默认值):基于 Boot 3 行为断言,文档抓取失败,待复核
3. HNSW 按 kb 过滤的实际计划行为:需 EXPLAIN 实测(小库上可能无感,数据量上来才显著)
4. double-done 影响面:消息/span/缓存三处翻倍,已在测试契约确认,需修后回归验证
5. 180s 墙钟 vs 空闲超时:verify 重试轮(MAX_STEPS=16)是否可合法超 180s,需实测一轮最坏耗时

## 优化路线图(对应 plan Task)

| 优先级 | 项 | 落点 |
|---|---|---|
| P0(本轮/立即) | 修复检索链路三断点(kbId 透传 C1 / 工具凭证 C2 / ANY 绑定 C4) | Plan B Task 2 + Task 1 + Plan A 修订 |
| P0(立即) | double-done 去重、verified=false 不写缓存 | Plan A Task 7 修订 |
| P1(8/23 里程碑前) | role_kb_access + 切面 + 方法级权限;conversation 属主;登录限流;XFF 收敛 | Plan B Task 3 + Task 1 |
| P1(8/23 里程碑前) | 语义缓存一致性(kbId 命名空间/版本戳/失效/TTL)+ 缓存 pipeline | Plan B Task 6 |
| P2(8/30 前) | 并发准入(Semaphore/有界队列)+ 限流改 userId + @Async 有界执行器 | Plan C 弹性 / Plan B Task 1 |
| P2(8/30 前) | JWT 过滤器去 DB、message 索引、list 分页、HNSW 分区 | Plan B Task 5 / Plan A |
| P3(9/6 部署前) | secret 外置强制、上传大小限制、compose 加固(健康检查/重启/资源)、429 Retry-After、错误信息泛化 | Plan C Task 5 |

## 通过项(无需改)

- SQL 注入:全部占位符参数化 ✓
- backend/.env 已 gitignore,密钥未入库 ✓
- 无效 token 静默 401 统一处理 ✓
- RRF/令牌桶纯算法有单测 ✓
- jjwt 0.12.6 API、langgraph 1.2.10 适配(P-B 档案)已落地 ✓
- 幂等键 Python 侧 _seen + Java 唯一索引双层结构在(竞态修复后) ✓
