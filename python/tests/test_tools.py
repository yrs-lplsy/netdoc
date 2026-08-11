# tools 层测试:幂等命中、None 字段防御、解析防御(审查 C1/I7 修复覆盖)
from app.tools import execute_tool


def test_execute_tool_idempotent_response():
    """Java 幂等命中响应 → 返回摘要文本,不重复执行。"""

    class IdemClient:
        def search_kb(self, query, top_k=5):
            return {"idempotent": True, "output": "5 hits"}

    text = execute_tool("search_kb", {"query": "x"}, IdemClient())
    assert "幂等命中" in text and "5 hits" in text


def test_execute_tool_null_content_defensive():
    """content 为 null 时不崩溃(Java SearchResult 序列化 null)。"""

    class NullClient:
        def search_kb(self, query, top_k=5):
            return [{"chunkId": 1, "docId": 1, "headingPath": "标题", "content": None}]

    text = execute_tool("search_kb", {"query": "x"}, NullClient())
    assert "标题" in text


def test_execute_tool_missing_fields_no_crash():
    """命中缺字段 → 防御渲染缺省值,不抛异常。"""

    class SparseClient:
        def search_kb(self, query, top_k=5):
            return [{"chunkId": 1}]

    text = execute_tool("search_kb", {"query": "x"}, SparseClient())
    assert "片段ID=1" in text


def test_execute_tool_exception_naturalized():
    """工具抛异常 → 自然语言化回喂,不逃逸。"""

    class BoomClient:
        def search_kb(self, *a, **k):
            raise RuntimeError("boom")

    text = execute_tool("search_kb", {"query": "x"}, BoomClient())
    assert "执行失败" in text and "boom" in text


def test_parse_hits_skips_non_meta_blocks():
    """幂等摘要/异常文本等非标准块被跳过,标准块正常解析。"""
    from app.nodes.tools_node import _parse_hits

    text = "(幂等命中,复用上次结果) 5 hits\n\n[1] 片段ID=1 文档ID=1 标题=第一章\n内容"
    hits = _parse_hits(text)
    assert len(hits) == 1
    assert hits[0]["headingPath"] == "第一章"
    assert hits[0]["content"] == "内容"
