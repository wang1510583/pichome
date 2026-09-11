const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const source = fs.readFileSync(path.join(__dirname, '../android/app/src/main/java/top/lempet/pichome/MainActivity.java'), 'utf8');
function extract(start, end) {
    const lines = source.split('\n');
    const begin = lines.findIndex(line => line.includes(start));
    const finish = lines.findIndex((line, index) => index > begin && line.includes(end));
    assert.ok(begin >= 0 && finish > begin);
    return lines.slice(begin, finish).map(line => JSON.parse(line.trim().replace(/^\+\s*/, ''))).join('');
}
const observer = extract('window.__pichomeAndroidDownloadObserver=new MutationObserver', 'window.__pichomeAndroidDownloadObserver.observe');
const hideDownloads = extract('function hideDownloads(root)', 'function installCacheMenuItem(doc)');
(async () => {
    const browser = await chromium.launch({headless:true});
    try {
        const page = await browser.newPage();
        await page.setContent('<div id="list"></div>');
        await page.evaluate(({observer, hideDownloads}) => {
            window.frameScans = 0; window.downloadScans = 0; window.menuScans = 0;
            window.safeInstall = (name, task) => task();
            window.installFrameCss = root => { frameScans++; if (root.id === 'list') throw Error('rescanned list'); };
            window.installCacheMenuItem = () => menuScans++;
            window.eval(hideDownloads);
            const original = window.hideDownloads;
            window.hideDownloads = root => { downloadScans++; original(root); };
            window.eval(observer);
            __pichomeAndroidDownloadObserver.observe(document.documentElement, {childList:true,subtree:true});
            const list = document.querySelector('#list');
            for (let index = 0; index < 1000; index++) {
                const card = document.createElement('div');
                list.appendChild(card);
                const button = document.createElement('button');
                button.textContent = '查看';
                card.appendChild(button);
            }
        }, {observer, hideDownloads});
        await page.waitForFunction(() => frameScans > 0);
        assert.deepEqual(await page.evaluate(() => [frameScans, downloadScans, menuScans]), [1000,1000,0]);
        await page.evaluate(() => {
            const menu = document.createElement('ul'); menu.className = 'el-dropdown-menu';
            document.body.appendChild(menu);
            for (let index = 0; index < 20; index++) {
                const item = document.createElement('li'); item.className = 'el-dropdown-menu__item'; item.textContent = '下载'; menu.appendChild(item);
            }
        });
        await page.waitForFunction(() => menuScans === 1);
        assert.equal(await page.locator('.el-dropdown-menu__item').first().evaluate(node => node.style.display), 'none');
        await page.evaluate(() => { const button = document.querySelector('button'); button.textContent = '下载'; });
        await page.waitForFunction(() => document.querySelector('button').style.display === 'none');
        console.log('PASS: 1,000 inserted cards scanned once each, no parent/list rescans, one menu scan per batch, download/text handling preserved');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
