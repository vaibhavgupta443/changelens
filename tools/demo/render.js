// Renders docs/demo/session.json (recorded by capture.js) as a terminal-style GIF and a PNG of the last frame.
// Needs: puppeteer (headless Chrome), gifenc, pngjs.   Usage: node render.js <session.json> <out.gif> <out.png>
const fs = require('fs');
const puppeteer = require('puppeteer');
const { GIFEncoder, quantize, applyPalette } = require('gifenc');
const { PNG } = require('pngjs');

const [sessionFile, outGif, outPng] = process.argv.slice(2);
const session = JSON.parse(fs.readFileSync(sessionFile, 'utf8'));
const W = 980, H = 640;

const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
function page(lines, caption) {
  const body = lines.map((l) => `<div class="${l.cls}">${l.html}</div>`).join('');
  return `<!doctype html><meta charset="utf-8"><style>
    :root { color-scheme: dark; }
    body { margin: 0; background: #0f1117; font: 15px/1.5 Consolas, "Cascadia Mono", "DejaVu Sans Mono", monospace; color: #c9d1d9; }
    .bar { height: 38px; background: #161b22; border-bottom: 1px solid #30363d; display: flex; align-items: center; padding: 0 16px; gap: 8px; }
    .dot { width: 12px; height: 12px; border-radius: 50%; } .r { background: #ff5f56 } .y { background: #ffbd2e } .g { background: #27c93f }
    .title { margin-left: 12px; color: #8b949e; font-size: 13px; }
    .note { padding: 8px 20px; background: #1b2230; color: #9db1cc; font-size: 12.5px; border-bottom: 1px solid #30363d; }
    .term { padding: 14px 20px; }
    .call { color: #e6edf3; margin-top: 10px; } .call b { color: #58a6ff; } .prompt { color: #3fb950; }
    .time { color: #d29922; }
    .result { color: #c9d1d9; padding-left: 18px; white-space: pre; }
    .fact { color: #8b949e; padding-left: 18px; white-space: pre; }
    .good { color: #3fb950; } .bad { color: #f85149; }
    .cur { display: inline-block; width: 8px; height: 15px; background: #c9d1d9; vertical-align: -2px; }
  </style>
  <div class="bar"><span class="dot r"></span><span class="dot y"></span><span class="dot g"></span><span class="title">changelens-mcp · ${esc(session.repo)} ${esc(session.base)}..${esc(session.head)}</span></div>
  <div class="note">${esc(caption)}</div>
  <div class="term">${body}</div>`;
}

function render(step, partial) {
  // returns an html line for a recorded step; partial (0..1) types out a call line
  if (step.kind === 'call') {
    const [name, ...rest] = step.text.split('  ');
    const full = rest.join('  ');
    const shown = partial >= 1 ? full : full.slice(0, Math.floor(full.length * partial));
    const time = partial >= 1 ? `   <span class="time">${(step.ms / 1000).toFixed(1)} s</span>` : '<span class="cur"></span>';
    return { cls: 'call', html: `<span class="prompt">▶</span> <b>${esc(name)}</b> ${esc(shown)}${time}` };
  }
  let html = esc(step.text);
  html = html.replace(/-&gt; real/g, '-&gt; <span class="good">real</span>').replace(/-&gt; NOT FOUND/g, '-&gt; <span class="bad">NOT FOUND</span>');
  return { cls: step.kind === 'fact' ? 'fact' : 'result', html };
}

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'], executablePath: process.env.CHROME_PATH || undefined });
  const tab = await browser.newPage();
  await tab.setViewport({ width: W, height: H, deviceScaleFactor: 1 });
  const caption = `Recorded ${session.recordedAt} against the real server. A scripted client stands in for an AI assistant; no model is involved. Waits are shortened, timings are real.`;
  const gif = GIFEncoder();
  let last;
  async function frame(lines, delay) {
    await tab.setContent(page(lines, caption));
    const png = await tab.screenshot({ type: 'png' });
    last = png;
    const { data, width, height } = PNG.sync.read(png);
    const palette = quantize(data, 256, { format: 'rgb565' });
    gif.writeFrame(applyPalette(data, palette, 'rgb565'), width, height, { palette, delay });
  }

  const lines = [];
  const body = [...session.steps];
  await frame([], 900);
  for (let i = 0; i < body.length; i++) {
    const s = body[i];
    if (s.kind === 'call') {
      for (const f of [0.25, 0.6, 1]) {
        await frame([...lines, render(s, f)], f === 1 ? 700 : 130);
      }
      lines.push(render(s, 1));
    } else {
      lines.push(render(s, 1));
      await frame(lines, s.kind === 'fact' ? 450 : 380);
    }
  }
  await frame(lines, 4500);
  gif.finish();
  fs.writeFileSync(outGif, Buffer.from(gif.bytes()));
  fs.writeFileSync(outPng, last);
  await browser.close();
  console.log(`wrote ${outGif} (${(fs.statSync(outGif).size / 1024).toFixed(0)} KB) and ${outPng}`);
})();
