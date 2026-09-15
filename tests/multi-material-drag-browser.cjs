const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const template = fs.readFileSync(path.join(__dirname, '../web/dzz/pichome/template/librarylist/pc/page/index.htm'), 'utf8');
const dragCode = template.slice(template.indexOf("const PICHOME_DRAG_MIME"), template.indexOf('// ==== 素材卡片拖拽 END'));
const bundle = fs.readFileSync(path.join(__dirname, '../web/static/vue_components/ImageLayout/index.js'), 'utf8');
const selection = bundle.slice(bundle.indexOf('MouseDown:function MouseDown(') + 'MouseDown:'.length, bundle.indexOf(',MouseDownFnc:', bundle.indexOf('MouseDown:function MouseDown(')));
(async () => {
    const browser = await chromium.launch({headless:true});
    try {
        const page = await browser.newPage({viewport:{width:1000,height:700}});
        await page.setContent('<aside id="pichome-left-sidebar" style="position:absolute;left:0;top:0;width:200px;height:600px"><div class="el-tree-node" data-key="destination" style="height:300px">目标文件夹</div></aside><main style="margin-left:240px"><div class="cannot-select image-item" data-rid="1" style="height:100px">素材1</div><div class="cannot-select image-item" data-rid="2" style="height:100px">素材2</div><div class="cannot-select image-item" data-rid="3" style="height:100px">素材3</div></main>');
        await page.addScriptTag({path:path.join(__dirname, '../web/static/vue/vue@3/vue.prod.js')});
        await page.evaluate(({dragCode, selection}) => {
            const B = Vue.ref([]), C = Vue.ref(false), f = Vue.ref({Init(){}});
            const e = {props:{checked:true,click:true,drap:true},nodekey:'rid'};
            const u = {StoreImgdatas:[{rid:'1'},{rid:'2'},{rid:'3'}]};
            let d = false, m = false, p = '';
            function ChangeKeyShiftstart(value) { p = value; }
            function EmpytCheckedKeys() { B.value = []; }
            const self = {
                ImageParam:{checkedKdys:[]}, ImageDataParam:{data:[]}, LeftTree:{Currentkey:'source'},
                $refs:{RefImageLayout:{get CheckedKeys(){return B.value;},set CheckedKeys(value){B.value=value;},restart(keep){window.keptPosition=keep;}}},
                Imagedragselect({CheckedKeys}){this.ImageParam.checkedKdys=CheckedKeys;},
                $message(){}, LeftTreeGetFolderNum(){}, LeftGetAllNum(){}
            };
            const t = {emit(name, payload){if(name==='dragselect')self.Imagedragselect(payload);}};
            const MouseDown = eval('(' + selection + ')');
            document.querySelector('main').addEventListener('mousedown', event => {d=event.ctrlKey;m=event.shiftKey;MouseDown(event);});
            window.requests = [];
            const axios = {post:async (url, payload) => {requests.push(payload);return {data:{success:true}};}};
            const MOD_URL = 'index.php?mod=pichome';
            eval(dragCode);
            window.getSelected = () => B.value.slice();
            window.setSelected = value => {B.value=value;self.ImageParam.checkedKdys=value.slice();};
            window.setStaleSelection = () => {B.value=[];self.ImageParam.checkedKdys=['1','2'];};
        }, {dragCode, selection});
        const first = page.locator('[data-rid="1"]');
        const second = page.locator('[data-rid="2"]');
        const target = page.locator('[data-key="destination"]');
        await first.click();
        await second.click({modifiers:['Control']});
        assert.deepEqual(await page.evaluate(() => getSelected()), ['1','2']);
        await first.dragTo(target);
        await page.waitForFunction(() => requests.length === 1);
        assert.equal(await page.evaluate(() => requests[0].rids), '1,2');
        assert.equal(await page.evaluate(() => keptPosition), true);
        await page.evaluate(() => setSelected(['1','2']));
        await page.keyboard.down('Control');
        await second.dragTo(target);
        await page.keyboard.up('Control');
        await page.waitForFunction(() => requests.length === 2);
        assert.equal(await page.evaluate(() => requests[1].rids), '1,2');
        await page.evaluate(() => setSelected([1,2,'offscreen']));
        await first.dragTo(target);
        await page.waitForFunction(() => requests.length === 3);
        assert.equal(await page.evaluate(() => requests[2].rids), '1,2,offscreen');
        await page.evaluate(() => setSelected(['1','2']));
        await page.locator('[data-rid="3"]').dragTo(target);
        await page.waitForFunction(() => requests.length === 4);
        assert.equal(await page.evaluate(() => requests[3].rids), '3');
        await page.evaluate(() => setStaleSelection());
        await first.dragTo(target);
        await page.waitForFunction(() => requests.length === 5);
        assert.equal(await page.evaluate(() => requests[4].rids), '1');
        await page.evaluate(() => setSelected(['1','2']));
        await second.click({modifiers:['Control']});
        assert.deepEqual(await page.evaluate(() => getSelected()), ['1']);
        assert.equal(await page.evaluate(() => requests.length), 5);
        console.log('PASS native drag/drop: Ctrl multi-selection with/without Ctrl held, offscreen/numeric IDs, unselected single drag, stale selection rejection, ordinary Ctrl deselection, one move request and scroll retention');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
