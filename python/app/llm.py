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
