from app.java_client import JavaClient

# OpenAI function calling 格式工具定义(DeepSeek 兼容)
TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "search_kb",
            "description": "在网络设备技术文档知识库中检索与问题最相关的文档片段(混合检索:向量 + 关键词)。回答技术问题时必须先调用它。",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "检索关键词或完整问题"},
                    "top_k": {"type": "integer", "description": "返回片段数,默认 5"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_doc_detail",
            "description": "获取某篇文档的完整详情(标题、状态、全部分块内容),用于溯源与深挖细节。",
            "parameters": {
                "type": "object",
                "properties": {"doc_id": {"type": "integer"}},
                "required": ["doc_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_stats",
            "description": "获取知识库统计信息(文档数、分块数)。",
            "parameters": {"type": "object", "properties": {}},
        },
    },
]

_FUNC_MAP = {
    "search_kb": lambda c, args: c.search_kb(args["query"], args.get("top_k", 5)),
    "get_doc_detail": lambda c, args: c.get_doc_detail(args["doc_id"]),
    "get_stats": lambda c, args: c.get_stats(),
}


def execute_tool(name: str, args: dict, client: JavaClient | None = None) -> str:
    """执行工具并返回自然语言化结果文本(LLM 可读);错误也自然语言化回喂。"""
    client = client or JavaClient()
    try:
        raw = _FUNC_MAP[name](client, args)
    except Exception as e:
        return f"[工具 {name} 执行失败] {e}"
    if isinstance(raw, list):
        if not raw:
            return "知识库中未检索到相关内容。"
        lines = []
        for i, hit in enumerate(raw, 1):
            lines.append(
                f"[{i}] 片段ID={hit.get('chunkId')} 文档ID={hit.get('docId')} 标题={hit.get('headingPath')}\n{hit.get('content', '')[:500]}"
            )
        return "\n\n".join(lines)
    return str(raw)
