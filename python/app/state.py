from typing import Optional, TypedDict


class AgentState(TypedDict, total=False):
    question: str              # 原始问题
    history: list              # 滑动窗口历史 [{role, content}]
    conversation_id: Optional[int]  # 会话 ID(Java 透传,工具幂等键用)
    rewritten: Optional[str]   # 改写后问题
    needs_retrieval: bool      # Router 决策
    contexts: list             # 检索到的片段 [{chunkId, docId, headingPath, content}]
    answer: str                # 最终回答
    sources: list              # 引用来源 [{title, snippet}]
    attempts: int              # 自检重试计数(最多 1 次)
    verified: bool             # 忠实度自检是否通过
    error: Optional[str]       # 错误信息(自然语言化)
    tool_calls: list           # 本次会话已执行调用记录(重复检测依据)
    kb_id: Optional[int]       # 多库隔离上下文,工具调用携带
