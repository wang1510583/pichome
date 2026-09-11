const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
    const browser = await chromium.launch({headless: true});
    try {
        const page = await browser.newPage({viewport: {width: 400, height: 800}, isMobile: true});
        await page.route('https://snapshot.test/', route => route.fulfill({contentType: 'text/html', body: '<div id="app"></div>'}));
        await page.goto('https://snapshot.test/');
        await page.addScriptTag({path: path.join(__dirname, '../web/static/js/pichome-list-snapshot.js')});
        await page.evaluate(() => {
            PichomeListSnapshot.configure('session-a', true);
            const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="red"/></svg>');
            PichomeListSnapshot.cache.put('list', {page: 1, perpage: 60, hassub: 0, fid: 'folder'}, {data: [{rid: 'cached', icondata: image}], param: {page: 1, perpage: 60, total: 1}, next: false});
        });
        await page.reload();
        await page.addScriptTag({path: path.join(__dirname, '../web/static/vue/vue@3/vue.prod.js')});
        await page.addScriptTag({path: path.join(__dirname, '../web/static/js/pichome-list-snapshot.js')});
        await page.evaluate(() => {
            PichomeListSnapshot.configure('session-a', true);
            window.e = {isdata: false, apptype: 'pichome', url: 'list', urlparam: {fid: 'folder'}, perpage: 60, hassub: 0};
            for (const [name, value] of Object.entries({s: [], l: true, k: true, v: false, x: true})) window[name] = Vue.ref(value);
            window.u = {};
            window.r = {value: {Init() {}}};
            window.nextTick = Vue.nextTick;
            window.ImgeParamInit = window.ReturnParam = () => {};
            window.axios = {CancelToken: function (callback) { callback(() => {}); }, isCancel: () => false, post: () => new Promise(resolve => { window.finishRequest = resolve; })};
            Vue.createApp({setup: () => ({rows: window.s, loading: window.k}), template: '<div v-if="loading">Loading</div><img v-for="item in rows" :key="item.rid" :alt="item.rid" :src="item.icondata">'}).mount('#app');
        });
        const source = fs.readFileSync(path.join(__dirname, '../web/static/vue_components/ImageLayout/index.js'), 'utf8');
        const start = source.indexOf('let b="";let snapshotSequence');
        await page.addScriptTag({content: source.slice(start, source.indexOf('let _=null;', start))});
        await page.evaluate(() => { GetData(); });
        await page.locator('img[alt="cached"]').waitFor({state: 'visible'});
        assert(await page.locator('img').evaluate(image => image.complete && image.naturalWidth > 0));
        assert.equal(await page.locator('#app').innerText(), '');
        await page.evaluate(() => finishRequest({data: {data: {data: [], param: {page: 1, perpage: 60, total: 0}, next: false}}}));
        await page.waitForFunction(() => document.querySelectorAll('img').length === 0);
        console.log('PASS browser reload: persisted covers paint while list request is pending; fresh deletion replaces cached rows');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
