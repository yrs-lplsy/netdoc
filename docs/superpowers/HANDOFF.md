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

1. `docs/superpowers/specs/2026-08-10-agentic-rag-enterprise-design.md`
   —— **企业级 RAG 中台权威设计**(2026-08-10 定稿:认证/多库/KG/三路融合/评测/排期)。
   2026-08-08 版 spec 为 Phase 1 历史依据,冲突处以新版为准
2. `docs/superpowers/plans/2026-08-08-agentic-rag-phase1-java-mvp.md`
   —— Phase 1 实施计划,文末"修订记录"P0-P4 记录了全部已踩过的坑,严禁重复踩
3. `docs/superpowers/plans/2026-08-09-agentic-rag-phase2-python-agent.md`(Plan A)
   —— Python Agent 基础 + Java 工程化(限流/缓存/可观测),第 3 周里程碑
4. `docs/superpowers/plans/2026-08-10-agentic-rag-plan-b-auth-kg.md`(Plan B)
   —— RBAC 认证 + 多知识库 + 知识图谱 + 缓存一致性,第 4 周里程碑
5. `docs/superpowers/plans/2026-08-10-agentic-rag-plan-c-eval-release.md`(Plan C)
   —— 评测 + 对照实验 + 知识包导出 + 部署,第 5-6 周里程碑

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

# 3. 现状快照(2026-08-10)

- 已完成:Phase 1 全部(文档管线/混合检索/SSE);Plan A 已开工——Task 1
  (Python 骨架)进行中:requirements.txt + venv(Py3.13)已就绪,.gitignore 已补;
  config.py/main.py/test_health.py 待写待验收
- 设计定稿:企业级 RAG 中台(spec 2026-08-10);Plan A/B/C 三份计划已提交,
  按周推进(8/16 Agent 演示 → 8/23 认证+KG 演示 → 8/30 指标 → 9/6 上线投递)
- 已对齐:docs 同步、README(NetDoc 叙事)、Lombok 统一 @Data、端口 9100、
  工具幂等键(Plan A Task 2 已含)、豆包 4 条优化(三路 RRF/缓存一致性/幂等/K8s 路径)落 spec
- 待办:Task 1 收尾 → Plan A Task 2-9 → Plan B → Plan C;简历正式批(9/6 前)

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

# 5. 路线图与执行体系(2026-08-10 更新)

按 spec 第 11 节排期,三份 plan 顺序推进(每份独立可演示):

- **Plan A(第 3 周,8/16 里程碑)**:Python Agent 五节点 + Java 工具端点(幂等)
  + SSE 全链路 + 令牌桶限流 + 语义缓存 + 可观测
- **Plan B(第 4 周,8/23 里程碑)**:RBAC0 认证(JWT+两层权限+切面)+ 多知识库
  + 知识图谱(构建/三路融合/可视化)+ 缓存一致性
- **Plan C(第 5-6 周,8/30 与 9/6 里程碑)**:评测(120 条/Recall@10/MRR/忠实度
  + 图谱对照实验)+ 可观测 token 成本 + 知识包导出(端侧)+ 全栈部署 + 简历

协作模式:用户自己写代码,助手教学/验收/答疑;报错四要素拆解;机械性修复
(配置行/文档同步)助手直接做。

## 开局第一件事

1. 调 using-superpowers 开场 → 读 spec(2026-08-10)+ 当前 plan → `git status`
2. 对齐账目(按 HEAD 快照核对遗留)
3. 从当前 plan 的 in_progress Task 继续
