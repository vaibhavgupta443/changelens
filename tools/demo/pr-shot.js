// Screenshots the ChangeLens comment on a real, public GitHub pull request page.
// Needs puppeteer and a Chrome (CHROME_PATH). Usage: node pr-shot.js <pull request url> <out.png> [maxHeight]
const puppeteer = require('puppeteer');
const [url, out, maxHeight = '1500'] = process.argv.slice(2);

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--no-sandbox'], executablePath: process.env.CHROME_PATH || undefined });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 1600, deviceScaleFactor: 1.6 });
  await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'light' }]);
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
  const handle = await page.evaluateHandle(() => {
    const all = [...document.querySelectorAll('.timeline-comment, .js-comment-container')];
    return all.find((el) => el.textContent.includes('ChangeLens: what this pull request does')) || null;
  });
  const el = handle.asElement();
  if (!el) throw new Error('ChangeLens comment not found on the page');
  await el.scrollIntoView();
  await new Promise((r) => setTimeout(r, 6000)); // GitHub renders Mermaid diagrams lazily, in a frame
  const box = await el.boundingBox();
  const height = Math.min(box.height, Number(maxHeight));
  await page.screenshot({ path: out, clip: { x: box.x, y: box.y + (await page.evaluate(() => window.scrollY)), width: box.width, height } });
  console.log(`wrote ${out}  (${Math.round(box.width)}x${Math.round(height)} of a ${Math.round(box.height)} px comment)`);
  await browser.close();
})().catch((e) => { console.error('FAILED', e.message); process.exit(1); });
