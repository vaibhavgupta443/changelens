from fastapi import FastAPI

# This creates our Python web server. "app" is the thing uvicorn will run.
app = FastAPI(title="ChangeLens AI Service")


# @app.get("/health") means: when someone visits http://localhost:8000/health,
# run the function right below it and send back what it returns (as JSON).
@app.get("/health")
def health():
    return {"service": "ai-service", "status": "UP"}
@app.get("/hello")
def hello():
    return {"message" : "hi"}