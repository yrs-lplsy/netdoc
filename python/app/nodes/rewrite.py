from app.state import AgentState

SYSTEM = (
    "你是网络设备技术文档问答助手的查询改写器。把用户问题改写成适合知识库检索的形式:"
    "消解指代(它/这个/上面 → 具体对象)、口语转检索词、保持技术术语。"
    "只输出改写后的问题本身,不要解释。若问题已清晰,原样返回。"
)


async def rewrite_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    history = state.get("history") or []
    history_text = "\n".join(f"{h['role']}: {h['content']}" for h in history[-6:])
    # 忠实度自检失败重试时,把 FAIL 理由回喂,指导改写检索词(spec §4.2 "改写重检索一次")
    retry_hint = ""
    if state.get("error"):
        retry_hint = f"\n注意:上次回答因忠实度审查未通过,理由:{state['error']}。请调整检索词以获取更充分的资料。"
    messages = [
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"对话历史:\n{history_text or '(无)'}\n\n当前问题:{state['question']}{retry_hint}"},
    ]
    resp = await chat.ainvoke(messages)
    state["rewritten"] = (getattr(resp, "content", "") or "").strip() or state["question"]
    return state
