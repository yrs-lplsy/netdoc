from fastapi import FastAPI

app = FastAPI(title="NetDoc Agent Service", version="0.2.0")


@app.get("/health")
def health():
    return {"status": "UP"}
