import json

import pytest

from app.java_client import JavaClient
from app.tools import execute_tool


class FakeClient:
    """模拟 Java 工具响应。"""

    def __init__(self, hits=None):
        self.hits = hits or []
        self.calls = []

    def search_kb(self, query, top_k=5):
        self.calls.append(("search_kb", query, top_k))
        return self.hits

    def get_stats(self):
        self.calls.append(("get_stats",))
        return {"docCount": 2, "chunkCount": 10}


def test_duplicate_call_detected():
    c = JavaClient(base_url="http://fake:1")
    # 幂等 key 必须与 _call 内部计算完全一致:工具名 "search" + 同样的序列化
    c._seen.add(("search", json.dumps({"query": "安装", "topK": 5}, sort_keys=True, ensure_ascii=False)))
    with pytest.raises(RuntimeError, match="重复调用"):
        c.search_kb("安装", 5)


def test_execute_tool_empty_result_message():
    c = FakeClient(hits=[])
    text = execute_tool("search_kb", {"query": "不存在的词"}, c)
    assert "未检索到" in text


def test_execute_tool_formats_hits():
    c = FakeClient(hits=[{"chunkId": 1, "docId": 2, "headingPath": "第一章", "content": "内容"}])
    text = execute_tool("search_kb", {"query": "安装"}, c)
    assert "片段ID=1" in text and "第一章" in text


def test_execute_tool_error_naturalized():
    class Boom:
        def search_kb(self, *a, **k):
            raise RuntimeError("connection refused")

    text = execute_tool("search_kb", {"query": "x"}, Boom())
    assert "执行失败" in text and "connection refused" in text
