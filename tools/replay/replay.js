// Replays real git history through ChangeLens and compares it with an independent check.
//   node tools/replay/replay.js <repo-dir> <number-of-commits> <results.json>     (env CHANGELENS_JAR = path to changelens-cli.jar)
// For each non-merge commit that touches Java, the check asks: does the diff change any CODE line (ignoring whitespace, comments,
// Javadoc and imports)? ChangeLens is then asked whether it reports a change. Disagreements need a human look: the check is an
// approximation (for example it cannot see whitespace inside a string literal), so it is a screen, not a ground truth.
const { execFileSync, execFile } = require('child_process');
const fs = require('fs');
const path = require('path');
const [repo, count, outFile] = [process.argv[2], +process.argv[3] || 100, process.argv[4]];
const JAR = process.env.CHANGELENS_JAR || path.join(__dirname, '..', '..', 'backend', 'target', 'changelens-cli.jar');
const git = (...a) => execFileSync('git', ['-C', repo, ...a], { encoding: 'utf8', maxBuffer: 1 << 28 });

const commits = git('log', '--no-merges', '--format=%H', '-n', '600', '--', '*.java').split('\n').filter(Boolean).slice(0, count);

function oracle(sha) {
  let parent;
  try { parent = git('rev-parse', sha + '^').trim(); } catch (e) { return null; }
  const files = git('diff', '--name-only', parent, sha, '--', '*.java').split('\n').filter(Boolean);
  const codeLines = [];
  for (const f of files) {
    const d = git('diff', '-w', '-U0', '--no-color', parent, sha, '--', f).split('\n');
    for (const l of d) {
      if (!/^[+-]/.test(l) || /^(\+\+\+|---)/.test(l)) continue;
      const t = l.slice(1).trim();
      if (t === '' || t.startsWith('//') || t.startsWith('/*') || t.startsWith('*') || t.startsWith('import ') || t.startsWith('package ')) continue;
      codeLines.push(f.split('/').pop() + ': ' + l.slice(0, 110));
    }
  }
  return { parent, files: files.length, codeLines };
}

function predicted(md) {
  const m = /Methods: (\d+) added, (\d+) removed, \*\*(\d+) behaviour changed\*\*, (\d+) declaration-only/.exec(md);
  const t = /(\d+) types added, (\d+) removed, (\d+) modified/.exec(md);
  const n = m ? m.slice(1, 5).map(Number) : [0, 0, 0, 0];
  const ty = t ? t.slice(1, 3).map(Number) : [0, 0];
  const fields = (md.match(/field (added|removed|changed|modified|initialiser)/g) || []).length;
  const decls = (md.match(/annotation (added|removed)|\*\*renamed\*\*|modifier (added|removed)|### (RENAMED|MOVED)|extends|implements/g) || []).length;
  const moved = /([1-9]\d*) moved\/renamed/.test(md);
  return { methods: n, types: ty, fields, decls, change: n.some((x) => x > 0) || ty.some((x) => x > 0) || fields > 0 || decls > 0 || moved };
}

const results = [];
let next = 0;
const POOL = 3;
function runOne(sha) {
  return new Promise((resolve) => {
    const o = oracle(sha);
    if (!o || o.files === 0) return resolve(null);
    const tmp = path.join(path.dirname(outFile), 'tmp-' + sha.slice(0, 8) + '.md');
    execFile('java', ['-jar', JAR, '--repo', repo, '--base', o.parent, '--head', sha, '--format', 'markdown', '--out', tmp], { maxBuffer: 1 << 26 }, (err) => {
      let md = '';
      try { md = fs.readFileSync(tmp, 'utf8'); fs.unlinkSync(tmp); } catch (e) { /* failed run */ }
      const subject = git('log', '-1', '--format=%s', sha).trim().slice(0, 80);
      resolve({ sha: sha.slice(0, 10), subject, javaFiles: o.files, truthChange: o.codeLines.length > 0, codeSample: o.codeLines.slice(0, 3), failed: !!err || md === '', pred: predicted(md) });
    });
  });
}
async function worker() {
  while (next < commits.length) {
    const i = next++;
    const r = await runOne(commits[i]);
    if (r) results.push(r);
    if (results.length % 10 === 0) fs.writeFileSync(outFile, JSON.stringify(results, null, 1));
  }
}
Promise.all(Array.from({ length: POOL }, worker)).then(() => {
  fs.writeFileSync(outFile, JSON.stringify(results, null, 1));
  const ok = results.filter((r) => !r.failed);
  const c = { TP: 0, TN: 0, FP: 0, FN: 0 };
  ok.forEach((r) => { c[r.truthChange ? (r.pred.change ? 'TP' : 'FN') : (r.pred.change ? 'FP' : 'TN')]++; });
  console.log('commits', results.length, 'failed runs', results.length - ok.length, JSON.stringify(c));
  console.log('DONE');
});
