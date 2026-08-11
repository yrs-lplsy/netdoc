-- vector 扩展由 docker-entrypoint-initdb.d/init.sql 在容器首启时创建
-- DDL 全量托管:Hibernate ddl-auto=none;生成列由 PG 维护,避免 update 迁移与生成列冲突

CREATE TABLE IF NOT EXISTS document (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(255),
    category      VARCHAR(255),
    uploader      BIGINT,
    status        VARCHAR(255),
    version       INT,
    error_message TEXT,
    created_at    TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS document_chunk (
    id             BIGSERIAL PRIMARY KEY,
    doc_id         BIGINT,
    chunk_index    INT,
    content        TEXT,
    token_count    INT,
    heading_path   VARCHAR(255),
    embedding      vector(1024),
    segmented_text TEXT,
    search_text    tsvector GENERATED ALWAYS AS (to_tsvector('simple', segmented_text)) STORED
);

CREATE TABLE IF NOT EXISTS conversation (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT,
    title      VARCHAR(255),
    created_at TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS message (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT,
    role            VARCHAR(255),
    content         TEXT,
    sources_json    TEXT,
    feedback        SMALLINT,
    created_at      TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS tool_call_log (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT,
    tool_name       VARCHAR(255),
    idempotent_key  VARCHAR(255),   -- 幂等键 conversationId:agentStepId(spec §9 双层防线)
    input_json      TEXT,
    output_summary  TEXT,
    latency_ms      INT,
    ok              BOOLEAN,
    created_at      TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS rag_span (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT,
    question        TEXT,
    gateway_ms      INT,
    rewrite_ms      INT,
    router_ms       INT,
    tools_ms        INT,
    generate_ms     INT,
    verify_ms       INT,
    cache_hit       BOOLEAN,
    created_at      TIMESTAMP(6)
);

-- schema.sql 追加(幂等)
CREATE TABLE IF NOT EXISTS usr (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE,
    password_hash VARCHAR(255),
    enabled BOOLEAN,
    created_at TIMESTAMP(6)
);
CREATE TABLE IF NOT EXISTS role (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) UNIQUE
);
CREATE TABLE IF NOT EXISTS user_role (
    user_id BIGINT, role_id BIGINT
);
CREATE TABLE IF NOT EXISTS permission (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(255) UNIQUE
);
CREATE TABLE IF NOT EXISTS role_permission (
    role_id BIGINT, permission_id BIGINT
);

-- 新建 knowledge_base 表(库本身,对应第 2 步的 Java 实体):
CREATE TABLE IF NOT EXISTS knowledge_base (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    description TEXT,
    owner_id    BIGINT,
    created_at  TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS role_kb_access (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT,
    kb_id BIGINT,
    access VARCHAR(255),
    CONSTRAINT uk_role_kb UNIQUE (role_id, kb_id)
);

-- Task 4:知识图谱(KgEntity/KgRelation 实体注解 @Table 索引与 DDL 对应)
CREATE TABLE IF NOT EXISTS kg_entity (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT,
    name VARCHAR(255),
    type VARCHAR(255),
    normalized_name VARCHAR(255),
    doc_id BIGINT,
    embedding vector(1024),          -- 实体向量,Task 4 先不填,留升级位
    confidence DOUBLE PRECISION,
    created_at TIMESTAMP(6)
);
CREATE INDEX IF NOT EXISTS idx_kg_entity_name ON kg_entity (kb_id, name);

CREATE TABLE IF NOT EXISTS kg_relation (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT,
    source_id BIGINT,
    target_id BIGINT,
    relation VARCHAR(255),
    confidence DOUBLE PRECISION,
    created_at TIMESTAMP(6)
);
CREATE INDEX IF NOT EXISTS idx_kg_rel_src ON kg_relation (kb_id, source_id);

-- 三张旧表加 kb_id 列(幂等写法,兼容已存在的旧库):
ALTER TABLE document ADD COLUMN IF NOT EXISTS kb_id BIGINT;
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS kb_id BIGINT;
ALTER TABLE conversation ADD COLUMN IF NOT EXISTS kb_id BIGINT;

--按库查询的索引(检索按 kb_id 过滤时用):
CREATE INDEX IF NOT EXISTS idx_doc_kb   ON document (kb_id);
CREATE INDEX IF NOT EXISTS idx_chunk_kb ON document_chunk (kb_id);

CREATE INDEX IF NOT EXISTS idx_chunk_fts  ON document_chunk USING gin (search_text);
CREATE INDEX IF NOT EXISTS idx_chunk_hnsw ON document_chunk USING hnsw (embedding vector_cosine_ops);

-- 幂等键唯一约束:新库由表定义覆盖;已有库(update 时期建的旧表)补列+索引,幂等可重入
ALTER TABLE tool_call_log ADD COLUMN IF NOT EXISTS idempotent_key VARCHAR(255);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tool_call_idem ON tool_call_log (idempotent_key);