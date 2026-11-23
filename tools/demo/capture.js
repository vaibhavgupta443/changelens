// Records a real session against the real MCP server: every line in session.json is something the server actually answered.
// A scripted client stands in for an AI assistant, so the recording shows the tools, not a model.
//
// Usage: node capture.js <changelens-mcp*.jar> <folder holding the repo> <repo> <base> <head> <out.json>
const { spawn } = require('child_process');
const fs = require('fs');
const [jar, workspace, repo, base, head, out] = process.argv.slice(2);

const p = spawn('java', ['-jar', jar, workspace], { stdio: ['pipe', 'pipe', 'ignore'] });
let buf = ''; const waiting = new Map(); let id = 0;
p.stdout.on('data', (d) => { buf += d; let i; while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i); buf = buf.slice(i + 1); if (!l.trim()) continue; const m = JSON.parse(l); if (m.id != null && waiting.has(m.id)) { waiting.get(m.id)(m); waiting.delete(m.id); } } });
const rpc = (method, params) => new Promise((res) => { const n = ++id; waiting.set(n, res); p.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: n, method, params }) + '\n'); });

const steps = [];
const say = (kind, text, ms) => steps.push({ kind, text, ms });
async function tool(name, args, label) {
  const t0 = Date.now();
  const r = (await rpc('tools/call', { name, arguments: args })).result;
  const ms = Date.now() - t0;
  if (r.isError) throw new Error(name + ': ' + r.content[0].text);
  say('call', `${name}  ${label || Object.entries(args).map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`).join('  ')}`, ms);
  return JSON.parse(r.content[0].text);
}
const short = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

(async () => {
  await rpc('initialize', { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'demo', version: '1' } });
  p.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');

  const a = await tool('analyze_change', { repo, base, head });
  const o = a.overview;
  say('result', `${o.filesChanged} files changed, ${o.methodsBehaviorChanged} methods with new or changed logic`);
  say('result', `${o.changedWithoutTests} changed methods that no test reaches, ${Math.round(o.resolvedCallRatio * 100)}% of calls resolved`);

  const ch = await tool('list_changes', { analysisId: a.analysisId, nature: 'BEHAVIOR' });
  say('result', `${ch.total} methods, most important first:`);
  for (const m of ch.items.slice(0, 2)) {
    const [type, rest] = m.id.split('#');
    say('result', `  ${type.split('.').pop()}#${short(rest, 34)}`);
    say('fact', `    ${short(m.facts.slice(0, 2).map((f) => f.text).join(' · '), 92)}`);
  }

  const h = await tool('change_history', { analysisId: a.analysisId });
  say('result', `history read as of the base commit (${h.history.commitsIndexed} commits back to ${h.history.oldestIndexed}):`);
  for (const f of h.files.slice(0, 3)) {
    say('result', `  ${f.file.split('/').pop()}: ${f.earlierCommitsTouchingFile} earlier commits touched it, ${f.earlierCommitsLookingLikeFixes} look like fixes`);
  }

  const q = 'fix for null pointer in the jimple frontend';
  const s = await tool('search_history', { repo, query: q, limit: 2 }, `query="${q}"`);
  s.results.forEach((r) => say('result', `  ${r.commit.slice(0, 8)}  ${short(r.subject, 78)}`));

  const real = s.results[0].commit;
  const fake = '0123456789abcdef0123456789abcdef01234567';
  const v = await tool('verify_claims', { analysisId: a.analysisId, claims: [{ commit: real }, { commit: fake }] }, `commits=[${real.slice(0, 8)}, ${fake.slice(0, 8)}]`);
  say('result', `  ${v.results.map((r) => `${r.commit.slice(0, 8)} -> ${r.commitKnown ? 'real' : 'NOT FOUND'}`).join('    ')}`);

  fs.writeFileSync(out, JSON.stringify({ repo, base, head, jar: require('path').basename(jar), recordedAt: new Date().toISOString().slice(0, 10), steps }, null, 1));
  console.log(`saved ${steps.length} steps to ${out}`);
  p.stdin.end(); setTimeout(() => process.exit(0), 800);
})().catch((e) => { console.error('FAILED', e.message); p.kill(); process.exit(1); });
