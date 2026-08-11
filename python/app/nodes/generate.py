from app.state import AgentState

SYSTEM = (
    "你是网络设备技术文档智能问答助手。只能根据提供的资料片段回答,禁止编造。"
    "回答中用 [1][2] 标注引用编号;资料中没有的内容直接说'资料中未找到相关信息'。"
    "用中文回答。"
)

# 带引用流式;按 needs_retrieval 分支:检索无果拒答 / 闲聊直答
async def generate_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    contexts = state.get("contexts") or []
    # 检索分支但没拿到资料 → 明确拒答/反问,不硬答(spec §8);闲聊分支无 contexts 直接自然回答
    if not contexts and state.get("needs_retrieval", True):
        state["answer"] = "资料库中暂未找到相关信息,请换一种问法或补充文档。"
        state["sources"] = []
        return state

    prompt_parts = []
    if contexts:
        prompt_parts.append("资料:\n")
        for i, c in enumerate(contexts):
            prompt_parts.append(f"[{i + 1}] {c.get('headingPath', '')}: {c.get('content', '')}")
    gc = state.get("graph_context")              # 图谱关系上下文段(实体链接命中时非空)
    if gc:
        prompt_parts.append(f"\n图谱关系:\n{gc}")
    if state.get("needs_retrieval", True):
        prompt_parts.append("\n要求:回答中标注引用编号;资料中没有的内容直接说明;用中文回答。")
    else:
        prompt_parts.append("\n要求:这是闲聊,无需引用资料,自然回答;用中文。")
    prompt_parts.append(f"\n问题:{state.get('rewritten') or state['question']}")

    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": "\n\n".join(prompt_parts)},
    ]

    answer_parts = []
    async for chunk in chat.astream(messages):
        answer_parts.append(chunk.content or "")
    state["answer"] = "".join(answer_parts)
    state["sources"] = [
        {"title": c.get("headingPath", ""), "snippet": (c.get("content") or "")[:120]}
        for c in contexts
    ]
    return state
