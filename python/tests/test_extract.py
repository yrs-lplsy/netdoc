# python/tests/test_extract.py
from types import SimpleNamespace

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

FAKE_CALL = {"name": "submit_kg", "args": {
    "entities": [
        {"name": "OpenWrt", "type": "SOFTWARE", "confidence": 0.95},
        {"name": "opkg", "type": "COMMAND", "confidence": 0.6},   # 低置信应被过滤
    ],
    "relations": [
        {"source": "OpenWrt", "target": "MT799X", "relation": "SUPPORTS", "confidence": 0.9},
    ],
}}


def test_extract_filters_low_confidence_and_normalizes(monkeypatch):
    class FakeChat:
        async def ainvoke(self, messages, **kwargs):
            return SimpleNamespace(content="", tool_calls=[FAKE_CALL])

    import app.extract
    monkeypatch.setattr(app.extract, "chat_model", FakeChat())

    r = client.post("/extract", json={"kb_id": 1, "doc_id": 1, "chunks": ["OpenWrt 支持 MT799X 芯片"]})
    assert r.status_code == 200
    data = r.json()
    names = [e["name"] for e in data["entities"]]
    assert "OpenWrt" in names
    assert len(data["entities"]) == 1      # opkg(0.6) 被置信度过滤
    assert data["entities"][0]["normalized_name"] == "OpenWrt"
    assert len(data["relations"]) == 1
