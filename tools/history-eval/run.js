// Measures search_history against a labelled question set, through the real MCP server jar over stdio.
// Usage: node run.js <changelens-mcp*.jar> <workspace-folder> <questions.json> [keyword|semantic|hybrid] [--scores]
const { spawn } = require('child_process');
const fs = require('fs');
const [jar, workspace, file, mode = 'hybrid'] = process.argv.slice(2);
const showScores = process.argv.includes('--scores');
const set = JSON.parse(fs.readFileSync(file, 'utf8'));
const p = spawn('java', ['-jar', jar, workspace], { stdio: ['pipe', 'pipe', 'ignore'] });
let buf = ''; const waiting = new Map(); let id = 0;
p.stdout.on('data', (d) => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i); buf = buf.slice(i + 1); if (!l.trim()) continue; const m = JSON.parse(l); if (m.id != null && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } } });
const rpc = (method, params) => new Promise((res) => { const n = ++id; waiting.set(n, res); p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) + '\n'); });
const search = async (query) => { const r = (await rpc('tools/call', { name: 'search_history', arguments: { repo: set.repo, query, limit: 10, mode } })).result; return { error: r.isError, body: JSON.parse(r.content[0].text) }; };
(async () => {
  await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'eval', version: '1' } });
  p.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  const stats = {}; const misses = []; const rows = [];
  let meta; let modeUsed;
  for (const q of set.questions) {
    const t0 = Date.now();
    const { error, body } = await search(q.q);
    if (error) { console.log('ERROR', q.q, body); continue; }
    meta = meta || body.history; modeUsed = body.mode;
    const commits = body.results.map((r) => r.commit);
    const s = (stats[q.kind] = stats[q.kind] || { n: 0, r1: 0, r5: 0, r10: 0, mrr: 0, fp: 0, ms: [] });
    s.n++; s.ms.push(Date.now() - t0);
    if (q.kind === 'unrelated') {
      if (commits.length) s.fp++;
      rows.push({ kind: q.kind, q: q.q, rank: '-', ansScore: null, topScore: body.results[0] ? body.results[0].score : null });
      if (commits.length) misses.push({ kind: q.kind, q: q.q, rank: `${commits.length} result(s) returned`, top: body.results.slice(0, 2).map((r) => r.subject.slice(0, 70)) });
      continue;
    }
    const rank = commits.findIndex((c) => q.answers.some((a) => c.startsWith(a))) + 1;
    if (rank === 1) s.r1++; if (rank >= 1 && rank <= 5) s.r5++; if (rank >= 1) s.r10++; if (rank) s.mrr += 1 / rank;
    rows.push({ kind: q.kind, q: q.q, rank: rank || '>10', ansScore: rank ? body.results[rank - 1].score : null, topScore: body.results[0] ? body.results[0].score : null });
    if (rank !== 1) misses.push({ kind: q.kind, q: q.q, rank: rank || 'not in top 10', top: body.results.slice(0, 2).map((r) => r.subject.slice(0, 70)) });
  }
  console.log(`mode=${modeUsed}  history: ${meta.commitsIndexed} commits indexed`);
  for (const [k, s] of Object.entries(stats)) {
    const t = `first query ${s.ms[0]} ms, then ${Math.round(s.ms.slice(1).reduce((a, b) => a + b, 0) / Math.max(1, s.ms.length - 1))} ms each`;
    console.log(k === 'unrelated'
      ? `${k.padEnd(10)} n=${s.n}  returned something for ${s.fp}/${s.n} (should be 0)`
      : `${k.padEnd(10)} n=${s.n}  top1 ${s.r1}/${s.n}  top5 ${s.r5}/${s.n}  top10 ${s.r10}/${s.n}  MRR ${(s.mrr / s.n).toFixed(2)}  (${t})`);
  }
  if (showScores) { console.log('\nscores (answer = score of the right commit, top = score of the best result):'); rows.forEach((r) => console.log(` [${r.kind.padEnd(10)}] rank ${String(r.rank).padEnd(4)} answer ${r.ansScore ?? '-'}  top ${r.topScore ?? '-'}  "${r.q.slice(0, 60)}"`)); }
  console.log('\nnot ranked first:'); misses.forEach((m) => console.log(` [${m.kind}] "${m.q}" -> ${m.rank}\n     got: ${m.top.join(' | ')}`));
  p.stdin.end(); setTimeout(() => process.exit(0), 1500);
})();
