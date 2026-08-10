import os
from pathlib import Path

from dotenv import load_dotenv

# python/app/config.py → parents[2] = agentic-rag/,密钥文件与 Java 共用 backend/.env
ENV_FILE = Path(__file__).resolve().parents[2] / "backend" / ".env"
load_dotenv(ENV_FILE)

DEEPSEEK_BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1")
DEEPSEEK_API_KEY = os.getenv("DEEPSEEK_API_KEY", "")
DEEPSEEK_MODEL = os.getenv("DEEPSEEK_MODEL", "deepseek-chat")
SILICONFLOW_BASE_URL = os.getenv("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1")
SILICONFLOW_API_KEY = os.getenv("SILICONFLOW_API_KEY", "")
EMBEDDING_MODEL = os.getenv("EMBEDDING_MODEL", "BAAI/bge-m3")
JAVA_BASE_URL = os.getenv("JAVA_BASE_URL", "http://localhost:9000")
TOOL_TIMEOUT_SECONDS = float(os.getenv("TOOL_TIMEOUT_SECONDS", "10"))
MAX_STEPS = int(os.getenv("MAX_STEPS", "16"))  # 一轮5层,retry第二轮最多10层;16留余量(死循环由verify attempts兜底)