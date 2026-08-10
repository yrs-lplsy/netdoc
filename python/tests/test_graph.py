import pytest
from types import SimpleNamespace

from app.state import AgentState


class FakeChat:
    """mock LLM:按调用次数返回预置响应,record 调用参数。
    返回 SimpleNamespace(content, tool_calls) 模拟 LangChain AIMessage 形态。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    async def ainvoke(self, messages, **kwargs):
        self.calls.append((messages, kwargs))
        r = self.responses.pop(0)
        if isinstance(r, str):
            return SimpleNamespace(content=r, tool_calls=[])
        return r


@pytest.fixture
def base_state():
    return AgentState(question="OpenWrt 如何设置无线?", history=[], needs_retrieval=True,
                      contexts=[], answer="", sources=[], attempts=0, verified=False,
                      error=None, tool_calls=[])


# ---- Router ----

async def test_router_direct_chat_skips_retrieval(base_state):
    from app.nodes.router import router_node
    fake = FakeChat(['{"needs_retrieval": false}'])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is False


async def test_router_invalid_json_defaults_to_retrieve(base_state):
    from app.nodes.router import router_node
    fake = FakeChat(["不是 JSON"])
    state = await router_node(dict(base_state), fake)
    assert state["needs_retrieval"] is True  # 解析失败降级走检索


# ---- Tools(两阶段:LLM function calling 决定 → 执行)----

async def test_tools_node_decides_and_executes(base_state):
    from app.nodes.tools_node import tools_node
    call = {"name": "search_kb", "args": {"query": "安装", "top_k": 5}}
    fake_chat = FakeChat([SimpleNamespace(content="", tool_calls=[call])])
    fake_client = FakeJavaClient()
    state = dict(base_state)
    out = await tools_node(state, fake_chat, fake_client)
    assert len(out["contexts"]) == 1  # LLM 决定调用 search_kb,结果解析进 contexts
    assert out["tool_calls"] == [call]


async def test_tools_node_duplicate_call_deduped(base_state):
    from app.nodes.tools_node import tools_node
    call = {"name": "search_kb", "args": {"query": "安装", "top_k": 5}}
    fake_client = FakeJavaClient()
    state = dict(base_state)
    out1 = await tools_node(state, FakeChat([SimpleNamespace(content="", tool_calls=[call])]), fake_client)
    # 第二次 LLM 又请求相同调用:JavaClient._seen 拦截,contexts 不再增加
    out2 = await tools_node(out1, FakeChat([SimpleNamespace(content="", tool_calls=[call])]), fake_client)
    assert len(out1["contexts"]) == 1
    assert len(out2["contexts"]) == 1


# ---- Verify(attempts 递增 + PASS/FAIL 判定 + FAIL 理由回喂)----

async def test_verify_fail_marks_retry(base_state):
    from app.nodes.verify import verify_node
    fake = FakeChat(["FAIL 回答中包含了资料没有的信息"])
    state = await verify_node(dict(base_state), fake)
    assert state["verified"] is False
    assert state["attempts"] == 1
    assert "未通过" in state["error"]  # FAIL 理由写入 error,回喂 rewrite


async def test_verify_pass(base_state):
    from app.nodes.verify import verify_node
    fake = FakeChat(["PASS 回答忠实于资料"])
    state = await verify_node(dict(base_state), fake)
    assert state["verified"] is True
    assert state["attempts"] == 1


def test_verify_decision_give_up_after_two_failures():
    from app.nodes.verify import verify_decision
    assert verify_decision({"verified": False, "attempts": 1}) == "retry"
    assert verify_decision({"verified": False, "attempts": 2}) == "give_up"
    assert verify_decision({"verified": True, "attempts": 2}) == "pass"


# ---- Generate(检索无果拒答 / 闲聊直答)----

class FakeStreamChat:
    """模拟流式 LLM:astream 逐 token 产出。"""

    def __init__(self, tokens):
        self.tokens = tokens

    async def astream(self, messages):
        for t in self.tokens:
            yield SimpleNamespace(content=t)


async def test_generate_rejects_when_no_contexts_and_retrieval_needed(base_state):
    from app.nodes.generate import generate_node
    state = dict(base_state)  # needs_retrieval=True
    out = await generate_node(state, FakeStreamChat(["资料"]))
    assert "未找到" in out["answer"]
    assert out["sources"] == []


async def test_generate_answers_direct_chat_without_contexts(base_state):
    from app.nodes.generate import generate_node
    state = dict(base_state)
    state["needs_retrieval"] = False  # Router 判定闲聊,直接自然回答
    out = await generate_node(state, FakeStreamChat(["你好!"]))
    assert out["answer"] == "你好!"


# ---- 图结构 ----

def test_graph_has_five_nodes():
    from app.graph import build_graph
    graph = build_graph()
    node_names = {n for n, _ in graph.get_graph().nodes.items()}
    assert {"rewrite", "router", "tools", "generate", "verify"} <= node_names


class FakeJavaClient:
    """模拟 JavaClient:含重复调用检测,命中返回固定片段。"""

    def __init__(self):
        self.hits = [{"chunkId": 1, "docId": 1, "headingPath": "第一章", "content": "内容"}]
        self.seen = set()

    def search_kb(self, query, top_k=5):
        key = ("search_kb", query, top_k)
        if key in self.seen:
            raise RuntimeError("工具 search_kb 已用相同参数调用过,已跳过重复调用")
        self.seen.add(key)
        return self.hits
