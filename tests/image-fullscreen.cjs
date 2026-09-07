const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const filename = path.join(__dirname, '../web/dzz/details/template/mobile/assets/js/image-fullscreen.js');
const {gesture, constrain} = require(filename);

const initial = {width: 400, height: 800, scale: 1, x: 0, y: 0};
const zoomed = gesture(initial, [{x: 100, y: 400}, {x: 300, y: 400}], [{x: 0, y: 450}, {x: 400, y: 450}]);
assert.deepEqual(zoomed, {width: 400, height: 800, scale: 2, x: -200, y: -350});
const dragged = gesture(zoomed, [{x: 200, y: 300}], [{x: 250, y: 380}]);
assert.equal(dragged.x, -150);
assert.equal(dragged.y, -270);
assert.equal(dragged.scale, 2);
assert.deepEqual(constrain({...initial, width: 200, height: 400, x: -500, y: 900}, 400, 800), {...initial, width: 200, height: 400, x: 100, y: 200});
assert.equal(constrain({...zoomed, x: 800, y: -2000}, 400, 800).y, -800);
assert.equal(gesture(initial, [{x: 0, y: 0}, {x: 100, y: 0}], [{x: 0, y: 0}, {x: 10000, y: 0}]).scale, 32);
assert.equal(gesture(initial, [{x: 0, y: 0}, {x: 100, y: 0}], [{x: 0, y: 0}, {x: 1, y: 0}]).scale, 1);

class Target {
    constructor() { this.handlers = new Map(); }
    addEventListener(name, handler) {
        if (!this.handlers.has(name)) this.handlers.set(name, new Set());
        this.handlers.get(name).add(handler);
    }
    removeEventListener(name, handler) { this.handlers.get(name)?.delete(handler); }
    fire(name, fields = {}) {
        const event = {preventDefault() { this.prevented = true; }, stopPropagation() { this.stopped = true; }, stopImmediatePropagation() { this.stopped = true; }, ...fields};
        [...(this.handlers.get(name) || [])].forEach(handler => handler(event));
        return event;
    }
}
class Element extends Target {
    constructor(doc, tag) {
        super(); this.doc = doc; this.tag = tag; this.style = {}; this.children = []; this.isConnected = true;
        this.classList = {contains: name => name === 'pichome-mirror-preview-target'};
    }
    setAttribute(name, value) { this[name] = value; }
    appendChild(child) { this.children.push(child); child.parent = this; }
    remove() { this.isConnected = false; this.parent.children = this.parent.children.filter(child => child !== this); }
    focus() { this.doc.activeElement = this; }
    setPointerCapture() {}
    getBoundingClientRect() { return {left: 0, top: 0}; }
}
function document() {
    const doc = new Target();
    doc.createElement = tag => new Element(doc, tag);
    doc.documentElement = doc.createElement('html');
    doc.documentElement.style.overflow = 'auto';
    doc.body = doc.createElement('body');
    doc.body.inert = false;
    doc.activeElement = doc.body;
    return doc;
}
const host = new Target();
host.document = document(); host.parent = host; host.innerWidth = 400; host.innerHeight = 800;
const observers = [];
host.MutationObserver = class {
    constructor(callback) { this.callback = callback; observers.push(this); }
    observe() {} disconnect() { this.disconnected = true; }
};
const child = new Target();
child.parent = host; child.document = document(); child.frameElement = {isConnected: true};
const source = child.document.createElement('img');
source.src = 'https://example.test/original.jpg';
const box = {querySelector: () => source};
vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {window: child, Map, Math, Date, Object, Array});
function open() {
    const event = child.document.fire('click', {target: {closest: () => box}});
    assert(event.prevented && event.stopped);
    const overlay = host.document.documentElement.children[0];
    assert.equal(overlay.className, 'pichome-image-fullscreen');
    assert.equal(overlay.style.width, '400px');
    assert.equal(overlay.style.height, '800px');
    assert.equal(child.document.documentElement.children.length, 0);
    const surface = overlay.children[0], stage = surface.children[0], picture = stage.children[0];
    picture.naturalWidth = 400; picture.naturalHeight = 800; picture.fire('load');
    assert.equal(picture.style.transform, 'scaleX(-1)');
    return {overlay, surface, stage};
}
const viewer = open();
assert(host.document.body.inert);
viewer.surface.fire('pointerdown', {pointerId: 1, clientX: 100, clientY: 400});
viewer.surface.fire('pointerdown', {pointerId: 2, clientX: 300, clientY: 400});
viewer.surface.fire('pointermove', {pointerId: 1, clientX: 0, clientY: 400});
viewer.surface.fire('pointermove', {pointerId: 2, clientX: 400, clientY: 400});
assert.equal(viewer.stage.style.transform, 'translate3d(-200px,-400px,0) scale(2)');
viewer.surface.fire('pointerup', {pointerId: 2});
viewer.surface.fire('pointermove', {pointerId: 1, clientX: 50, clientY: 480});
assert.equal(viewer.stage.style.transform, 'translate3d(-150px,-320px,0) scale(2)');
viewer.surface.fire('pointercancel', {pointerId: 1});
viewer.surface.fire('pointermove', {pointerId: 1, clientX: 100, clientY: 100});
assert.equal(viewer.stage.style.transform, 'translate3d(-150px,-320px,0) scale(2)');
const touch = viewer.overlay.fire('touchmove', {cancelable: true});
assert(touch.stopped && touch.prevented);
assert.equal(viewer.overlay.children.length, 1);
assert(host.__pichomeCloseImageFullscreen());
assert.equal(host.document.documentElement.children.length, 0);
assert.equal(host.document.documentElement.style.overflow, 'auto');
assert.equal(host.document.body.inert, false);
assert.equal(host.document.activeElement, host.document.body);
assert.equal(host.handlers.get('resize').size, 0);
assert(observers[0].disconnected);
open();
host.document.fire('keydown', {key: 'Escape'});
assert.equal(host.__pichomeCloseImageFullscreen, undefined);
const tapped = open();
tapped.surface.fire('pointerdown', {pointerId: 3, clientX: 200, clientY: 300});
tapped.surface.fire('pointerup', {pointerId: 3, clientX: 200, clientY: 300});
assert.equal(host.document.documentElement.children.length, 1);
const closingClick = tapped.overlay.fire('click', {detail: 1});
assert(closingClick.prevented && closingClick.stopped);
assert.equal(host.__pichomeCloseImageFullscreen, undefined);
open();
child.frameElement.isConnected = false;
observers.at(-1).callback();
assert.equal(host.document.documentElement.children.length, 0);
child.document.fire('touchstart', {touches: [{clientX: 0, clientY: 0}]});
child.document.fire('touchmove', {touches: [{clientX: 100, clientY: 0}]});
child.document.fire('click', {target: {closest: () => box}});
assert.equal(host.document.documentElement.children.length, 0);
console.log('PASS: top-level portal, viewport fit, anchored pinch, one-finger pan, bounds, tap close, gesture cancellation, mirror, swipe suppression, Escape/native-back cleanup');
