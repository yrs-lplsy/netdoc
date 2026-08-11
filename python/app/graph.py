import time

from langgraph.graph import END, START, StateGraph

from app.nodes.generate import generate_node
from app.nodes.router import route_decision, router_node
from app.nodes.rewrite import rewrite_node
from app.nodes.tools_node import tools_node
from app.nodes.verify import verify_decision, verify_node
from app.state import AgentState

NODE_NAMES = ("rewrite", "router", "tools", "generate", "verify")


def build_graph():
    g = StateGraph(AgentState)
    g.add_node("rewrite", rewrite_node)
    g.add_node("router", router_node)
    g.add_node("tools", tools_node)
    g.add_node("generate", generate_node)
    g.add_node("verify", verify_node)
    g.add_edge(START, "rewrite")
    g.add_edge("rewrite", "router")
    g.add_conditional_edges("router", route_decision, {"retrieve": "tools", "direct": "generate"})
    g.add_edge("tools", "generate")
    g.add_edge("generate", "verify")
    g.add_conditional_edges("verify", verify_decision, {"pass": END, "retry": "rewrite", "give_up": END})
    return g.compile()


async def run_agent(input_state: dict):
    """流式执行 Agent;单次 astream_events 同时采集 token 与最终状态(勿二次 ainvoke,会重复执行图)。
    recursion_limit=MAX_STEPS 防死循环。产出 (kind, payload):answer/phase/sources/done。"""
    from app.config import MAX_STEPS

    graph = build_graph()
    final_answer, sources, verified, error = "", [], False, None
    start_ns: dict[str, int] = {}
    async for event in graph.astream_events(
        input_state,
        version="v2",
        config={"recursion_limit": MAX_STEPS},
    ):
        kind = event.get("event")
        node = event.get("metadata", {}).get("langgraph_node", "")  # v2 事件标准字段,比 name 更可靠
        if kind == "on_chat_model_stream":
            # chat_model 配了 streaming=True → 所有节点的 ainvoke 也产生流事件;
            # 只转发 generate 的 token,rewrite/router/tools/verify 的输出(改写词/决策 JSON)不外发
            if node != "generate":
                continue
            chunk = event["data"].get("chunk")
            token = chunk.content if chunk else ""
            if token:
                yield ("answer", token)
        elif kind == "on_chain_start" and node in NODE_NAMES:
            # langgraph 1.x 可能对同一节点发多次同名 start(节点包装层),只记首次,否则 elapsed 被覆盖成 0
            start_ns.setdefault(node, time.monotonic_ns())
        elif kind == "on_chain_end" and node in NODE_NAMES:
            output = event["data"].get("output") or {}
            # langgraph 1.x:条件边 path 函数(route_decision/verify_decision)的 END 事件
            # 与节点同名且 output 是 str(路由目标);只有节点本体的 output 才是 state dict
            if not isinstance(output, dict):
                continue
            if node == "generate":
                final_answer = output.get("answer", "")
                sources = output.get("sources") or []
            elif node == "verify":
                verified = bool(output.get("verified"))
                error = output.get("error")
            # 阶段耗时事件(Java 可观测 Task 9 汇总用;前端忽略未知事件)
            yield ("phase", {"node": node, "elapsedMs": (time.monotonic_ns() - start_ns.get(node, 0)) // 1_000_000})
    yield ("sources", sources)
    yield ("done", {"answer": final_answer, "verified": verified, "error": error})
