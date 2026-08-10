import json
from typing import AsyncIterator

from pydantic import BaseModel

from app.graph import run_agent


class AgentChatRequest(BaseModel):
    message: str
    conversation_id: int | None = None
    history: list[dict] = []


def build_input(req: AgentChatRequest) -> dict:
    return {
        "question": req.message,
        "history": req.history[-10:],
        "conversation_id": req.conversation_id,   # 工具幂等键上下文
        "needs_retrieval": True,
        "contexts": [],
        "answer": "",
        "sources": [],
        "attempts": 0,
        "verified": False,
        "error": None,
        "tool_calls": [],
    }


async def event_stream(req: AgentChatRequest) -> AsyncIterator[dict]:
    """LangGraph 事件 → SSE 事件流:{event, seq, data}。"""
    seq = 0

    def emit(event: str, data):
        nonlocal seq
        seq += 1
        return {"event": event, "data": json.dumps({"seq": seq, "data": data}, ensure_ascii=False)}

    try:
        async for kind, payload in run_agent(build_input(req)):
            if kind == "answer":
                yield emit("answer", payload)
            elif kind == "phase":
                yield emit("phase", payload)   # 阶段耗时透传(Java 可观测用;前端忽略)
            elif kind == "sources":
                yield emit("source", payload)
            elif kind == "done":
                yield emit("done", {"verified": payload["verified"], "error": payload["error"]})
    except Exception as e:
        yield emit("error", f"Agent 服务异常:{e}")
    finally:
        yield emit("done", None)
