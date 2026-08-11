# python/app/extract.py
from fastapi import APIRouter
from pydantic import BaseModel

from app.llm import chat_model

router = APIRouter()


class ExtractRequest(BaseModel):
    kb_id: int
    doc_id: int
    chunks: list[str]


class ExtractResponse(BaseModel):
    entities: list[dict]
    relations: list[dict]


# 实体类型限 6 类,控制抽取质量(spec §13 风险表)
ENTITY_TYPES = ("DEVICE", "SOFTWARE", "COMMAND", "CONFIG", "PROTOCOL", "VENDOR")

EXTRACT_TOOL = {
    "type": "function",
    "function": {
        "name": "submit_kg",
        "description": "提交从文档片段中抽取的实体与关系。只抽取确定的事实,不要猜测。",
        "parameters": {
            "type": "object",
            "properties": {
                "entities": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "实体名,如 OpenWrt"},
                            "type": {"type": "string", "enum": list(ENTITY_TYPES)},
                            "confidence": {"type": "number", "description": "0-1 置信度"},
                        },
                        "required": ["name", "type", "confidence"],
                    },
                },
                "relations": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "source": {"type": "string"},
                            "target": {"type": "string"},
                            "relation": {"type": "string", "description": "如 USES/REQUIRES/CONFIGURES/SUPPORTS"},
                            "confidence": {"type": "number"},
                        },
                        "required": ["source", "target", "relation", "confidence"],
                    },
                },
            },
            "required": ["entities", "relations"],
        },
    },
}

SYSTEM = (
    "你是知识图谱构建器。从设备技术文档片段中抽取实体与关系,只抽确定事实。"
    "实体类型限 DEVICE/SOFTWARE/COMMAND/CONFIG/PROTOCOL/VENDOR。"
    "关系如:OpenWrt -[SUPPORTS]-> MT799X。置信度 <0.7 的不要提交。"
)

# 别名归一化:不同写法映射到同一规范名(面试点:实体消歧)
ALIASES = {
    "openwrt": "OpenWrt",
    "opkg": "opkg",
    "luci": "Luci",
    "mt799x": "MT799X",
}


def normalize(name: str) -> str:
    return ALIASES.get(name.strip().lower(), name.strip())


@router.post("/extract", response_model=ExtractResponse)
async def extract(req: ExtractRequest):
    entities, relations = [], []
    for chunk in req.chunks[:20]:   # 单次重建上限 20 chunk,防超时
        resp = await chat_model.ainvoke(
            [
                {"role": "system", "content": SYSTEM},
                {"role": "user", "content": f"文档片段:\n{chunk[:1500]}"},
            ],
            tools=[EXTRACT_TOOL],
        )
        calls = getattr(resp, "tool_calls", None) or []
        for c in calls:
            args = c.get("args") or {}
            for e in args.get("entities", []):
                if e.get("type") not in ENTITY_TYPES:   # 白名单防御(LLM 可能不遵守 enum)
                    continue
                if e.get("confidence", 0) >= 0.7:
                    e["normalized_name"] = normalize(e["name"])
                    entities.append(e)
            for r in args.get("relations", []):
                if r.get("confidence", 0) >= 0.7:
                    relations.append(r)
    return ExtractResponse(entities=entities, relations=relations)
