-- vector 扩展由 docker-entrypoint-initdb.d/init.sql 在容器首启时创建，这里不再重复
-- tsvector 由 segmented_text 自动维护（避免 JPA 管理 tsvector 类型）
ALTER TABLE document_chunk ADD COLUMN IF NOT EXISTS search_text tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', segmented_text)) STORED;

CREATE INDEX IF NOT EXISTS idx_chunk_fts  ON document_chunk USING gin (search_text);
CREATE INDEX IF NOT EXISTS idx_chunk_hnsw ON document_chunk USING hnsw (embedding vector_cosine_ops);
