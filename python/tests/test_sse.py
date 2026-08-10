import json

import pytest

from app.sse import AgentChatRequest, event_stream


@pytest.mark.asyncio
async def test_event_stream_sequence():
    async def fake_run_agent(state):
        yield ("phase", "rewrite")
        yield ("answer", "你好")
        yield ("sources", [{"title": "第一章", "snippet": "..."}])
        yield ("done", {"verified": True, "error": None})

    import app.sse
    app.sse.run_agent = fake_run_agent  # monkeypatch

    events = [e async for e in event_stream(AgentChatRequest(message="你好"))]
    names = [e["event"] for e in events]
    # 实现契约:phase(透传可观测)+ answer + source + done + finally 兜底 done
    assert names == ["phase", "answer", "source", "done", "done"]
    # seq 递增
    seqs = [json.loads(e["data"])["seq"] for e in events]
    assert seqs == [1, 2, 3, 4, 5]
