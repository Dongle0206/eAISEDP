// HTML → PDF via puppeteer-core + system Chrome
const puppeteer = require('puppeteer-core');
const path = require('path');
const fs = require('fs');

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const htmlPath = path.join(__dirname, 'manual.html');
const pdfPath = path.join(__dirname, '企业级多Agent协同运作指导手册.pdf');

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--disable-dev-shm-usage'],
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 794, height: 1123, deviceScaleFactor: 2 });
    await page.goto('file:///' + htmlPath.replace(/\\/g, '/'), { waitUntil: 'networkidle0', timeout: 60000 });
    await page.evaluate(() => document.fonts ? document.fonts.ready : Promise.resolve());
    await new Promise(r => setTimeout(r, 800));
    await page.pdf({
      path: pdfPath,
      format: 'A4',
      printBackground: true,
      margin: { top: 0, right: 0, bottom: 0, left: 0 },
      preferCSSPageSize: true,
    });
    console.log('[build] PDF OK:', pdfPath);
    console.log('[build] size:', (fs.statSync(pdfPath).size / 1024).toFixed(1) + ' KB');
  } finally {
    await browser.close();
  }
})().catch(e => { console.error('[build] FAILED:', e.message); process.exit(1); });
