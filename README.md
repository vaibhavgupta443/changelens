# ChangeLens

**An AI assistant for pull-request review: paste a PR, get a structured review of what changed, what could break, and what to check.**

> **Status: early. Day 1 of a planned build.** The three-service skeleton runs and talks to itself. The review logic is not built yet. This README separates what exists from what is planned, so nothing here is oversold.

## Why

Reviewing a pull request means reading a diff without the context of why it was written. ChangeLens aims to give a reviewer a head start: a plain-language summary of the change, the places most likely to hide bugs, and concrete questions to ask the author. It is a reviewer's aid, not a replacement for one.

## What works today

Three services, started separately, each answering a health check:

```
React (Vite, :5173)  ──fetch──▶  Spring Boot (:8080  /api/health)  ──RestClient──▶  Python FastAPI (:8000  /health)
```

- **`frontend/`**: React + Vite page that shows whether the other two services are up.
- **`backend/`**: Spring Boot 4 service (Java 21). `GET /api/health` calls the Python service and reports `UP` or `DOWN` instead of crashing when it is not running. CORS is limited to the dev frontend.
- **`ai-service/`**: FastAPI service with `/health`.
- **`notes/`**: my learning notes and a commands cheat sheet.

## What I am building next

In order:

1. **Fetch a PR diff** from GitHub by URL (read-only, using a personal access token held in an environment variable, never in the repo).
2. **Parse the diff** into files and hunks in the Spring Boot service, and keep the review history in a database.
3. **Review with an LLM** in the Python service: summary, risk areas, suggested tests, and questions for the author, returned as structured JSON so the UI can render it.
4. **Show it in the React UI**: the diff on one side, findings anchored to lines on the other.
5. **Evaluate it**: a small set of real PRs with known issues, to measure how often the review finds them and how often it invents problems.

Design intent (to be confirmed as it gets built):

- **Why three services?** The JVM service owns the product (API, storage, GitHub integration); Python owns the model work, where the tooling is strongest. It also gives the project a realistic service boundary to work against.
- **Keys stay out of the repo.** `.gitignore` already excludes `.env`.
- **Model output is treated as untrusted.** It will be validated against a schema before the UI sees it.

## Run it

You need Python 3.10+, Java 21 and Node 20+. In three terminals, from this folder:

```bash
# 1. Python AI service
cd ai-service
python -m venv .venv && .venv/Scripts/activate      # Windows; use bin/activate on Linux/macOS
pip install -r requirements.txt
python -m uvicorn main:app --port 8000

# 2. Spring Boot backend
cd backend
./mvnw spring-boot:run                               # mvnw.cmd on Windows

# 3. React frontend
cd frontend
npm install
npm run dev                                          # open http://localhost:5173
```

The page should show React, Spring Boot and the Python service as UP. Stop the Python service and the Spring Boot line should flip to `DOWN` for the AI service without the backend crashing.

## Limitations

- No PR review logic yet, no GitHub integration, no database, no tests beyond the generated Spring Boot context test.
- No authentication.
