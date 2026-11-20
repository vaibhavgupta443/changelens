# Commands cheat sheet (copy from here, no need to memorize)

Open a terminal in VS Code: Terminal > New Terminal

## Python server (port 8000)
cd ai-service
.\.venv\Scripts\python -m uvicorn main:app --port 8000 --reload

Stop any server: click the terminal, press Ctrl + C

## Java server (port 8080)   (we will do this later)
cd backend
.\mvnw.cmd spring-boot:run

## React page (port 5173)    (we will do this later)
cd frontend
npm run dev
