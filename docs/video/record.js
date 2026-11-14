// Records docs/video/video.html into a video (silent). Usage: node record.js <durations.json> <out_dir>
// durations.json: [{"id": "...", "ms": 5200}, ...] in the order of script.json.
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const script = JSON.parse(fs.readFileSync(path.join(__dirname, 'script.json'), 'utf8'));
  const durs = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const out = process.argv[3];
  const scenes = script.map((s, i) => ({ ...s, ms: durs[i].ms }));
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, recordVideo: { dir: out, size: { width: 1280, height: 720 } } });
  const page = await ctx.newPage();
  const t0 = Date.now();
  await page.goto('file://' + path.join(__dirname, 'video.html'));
  await page.evaluate(() => document.fonts.ready);
  const lead = Date.now() - t0;
  fs.writeFileSync(path.join(out, 'lead_ms.txt'), String(lead));
  await page.evaluate(sc => { window.run(sc); }, scenes);
  await page.waitForFunction(() => window.done === true, null, { timeout: 15 * 60 * 1000, polling: 500 });
  await page.waitForTimeout(800);
  await ctx.close();
  await browser.close();
})();
