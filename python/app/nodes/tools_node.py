from app.state import AgentState

TOOLS_SYSTEM = (
    "你是网络设备技术文档问答助手。回答技术问题前必须调用 search_kb 检索知识库;"
    "需要溯源时调用 get_doc_detail;用户问知识库规模时调用 get_stats。"
)


# 两阶段:LLM function calling 决定 → 执行,带重复检测与错误自然语言化
async def tools_node(state: AgentState, chat=None, client=None) -> AgentState:
    """阶段1:LLM function calling 决定工具调用;阶段2:循环执行(去重/超时/错误自然语言化)。"""
    from app.java_client import JavaClient
    from app.llm import chat_model
    from app.tools import TOOL_SCHEMAS, execute_tool

    chat = chat or chat_model
    client = client or JavaClient(conversation_id=state.get("conversation_id"))
    contexts = list(state.get("contexts") or [])

    # 阶段 1:LLM 携带工具 schema 决定调用哪些工具(DeepSeek Function Calling)
    resp = await chat.ainvoke(
        [
            {"role": "system", "content": TOOLS_SYSTEM},
            {"role": "user", "content": f"问题:{state.get('rewritten') or state['question']}"},
        ],
        tools=TOOL_SCHEMAS,
    )
    raw_calls = getattr(resp, "tool_calls", None) or []
    requested = [{"name": c.get("name"), "args": c.get("args") or {}} for c in raw_calls]

    # 阶段 2:执行;重复调用由 JavaClient._seen 拦截,错误自然语言化写入执行结果
    for call in requested:
        name, args = call["name"], call["args"]
        text = execute_tool(name, args, client)
        if name == "search_kb" and not text.startswith("[工具"):
            contexts.extend(_parse_hits(text))  # 把检索结果文本解析回结构化

    state["contexts"] = contexts
    state["tool_calls"] = list(state.get("tool_calls") or []) + requested  # 累积记录(供审计/去重)
    return state


def _parse_hits(text: str) -> list:
    """从 execute_tool 的自然语言输出反解出结构化片段(单测覆盖)。"""
    import re

    hits = []
    for block in re.split(r"\[\d+\] ", text):
        if not block.strip():
            continue
        lines = block.splitlines()
        meta = lines[0] if lines else ""
        content = "\n".join(lines[1:]) if len(lines) > 1 else ""
        chunk_id = int(re.search(r"片段ID=(\d+)", meta).group(1))
        doc_id = int(re.search(r"文档ID=(\d+)", meta).group(1))
        title = re.search(r"标题=(.*)", meta).group(1)
        hits.append({"chunkId": chunk_id, "docId": doc_id, "headingPath": title, "content": content})
    return hits
