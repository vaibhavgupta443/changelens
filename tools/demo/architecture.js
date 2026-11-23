// Renders the architecture diagram (the same Mermaid source as the README) to a PNG for posts and slides.
// Needs puppeteer and a Chrome (CHROME_PATH). Usage: node architecture.js <out.png>
const puppeteer = require('puppeteer');
const out = process.argv[2] || 'architecture.png';

const diagram = `flowchart LR
  G[(git history)] --> E["Analysis engine<br/>JavaParser · call graph · diff<br/><i>deterministic, no model</i>"]
  G --> H["History index<br/>BM25 + local embeddings"]
  E --> F["Fact layer<br/>citable ids · verify"]
  H --> F
  F --> A["GitHub Action<br/>one PR comment"]
  F --> M["MCP server<br/>10 read-only tools"]
  F --> Q["Ask<br/>retrieve, model, verify"]
  M --> C["Claude · Cursor · any MCP client"]
  Q --> L["Any OpenAI-compatible model"]
  classDef core fill:#e8f1ff,stroke:#2f6fdd,color:#0b1f44;
  classDef ai fill:#efe8ff,stroke:#6a4fd4,color:#24104f;
  classDef out fill:#f3f4f6,stroke:#6b7280,color:#111827;
  class E,F,H core;
  class M,Q ai;
  class A,C,L out;`;

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'], executablePath: process.env.CHROME_PATH || undefined });
  const page = await browser.newPage();
  await page.setViewport({ width: 1500, height: 700, deviceScaleFactor: 2 });
  await page.setContent(`<!doctype html><meta charset="utf-8"><body style="margin:0;background:#fff;font-family:Segoe UI,Arial,sans-serif">
    <div style="padding:26px 34px 6px;font-size:22px;font-weight:600;color:#111">ChangeLens: a deterministic engine, and an AI layer that keeps models honest</div>
    <div id="d" class="mermaid" style="padding:10px 30px 24px">${diagram}</div>
    <script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
    <script>mermaid.initialize({ startOnLoad: true, theme: 'base', flowchart: { htmlLabels: true, curve: 'basis' }, themeVariables: { fontSize: '17px' } });</script>`);
  await page.waitForSelector('#d svg', { timeout: 30000 });
  await new Promise((r) => setTimeout(r, 800));
  const box = await (await page.$('body')).boundingBox();
  const height = Math.ceil((await page.evaluate(() => document.body.scrollHeight)));
  await page.setViewport({ width: 1500, height, deviceScaleFactor: 2 });
  await page.screenshot({ path: out, clip: { x: 0, y: 0, width: 1500, height } });
  await browser.close();
  console.log('wrote', out, box ? '' : '');
})();
