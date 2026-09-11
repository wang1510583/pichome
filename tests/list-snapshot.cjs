const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const create = require('../web/static/js/pichome-list-snapshot.js');
const storage = {value: null, getItem() { return this.value; }, setItem(key, value) { this.value = value; }, removeItem() { this.value = null; }};
let now = 1000;
const cache = create(storage, 'account-session-a', () => now);
const params = {appid: 'library', fid: 'folder', page: 1, perpage: 60, hassub: 0};
const payload = name => ({data: [{rid: name, icondata: '/cached-image.png'}], param: {page: 1, perpage: 60, total: 1}, next: false});
cache.put('list', params, payload('cached'));
assert.equal(cache.get('list', {...params}).data[0].rid, 'cached');
assert.equal(cache.get('list', {...params, fid: 'other'}), null);
assert.equal(cache.get('list', {...params, page: 2}), null);
assert.equal(cache.get('list', {...params, keyword: 'search'}), null);
assert.equal(create(storage, 'account-session-b', () => now).get('list', params), null);
assert.equal(storage.value, null);
cache.put('list', params, payload('cached'));
now += 8 * 86400000;
assert.equal(cache.get('list', params), null);
for (let index = 0; index < 6; index++) cache.put('list', {...params, fid: String(index)}, payload('item'));
assert.equal(JSON.parse(storage.value).entries.length, 4);
assert.doesNotThrow(() => create({getItem() { throw Error('blocked'); }, setItem() { throw Error('quota'); }, removeItem() {}}, 'account', () => now).put('list', params, payload('item')));

const source = fs.readFileSync(path.join(__dirname, '../web/static/vue_components/ImageLayout/index.js'), 'utf8');
const block = source.slice(source.indexOf('let b="";let snapshotSequence'), source.indexOf('let _=null;', source.indexOf('let b="";let snapshotSequence')));
function setup() {
    cache.put('list', params, payload('cached'));
    const requests = [];
    let layouts = 0;
    const context = {
        e: {isdata: false, apptype: 'pichome', url: 'list', urlparam: {appid: 'library', fid: 'folder'}, perpage: 60, hassub: 0, scrollref: {wrapRef: {scrollTop: 0}, setScrollTop(value) { this.wrapRef.scrollTop = value; }}},
        s: {value: []}, l: {value: true}, u: {page: 1}, k: {value: true}, v: {value: false}, x: {value: true}, r: {value: {Init() { layouts++; }}},
        window: {PichomeListSnapshot: {cache}}, nextTick: callback => Promise.resolve().then(callback), ImgeParamInit() {}, ReturnParam() {}, ElementPlus: {ElMessage() {}},
        axios: {CancelToken: function (callback) { callback(() => {}); }, isCancel: () => false, post: () => new Promise((resolve, reject) => requests.push({resolve, reject}))}
    };
    vm.createContext(context); vm.runInContext(block, context);
    return {context, requests, layouts: () => layouts};
}
(async () => {
    const first = setup();
    const loading = first.context.GetData();
    await Promise.resolve();
    assert.equal(first.context.s.value[0].rid, 'cached');
    assert.equal(first.context.k.value, false);
    assert.equal(first.context.v.value, true);
    assert.equal(first.layouts(), 1);
    first.context.e.scrollref.wrapRef.scrollTop = 150;
    first.requests[0].resolve({data: {data: payload('fresh')}});
    await loading; await Promise.resolve();
    assert.equal(first.context.s.value[0].rid, 'fresh');
    assert.equal(first.context.e.scrollref.wrapRef.scrollTop, 150);
    assert.equal(first.context.u.page, 2);
    assert.equal(cache.get('list', params).data[0].rid, 'fresh');
    first.context.l.value = false;
    const more = first.context.GetData();
    first.requests[1].resolve({data: {data: {...payload('second-page'), param: {page: 2, perpage: 60, total: 61}}}});
    await more;
    assert.equal(first.context.s.value.length, 2);
    assert.equal(cache.get('list', params).data[0].rid, 'fresh');
    const rejected = setup(); const pending = rejected.context.GetData();
    rejected.requests[0].reject({response: {status: 403}}); await pending;
    assert.equal(rejected.context.s.value.length, 0);
    assert.equal(cache.get('list', params), null);
    const offline = setup(); const disconnected = offline.context.GetData();
    offline.requests[0].reject(Error('offline')); await disconnected;
    assert.equal(offline.context.s.value[0].rid, 'cached');
    assert.equal(offline.context.v.value, false);
    const race = setup(); const older = race.context.GetData(); const newer = race.context.GetData();
    race.requests[1].resolve({data: {data: payload('new')}}); await newer;
    race.requests[0].resolve({data: {data: payload('old')}}); await older;
    assert.equal(race.context.s.value[0].rid, 'new');
    console.log('PASS: cached first paint before network, quiet refresh/scroll retention, pagination, account/query isolation, TTL/quota, denied-response purge, offline display and stale-response protection');
})().catch(error => { console.error(error); process.exitCode = 1; });
