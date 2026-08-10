import json

from app.state import AgentState

SYSTEM = (
    "你是检索决策器。判断用户问题是否需要检索知识库:"
    "涉及设备配置/参数/操作步骤/故障排查等技术内容 → needs_retrieval=true;"
    "纯寒暄(你好/谢谢)或与设备技术无关 → needs_retrieval=false 直接回答。"
    '只输出 JSON:{"needs_retrieval": true|false, "reason": "一句话理由"}'
)

# 实现 router 节点(JSON 约束,解析失败降级检索)
async def router_node(state: AgentState, chat=None) -> AgentState:
    from app.llm import chat_model

    chat = chat or chat_model
    resp = await chat.ainvoke([
        {"role": "system", "content": SYSTEM},
        {"role": "user", "content": f"问题:{state.get('rewritten') or state['question']}"},
    ])
    text = getattr(resp, "content", "") or ""
    try:
        decision = json.loads(text.strip().strip("`"))
        state["needs_retrieval"] = bool(decision.get("needs_retrieval", True))
    except Exception:
        state["needs_retrieval"] = True  # 解析失败降级:宁可多检索,不可漏检索
    return state


def route_decision(state: AgentState) -> str:
    return "retrieve" if state["needs_retrieval"] else "direct"
