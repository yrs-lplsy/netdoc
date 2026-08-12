import re

from app.state import AgentState

SYSTEM = (
    "你是忠实度审查员。判断回答是否严格忠于给定的资料片段:"
    "回答中的事实必须在资料中有依据,禁止编造。"
    "回答中明确声明'资料中未找到相关信息/未提及/未给出'等拒答表述是允许的,不算编造;"
    "只有把资料中没有的内容作为事实陈述时才判 FAIL。"
    '先输出 PASS 或 FAIL,再输出一句理由。'
)

# 实现 verify 节点(PASS/FAIL 判断 + attempts 递增 + FAIL 理由回喂)
async def verify_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    context_text = "\n".join(
        f"[{i + 1}] {c.get('headingPath', '')}: {c.get('content', '')[:800]}"
        for i, c in enumerate(state.get("contexts") or [])
    )
    resp = await chat.ainvoke([
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"资料片段:\n{context_text or '(无)'}\n\n回答:{state.get('answer', '')}"},
    ])
    text = getattr(resp, "content", "") or ""
    state["attempts"] = (state.get("attempts") or 0) + 1  # 每次审查 +1,保证 retry 最多一次
    if re.search(r"\bPASS\b", text.upper()):  # 容忍 "审查结果:PASS" 等前缀
        state["verified"] = True
    else:
        state["verified"] = False
        state["error"] = f"忠实度审查未通过:{text[:200]}"  # FAIL 理由写入 error,回喂 rewrite
    return state


def verify_decision(state: AgentState) -> str:
    if state["verified"]:
        return "pass"
    return "retry" if (state.get("attempts") or 0) < 2 else "give_up"
