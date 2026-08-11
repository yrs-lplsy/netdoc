import json

import httpx

from app.config import JAVA_BASE_URL, TOOL_TIMEOUT_SECONDS


class JavaClient:
    """反向调用 Java 工具端点;记录已执行调用,重复调用检测。
    conversation_id 非空时,每次调用携带递增 agentStepId → Java 侧幂等键( 双层防线)。"""

    def __init__(self, base_url: str = JAVA_BASE_URL, timeout: float = TOOL_TIMEOUT_SECONDS,
                 conversation_id: int | None = None, kb_id: int | None = None):
        # 接口基础地址，默认常量 JAVA_BASE_URL
        self.base_url = base_url
        # 请求超时秒数，默认全局超时常量
        self.timeout = timeout
        # 会话编号，可以是整型或者为空，默认无会话 ID
        self.conversation_id = conversation_id
        self._step = 0
        self._seen: set[tuple[str, str]] = set()
        self.kb_id = kb_id
        self._token = None

    def _call(self, tool: str, payload: dict) -> dict:
        key = (tool, json.dumps(payload, sort_keys=True, ensure_ascii=False))
        if key in self._seen:
            raise RuntimeError(f"工具 {tool} 已用相同参数调用过,已跳过重复调用")
        self._ensure_token()
        if self.conversation_id is not None:
            self._step += 1
            payload = {**payload, "conversationId": self.conversation_id, "agentStepId": self._step}
        with httpx.Client(timeout=self.timeout) as client:
            r = client.post(f"{self.base_url}/api/agent/tools/{tool}", json=payload,
                            headers={"Authorization": f"Bearer {self._token}"})
            r.raise_for_status()
        self._seen.add(key)
        return r.json()

    def search_kb(self, query: str, top_k: int = 5) -> list:
        payload = {"query": query, "topK": top_k}
        if self.kb_id is not None:
            payload["kbId"] = self.kb_id   # Java ToolRequest.kbId 字段名对齐
        return self._call("search", payload)

    def get_doc_detail(self, doc_id: int) -> dict:
        return self._call("get-doc-detail", {"docId": doc_id})

    def get_stats(self) -> dict:
        return self._call("get-stats", {})

    def _ensure_token(self) -> None:
        if self._token:
            return
        with httpx.Client(timeout=self.timeout) as c:
            r = c.post(f"{self.base_url}/api/auth/login",
                    json={"username": AGENT_USERNAME, "password": AGENT_PASSWORD})
            r.raise_for_status()
            self._token = r.json()["token"]
    
    
