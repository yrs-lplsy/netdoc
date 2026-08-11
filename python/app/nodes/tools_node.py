from app.state import AgentState

TOOLS_SYSTEM = (
    "你是网络设备技术文档问答助手。回答技术问题前必须调用 search_kb 检索知识库;"
    "需要溯源时调用 get_doc_detail;用户问知识库规模时调用 get_stats。"
)


# 两阶段:LLM function calling 决定 → 执行,带重复检测与错误自然语言化
async def tools_node(state: AgentState, chat=None, client=None) -> AgentState:
    """阶段1:LLM function calling 决定工具调用;阶段2:循环执行(去重/超时/错误自然语言化)。"""
    from app.llm import chat_model
    from app.tools import TOOL_SCHEMAS, execute_tool

    chat = chat or chat_model
    # JavaClient 在一次 run_agent 生命周期内复用(存入 state):重试轮 step 持续递增,
    # 幂等键(conversation_id + agentStepId)不冲突——否则重试轮 step 归零撞幂等键(C1)
    if client is None:
        client = state.get("_java_client")
        if client is None:
            from app.java_client import JavaClient

            client = JavaClient(conversation_id=state.get("conversation_id"),
                                kb_id=state.get("kb_id"))
            state["_java_client"] = client
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
            _extract_graph_context(text, state)   # 图谱关系段 → state["graph_context"]
            contexts.extend(_parse_hits(text))    # _parse_hits 只解析 [N] 块,图谱段单独走 state

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
        # 防御:首行不是 meta(幂等摘要/异常文本/非标准块),跳过该块,不崩溃
        m_id = re.search(r"片段ID=(\d+)", meta)
        m_doc = re.search(r"文档ID=(\d+)", meta)
        m_title = re.search(r"标题=(.*)", meta)
        if not (m_id and m_doc and m_title):
            continue
        content = "\n".join(lines[1:]) if len(lines) > 1 else ""
        hits.append({"chunkId": int(m_id.group(1)), "docId": int(m_doc.group(1)),
                     "headingPath": m_title.group(1), "content": content})
    return hits
