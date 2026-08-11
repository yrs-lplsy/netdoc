from fastapi import FastAPI

from sse_starlette.sse import EventSourceResponse
from app.sse import AgentChatRequest, event_stream

app = FastAPI(title="NetDoc Agent Service", version="0.2.0")


@app.get("/health")
def health():
    return {"status": "UP"}

@app.post("/agent/chat")
async def agent_chat(req: AgentChatRequest):
    return EventSourceResponse(event_stream(req), headers={"Cache-Control": "no-cache"})

from app.extract import router as extract_router

app.include_router(extract_router)