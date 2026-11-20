# Day 1 - Three services talking

## How to run (3 separate terminals, from the Project1 folder)

1. Python:  cd ai-service ; .\.venv\Scripts\python -m uvicorn main:app --port 8000
2. Java:    cd backend ; .\mvnw.cmd spring-boot:run
3. React:   cd frontend ; npm run dev   -> open http://localhost:5173

## What happens when you open the page
Browser (React :5173) --fetch--> Spring Boot (:8080 /api/health) --RestClient--> Python (:8000 /health)

## Words to know
- Port: a numbered "door" on your computer. Each server listens on its own door.
- Endpoint: one URL a server answers, like /api/health.
- JSON: the text format servers use to send data, like {"status":"UP"}.
- CORS: browser safety rule; a page may only call servers that said "this page is allowed".

## My notes (fill in tonight)
- What I built:
- What I learned:
- What confused me:
