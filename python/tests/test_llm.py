from unittest.mock import AsyncMock, patch

import pytest

from app.llm import embed

# 注意:mock 目标必须是模块级对象 app.llm.embeddings_model,而不是模型实例的属性——
# langchain 1.x 的 OpenAIEmbeddings 是 pydantic v2 模型,__delattr__ 拦截实例属性删除,
# patch("...embeddings_model.aembed_documents") 退出时会抛 AttributeError。


@pytest.mark.asyncio
async def test_embed_batches_and_retries():
    fake = AsyncMock()
    fake.aembed_documents = AsyncMock(side_effect=[Exception("boom"), [[0.1, 0.2]]])
    with patch("app.llm.embeddings_model", fake):
        result = await embed(["hello"])
    assert len(result) == 1
    assert fake.aembed_documents.await_count == 2  # 第一次失败,重试成功


@pytest.mark.asyncio
async def test_embed_raises_after_three_failures():
    fake = AsyncMock()
    fake.aembed_documents = AsyncMock(side_effect=Exception("boom"))
    with patch("app.llm.embeddings_model", fake):
        with pytest.raises(Exception):
            await embed(["hello"])
    assert fake.aembed_documents.await_count == 3
