const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync(require('path').join(__dirname,'../web/static/vue_components/ImageLayout/index.js'),'utf8');
const component=source.slice(source.indexOf('var rt={'),source.indexOf('var lt=',source.indexOf('var rt={')));
class Video extends EventTarget{
 constructor(){super();this.dataset={};this.currentTime=0;this.duration=100;this.paused=true;this.playCalls=0;this.loadCalls=0;this.readyState=4;}
 setAttribute(k,v){this[k]=v;}removeAttribute(k){delete this[k];}
 play(){this.playCalls++;this.paused=false;return this.pending?new Promise(()=>{}):Promise.resolve();}
 pause(){this.paused=true;}load(){this.loadCalls++;}remove(){this.removed=true;}
}
function setup(cached=''){
 let now=10000,id=0;const timers=new Map(),videos=[],cleanup=[],cacheCalls=[];
 const window=new EventTarget();window.PichomeCache={getCachedMediaUrl:()=>cached,cacheMedia:(...args)=>cacheCalls.push(args)};
 const ctx={ref:value=>({value}),reactive:v=>v,window,document:{createElement:()=>{const v=new Video();videos.push(v);return v;}},Vue:{onBeforeUnmount:fn=>cleanup.push(fn)},CustomEvent:class extends Event{constructor(name,opts){super(name);this.detail=opts.detail;}},Date:{now:()=>now},Number,Math,String,setTimeout:(fn,ms)=>{timers.set(++id,{at:now+ms,fn});return id;},clearTimeout:id=>timers.delete(id)};
 const tick=ms=>{const end=now+ms;while(true){const due=[...timers].filter(([,v])=>v.at<=end).sort((a,b)=>a[1].at-b[1].at)[0];if(!due)break;now=due[1].at;timers.delete(due[0]);due[1].fn();}now=end;};
 vm.createContext(ctx);vm.runInContext(component,ctx);const api=ctx.rt.setup({ImageData:{rid:'r1',mediaplayerpath:'https://media/video'},Lang:{}});
 api.DomVideoBox.value={appendChild:v=>v.parentNode=true};api.DomBox.value={getBoundingClientRect:()=>({left:0,width:100})};
 return {api,video:videos[0],tick,cleanup,cacheCalls,timers,setCached:url=>{cached=url;}};
}
const flush=async()=>{await Promise.resolve();await Promise.resolve();};
const tap=h=>{h.api.Pointerdown({pointerType:'touch',clientX:10,clientY:10});h.api.Pointerup({pointerType:'touch',clientX:10,clientY:10});};
(async()=>{
 const desktop=setup();desktop.api.Mouseenter({});desktop.tick(299);assert.equal(desktop.video.playCalls,0);desktop.tick(1);assert.equal(desktop.video.playCalls,1);await flush();desktop.video.dispatchEvent(new Event('playing'));desktop.video.currentTime=1;desktop.video.dispatchEvent(new Event('timeupdate'));desktop.tick(16000);assert.equal(desktop.video.loadCalls,1);
 const leave=setup();leave.api.Mouseenter({});leave.tick(200);leave.api.Mouseleave({});leave.tick(200);assert.equal(leave.video.playCalls,0);
 const mobile=setup('http://127.0.0.1/cache');tap(mobile);assert.equal(mobile.video.playCalls,1);assert.equal(mobile.cacheCalls.length,0);assert.equal(mobile.video.dataset.pichomeCacheRequested,'rid:r1:video|https://media/video');await flush();mobile.video.dispatchEvent(new Event('canplay'));mobile.api.Mousemove({clientX:70});assert.equal(mobile.video.currentTime,0);mobile.api.Mouseleave({});assert(mobile.api.VideoData.show);
 const stuck=setup('http://127.0.0.1/cache');stuck.video.pending=true;tap(stuck);stuck.tick(4000);assert.equal(stuck.video.src,'https://media/video');assert.equal(stuck.video.playCalls,2);stuck.tick(12000);assert(!stuck.api.VideoData.loading);assert(stuck.api.VideoData.error);stuck.video.pending=false;tap(stuck);assert.equal(stuck.video.playCalls,3);
 const reload=setup();tap(reload);reload.video.pending=true;reload.video.dispatchEvent(new Event('loadstart'));reload.video.dispatchEvent(new Event('canplay'));assert.equal(reload.video.playCalls,2);reload.video.dispatchEvent(new Event('playing'));assert(!reload.api.VideoData.loading);
 const cancel=setup();cancel.api.Pointerdown({pointerType:'touch',clientX:1,clientY:1});cancel.api.Pointercancel();cancel.api.Pointerup({pointerType:'touch',clientX:1,clientY:1});assert.equal(cancel.video.playCalls,0);
 const completedWhileWaiting=setup();completedWhileWaiting.video.pending=true;tap(completedWhileWaiting);assert.equal(completedWhileWaiting.video.src,'https://media/video');completedWhileWaiting.setCached('http://127.0.0.1/ready');tap(completedWhileWaiting);assert.equal(completedWhileWaiting.video.src,'http://127.0.0.1/ready');assert.equal(completedWhileWaiting.video.playCalls,2);assert.equal(completedWhileWaiting.cacheCalls.length,1);
 const sameCacheRetry=setup('http://127.0.0.1/ready');sameCacheRetry.video.pending=true;tap(sameCacheRetry);tap(sameCacheRetry);assert.equal(sameCacheRetry.video.playCalls,2);assert.equal(sameCacheRetry.video.loadCalls,2);
 const fakeProgress=setup('http://127.0.0.1/ready');fakeProgress.video.pending=true;tap(fakeProgress);fakeProgress.video.dispatchEvent(new Event('playing'));for(let i=0;i<4;i++){fakeProgress.tick(1000);fakeProgress.video.dispatchEvent(new Event('timeupdate'));}assert.equal(fakeProgress.video.src,'https://media/video');
 const autoCache=setup();autoCache.video.pending=true;tap(autoCache);assert.equal(autoCache.video.playCalls,1);assert.equal(autoCache.cacheCalls.length,1);autoCache.setCached('http://127.0.0.1/completed');autoCache.tick(1000);assert.equal(autoCache.video.src,'http://127.0.0.1/completed');assert.equal(autoCache.video.playCalls,2);
 const lateCache=setup();lateCache.video.pending=true;tap(lateCache);lateCache.tick(16000);assert(lateCache.api.VideoData.error);lateCache.setCached('http://127.0.0.1/completed-late');lateCache.tick(1000);assert.equal(lateCache.video.src,'http://127.0.0.1/completed-late');assert.equal(lateCache.api.VideoData.error,'');assert.equal(lateCache.video.playCalls,3);
 const streaming=setup();tap(streaming);await flush();streaming.video.currentTime=1;streaming.video.dispatchEvent(new Event('timeupdate'));streaming.setCached('http://127.0.0.1/dont-interrupt');streaming.tick(2000);assert.equal(streaming.video.src,'https://media/video');assert.equal(streaming.video.loadCalls,1);
 const abandoned=setup();abandoned.video.pending=true;tap(abandoned);abandoned.tick(1500);abandoned.api.Mouseleave({});abandoned.setCached('http://127.0.0.1/dont-restart');abandoned.tick(2000);assert.equal(abandoned.video.playCalls,1);assert.equal(abandoned.timers.size,0);
 stuck.cleanup[0]();assert.equal(stuck.timers.size,0);assert(stuck.video.removed);
 console.log('PASS: automatic cache handover (including late completion), uninterrupted streaming, stop cancels handover, 300ms hover, immediate touch, retry, reload recovery, bounded spinner, cleanup');
})().catch(e=>{console.error(e);process.exitCode=1});
