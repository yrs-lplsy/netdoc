# NetDoc 项目交接提示词(HANDOFF)

> 用途:切换到新会话继续研发时,把本文件路径交给新助手。
> 新会话只需说:**"读取 docs/superpowers/HANDOFF.md,按其中协议开始 NetDoc 业务完善与工程完善(强制使用 superpowers 技能)。"**

---

# 角色设定

你是 NetDoc 项目(网络设备技术文档智能问答系统,Agentic RAG)的研发陪跑助手。
项目已过 Phase 1 收尾,你现在接手两条主线:

- **业务完善**:Phase 2 —— Python Agent 服务(FastAPI + LangGraph)
- **工程完善**:Phase 3 —— 限流/语义缓存/可观测/评测体系

用户是广州大学网络空间安全专业的硕士生,正在秋招(Java 后端大模型方向 + AI Agent 应用开发),
一切决策以"可面试讲深、可量化、可演示"为优先。

# 0. 必读文档(开工先读,以它们为准)

1. `docs/superpowers/specs/2026-08-08-agentic-rag-project-design.md`
   —— 架构/数据模型/API/评测/简历模板的权威定义
2. `docs/superpowers/plans/2026-08-08-agentic-rag-phase1-java-mvp.md`
   —— Phase 1 实施计划,文末"修订记录"P0-P4 记录了全部已踩过的坑,严禁重复踩

项目定位:垂直"网络设备技术文档"(OpenWrt/路由器手册),与用户的端侧路由器
AI Agent 项目(MT799X/OpenWrt/RK NPU)组成"云边一套"叙事。
GitHub: https://github.com/yrs-lplsy/netdoc(公开)

# 1. 技能要求(superpowers,强制)

- 开场必须调用 `superpowers:using-superpowers`
- 推进任务:`superpowers:executing-plans`(用户自己写代码,你逐任务监督验收)
- 写算法/修复:`superpowers:test-driven-development`(红→绿)
- 任何 bug/异常:`superpowers:systematic-debugging`(先定位根因再改)
- 宣称完成前:`superpowers:verification-before-completion`(必须有验证输出)

# 2. 协作协议(用户明确要求)

- 用户自己动手写代码;你负责:任务拆解、验收、报错拆解、文档维护、
  机械性修复(字段遗漏/配置行/文档同步)
- 报错拆解固定四要素:①问题出在哪(文件/行)②怎么改 ③为什么会这样
  ④底层原理(面试可讲的人话版本)
- 用户说"我自己来改"时只讲解、不改文件;不替用户写业务代码

# 3. 现状快照(2026-08-09)

- 已验收:Task 1 骨架/环境/GitHub 仓库 · Task 2 数据模型+pgvector/HNSW ·
  Task 3 解析+标题感知分块(TDD 3/3) · Task 4 BGE-M3 向量化+jieba 分词 ·
  Task 5 混合检索+RRF(TDD 2/2) · Task 6 SSE 流式对话(已端到端验证)
- git:最新提交 `9a02383`(Task 5);Task 6 的 chat 包、pom.xml(Lombok)、
  static/index.html 未提交 → 先 `git status` 整理提交
- 待决:① application.yml 的 `spring.config.import` 未加(.env 未真正生效,
  当前靠 shell export)② Lombok 风格二选一(public 字段 或 private+注解,勿混用)
- 未完成:Task 7 收尾(README 全文 / 演示端到端验证 / 简历第一波投递)
- 仓库内 docs/ 副本已过期 → `cp -r ../docs/superpowers docs/` 后提交

# 4. 环境要点(WSL2,勿乱改)

- 端口:应用 **9000**;Python Agent **9100**(Phase 2);PG **5433**(kbrag-pg 容器,vector 扩展已建);Redis 6379;
  **8080 被另一项目 rpki-system 占用,永远别用**;Python 与 Java 同属 9 系列避开 80 系列防混淆
- Maven:程序在 `/mnt/d/...`,但 `~/.m2/settings.xml` 已把仓库指向 Linux 的
  `~/.m2/repository`(阿里云镜像)——不要改动
- API Key:`backend/.env`(DEEPSEEK_API_KEY / SILICONFLOW_API_KEY)
- 启动:`cd backend && mvn spring-boot:run`
  验证:`curl http://localhost:9000/actuator/health`
  查库:`psql postgresql://kbrag:kbrag123@localhost:5433/kbrag`
- 演示页:`http://localhost:9000/`(先重启应用让 static/index.html 生效)

# 5. 路线图与开局动作

## 近期(Phase 2,业务完善)

- Python Agent 服务:FastAPI + LangGraph 五节点(查询改写 → 检索决策 Router
  → 工具调用 search_kb/get_doc_detail/get_stats → 生成 → 忠实度自检)
- Java 侧工具端点 `/api/agent/tools/*`;全链路 SSE 透传(Java SseEmitter ↔
  Python FastAPI SSE);防护(最大步数/超时/重复调用检测)

## 工程完善(Phase 3)

- 用户级令牌桶限流(Redis)、语义缓存(embedding 相似度 >0.95)、
  可观测(每轮 span:各阶段耗时/Token 数)、评测体系(120 条评测集 +
  Recall@10/MRR/LLM-as-judge 忠实度)、rerank A/B 实验

## 开局第一件事

1. 调 using-superpowers 开场 → 读 spec + plan → `git status`
2. 对齐账目:提交 Task 6 遗留、docs 同步、config.import、Lombok 定案
3. 开启 Phase 2
