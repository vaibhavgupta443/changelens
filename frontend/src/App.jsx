import { useEffect, useState } from 'react'

// A "component" is just a function that returns what the page should look like (JSX).
function App() {
  // useState = a variable React watches. When it changes, the page redraws.
  // `health` starts as null (nothing loaded yet); setHealth is how we change it.
  const [health, setHealth] = useState(null)
  const [error, setError] = useState(null)

  // useEffect with [] = "run this once, when the page first appears".
  useEffect(() => {
    fetch('http://localhost:8080/api/health') // call the Spring Boot backend
      .then((res) => res.json())              // turn the response into a JS object
      .then(setHealth)                        // save it -> React redraws the page
      .catch(() => setError('Cannot reach the backend. Is Spring Boot running?'))
  }, [])

  return (
    <div style={{ fontFamily: 'sans-serif', padding: 32 }}>
      <h1>ChangeLens</h1>
      <p>Day 1: checking that all 3 services can talk to each other.</p>

      <p>React (frontend): <b>UP</b> (you are looking at it)</p>
      {error && <p style={{ color: 'crimson' }}>{error}</p>}
      {health && (
        <>
          <p>Spring Boot (backend): <b>{health.status}</b></p>
          <p>Python (ai-service): <b>{health.aiService.status}</b></p>
        </>
      )}
    </div>
  )
}

export default App
