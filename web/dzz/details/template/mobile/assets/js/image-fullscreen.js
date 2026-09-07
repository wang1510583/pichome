(function (root) {
    'use strict';

    function constrain(state, width, height) {
        var scaledWidth = state.width * state.scale;
        var scaledHeight = state.height * state.scale;
        state.x = scaledWidth <= width ? (width - scaledWidth) / 2 : Math.max(width - scaledWidth, Math.min(0, state.x));
        state.y = scaledHeight <= height ? (height - scaledHeight) / 2 : Math.max(height - scaledHeight, Math.min(0, state.y));
        return state;
    }

    function gesture(state, start, current) {
        function center(points) {
            return points.length > 1 ? {x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2} : points[0];
        }
        function distance(points) {
            return Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y);
        }
        var before = center(start);
        var after = center(current);
        var scale = start.length > 1 ? Math.max(1, Math.min(32, state.scale * distance(current) / Math.max(1, distance(start)))) : state.scale;
        var ratio = scale / state.scale;
        return {width: state.width, height: state.height, scale: scale, x: after.x - (before.x - state.x) * ratio, y: after.y - (before.y - state.y) * ratio};
    }

    var nonImageExts = ['mp4', 'webm', 'mov', 'm4v', 'mkv', 'avi', 'wmv', 'flv', '3gp', 'mp3', 'wav', 'flac', 'aac', 'ogg', 'm4a', 'pdf', 'zip', 'rar', '7z', 'tar', 'gz', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt'];
    var imageExts = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'svg', 'ico', 'tif', 'tiff', 'heic', 'avif'];
    function isImagePayload(item) {
        if (!item || typeof item !== 'object') return false;
        if (item.iniframe) return false;
        var opentype = (item.opentype || item.type || '').toLowerCase();
        if (opentype && (opentype.indexOf('image') >= 0 || opentype === 'other')) return true;
        var ext = (item.ext || '').toLowerCase();
        if (ext && imageExts.indexOf(ext) >= 0) return true;
        return false;
    }
    function isDefiniteNonImage(entry) {
        if (!entry || typeof entry !== 'object') return false;
        var opentype = (entry.opentype || entry.type || '').toLowerCase();
        if (opentype) {
            if (opentype.indexOf('video') >= 0 || opentype.indexOf('audio') >= 0 || opentype === 'document' || opentype === 'attachment') return true;
            if (opentype.indexOf('image') >= 0 || opentype === 'other') return false;
        }
        if (entry.iniframe) return true;
        var ext = (entry.ext || '').toLowerCase();
        if (ext) {
            if (nonImageExts.indexOf(ext) >= 0) return true;
            if (imageExts.indexOf(ext) >= 0) return false;
        }
        return false;
    }

    if (typeof module === 'object' && module.exports) module.exports = {constrain: constrain, gesture: gesture, isDefiniteNonImage: isDefiniteNonImage, isImagePayload: isImagePayload};
    if (!root || !root.document) return;

    var galleryEntries = [];
    var galleryPaths = [];
    var entryMap = Object.create(null);
    var resourceCache = Object.create(null);
    var navigating = false;
    function detailVm() {
        if (root.__pichomeImageDetail) return root.__pichomeImageDetail;
        var element = root.document.querySelector('#dzzoffice');
        var app = element && element.__vue_app__;
        if (app && app._instance && app._instance.proxy) return app._instance.proxy;
        var popup = root.document.querySelector('#DetailPopup');
        var component = popup && popup.__vueParentComponent;
        return component && (component.proxy || component.ctx);
    }
    async function navigate(direction, active) {
        var detail = detailVm();
        if (!detail || navigating) return null;
        try {
            var parentApp = root.parent.dzzoffice;
            var parentVm = parentApp && parentApp.WindowThis;
            var entries = parentVm && parentVm.ImageDataParam && parentVm.ImageDataParam.data;
            if (entries && entries.length) {
                galleryEntries = entries.slice();
                galleryPaths = [];
                for (var i = 0; i < entries.length; i++) {
                    var item = entries[i];
                    if (item && item.dpath) {
                        galleryPaths.push(item.dpath);
                        entryMap[item.dpath] = item;
                    }
                }
            }
        } catch (ignore) {}
        if (!galleryPaths.length) galleryPaths = (detail.paths || []).slice();
        var index = galleryPaths.indexOf(detail.path);
        if (index < 0) return null;
        navigating = true;
        try {
            for (index += direction; index >= 0 && index < galleryPaths.length; index += direction) {
                var path = galleryPaths[index];
                if (resourceCache[path] === false) continue;
                var entry = entryMap[path] || (galleryEntries[index] && galleryEntries[index].dpath === path ? galleryEntries[index] : null);
                if (isDefiniteNonImage(entry)) {
                    resourceCache[path] = false;
                    continue;
                }
                var resourcesdata = resourceCache[path];
                if (!resourcesdata) {
                    var response = await root.axios.post(root.MOD_URL + '&operation=fetch', {path: path, isadmin: detail.isadmin});
                    if (!active()) return null;
                    var result = response && response.data;
                    if (!result || result.error || !result.resourcesdata) {
                        resourceCache[path] = false;
                        continue;
                    }
                    if (!isImagePayload(result.resourcesdata)) {
                        resourceCache[path] = false;
                        continue;
                    }
                    resourcesdata = result.resourcesdata;
                    resourceCache[path] = resourcesdata;
                }
                detail.paths = [path];
                detail.ridIndex = 0;
                detail.path = path;
                await detail.CommonOrder(resourcesdata);
                await detail.$nextTick();
                return root.document.querySelector('.detail-popup-content.center .img-box img.max');
            }
        } catch (error) {
            root.console.warn('Image navigation failed', error);
        } finally {
            navigating = false;
        }
        return null;
    }

    function open(source) {
        var host = root;
        var frames = [];
        try {
            while (host.parent !== host && host.parent.document) {
                frames.push(host.frameElement);
                host = host.parent;
            }
        } catch (ignore) {}
        if (host.__pichomeCloseImageFullscreen) host.__pichomeCloseImageFullscreen();
        var doc = host.document;
        var previousFocus = doc.activeElement;
        var overlay = doc.createElement('div');
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', '图片全屏预览');
        overlay.className = 'pichome-image-fullscreen';
        overlay.style.cssText = 'position:fixed;z-index:2147483647;background:#000;color:#fff;overflow:hidden;touch-action:none;overscroll-behavior:none;user-select:none;';
        var surface = doc.createElement('div');
        surface.style.cssText = 'position:absolute;inset:0;touch-action:none;overflow:hidden;';
        var stage = doc.createElement('div');
        stage.style.cssText = 'position:absolute;left:0;top:0;transform-origin:0 0;will-change:transform;pointer-events:none;';
        var picture = doc.createElement('img');
        picture.alt = source.alt || '';
        picture.draggable = false;
        picture.style.cssText = 'display:block;width:100%;height:100%;max-width:none;max-height:none;object-fit:contain;pointer-events:none;';
        if (source.classList.contains('pichome-mirror-preview-target')) picture.style.transform = 'scaleX(-1)';
        stage.appendChild(picture);
        surface.appendChild(stage);
        overlay.appendChild(surface);
        overlay.tabIndex = 0;
        overlay.setAttribute('aria-label', '图片全屏预览，轻点关闭，左右滑动切换图片');
        doc.documentElement.appendChild(overlay);
        var oldOverflow = doc.documentElement.style.overflow;
        doc.documentElement.style.overflow = 'hidden';
        var oldInert = doc.body.inert;
        doc.body.inert = true;
        var listeners = [];
        var pointers = new Map();
        var baseline;
        var startPoints;
        var width = 1;
        var height = 1;
        var state = {width: 1, height: 1, scale: 1, x: 0, y: 0};
        var closed = false;
        var switching = false;
        var press = null;
        var tapToClose = false;
        async function turn(direction) {
            if (switching || closed) return;
            switching = true;
            var next = await navigate(direction, function () { return !closed && frames.every(function (frame) { return !frame || frame.isConnected; }); });
            if (next && !closed) {
                source = next;
                picture.style.transform = source.classList.contains('pichome-mirror-preview-target') ? 'scaleX(-1)' : '';
                picture.src = source.currentSrc || source.src;
                fit();
            }
            switching = false;
            if (!closed && !source.isConnected) close();
        }
        function listen(target, name, handler, options) {
            target.addEventListener(name, handler, options);
            listeners.push(function () { target.removeEventListener(name, handler, options); });
        }
        function render() {
            constrain(state, width, height);
            stage.style.width = state.width + 'px';
            stage.style.height = state.height + 'px';
            stage.style.transform = 'translate3d(' + state.x + 'px,' + state.y + 'px,0) scale(' + state.scale + ')';
        }
        function rebase() {
            baseline = Object.assign({}, state);
            startPoints = Array.from(pointers.values());
        }
        function fit() {
            if (!picture.naturalWidth) return;
            var ratio = Math.min(width / picture.naturalWidth, height / picture.naturalHeight);
            state = {width: picture.naturalWidth * ratio, height: picture.naturalHeight * ratio, scale: 1, x: 0, y: 0};
            render();
            rebase();
        }
        function resize() {
            var viewport = host.visualViewport;
            width = viewport ? viewport.width : host.innerWidth;
            height = viewport ? viewport.height : host.innerHeight;
            overlay.style.left = (viewport ? viewport.offsetLeft : 0) + 'px';
            overlay.style.top = (viewport ? viewport.offsetTop : 0) + 'px';
            overlay.style.width = width + 'px';
            overlay.style.height = height + 'px';
            fit();
        }
        function close() {
            if (closed) return false;
            closed = true;
            observer.disconnect();
            listeners.forEach(function (remove) { remove(); });
            pointers.clear();
            overlay.remove();
            doc.documentElement.style.overflow = oldOverflow;
            doc.body.inert = oldInert;
            if (host.__pichomeCloseImageFullscreen === close) delete host.__pichomeCloseImageFullscreen;
            if (previousFocus && previousFocus.isConnected) previousFocus.focus({preventScroll: true});
            return true;
        }
        var observer = new host.MutationObserver(function () {
            if ((!switching && !source.isConnected) || frames.some(function (frame) { return frame && !frame.isConnected; })) close();
        });
        observer.observe(doc.documentElement, {childList: true, subtree: true});
        if (doc !== root.document) observer.observe(root.document.documentElement, {childList: true, subtree: true});
        host.__pichomeCloseImageFullscreen = close;
        listen(root, 'pagehide', close);
        listen(overlay, 'click', function (event) {
            event.preventDefault();
            event.stopImmediatePropagation();
            if (tapToClose || event.detail === 0) close();
        }, true);
        listen(host, 'resize', resize);
        if (host.visualViewport) {
            listen(host.visualViewport, 'resize', resize);
            listen(host.visualViewport, 'scroll', resize);
        }
        listen(doc, 'keydown', function (event) {
            if (event.key === 'Escape') { event.preventDefault(); event.stopImmediatePropagation(); close(); }
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); close(); }
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') { event.preventDefault(); turn(event.key === 'ArrowLeft' ? -1 : 1); }
            if (event.key === 'Tab') {
                event.preventDefault();
                overlay.focus();
            }
        }, true);
        ['touchstart', 'touchmove', 'touchend', 'touchcancel'].forEach(function (name) {
            listen(overlay, name, function (event) {
                event.stopPropagation();
                if (name === 'touchmove' && event.cancelable) event.preventDefault();
            }, {passive: false});
        });
        function point(event) {
            var bounds = overlay.getBoundingClientRect();
            return {x: event.clientX - bounds.left, y: event.clientY - bounds.top};
        }
        listen(surface, 'pointerdown', function (event) {
            if (event.button > 0 || pointers.size >= 2) return;
            tapToClose = false;
            event.preventDefault();
            surface.setPointerCapture(event.pointerId);
            pointers.set(event.pointerId, point(event));
            if (pointers.size === 1) press = {point: point(event), moved: false, multiple: false, state: Object.assign({}, state)};
            else if (press) press.multiple = true;
            rebase();
        });
        listen(surface, 'pointermove', function (event) {
            if (!pointers.has(event.pointerId)) return;
            event.preventDefault();
            pointers.set(event.pointerId, point(event));
            if (press && Math.hypot(point(event).x - press.point.x, point(event).y - press.point.y) > 8) press.moved = true;
            state = gesture(baseline, startPoints, Array.from(pointers.values()));
            render();
        });
        ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (name) {
            listen(surface, name, function (event) {
                if (!pointers.delete(event.pointerId)) return;
                if (name === 'pointerup' && pointers.size === 0 && press && !press.multiple) {
                    var deltaX = point(event).x - press.point.x;
                    var deltaY = point(event).y - press.point.y;
                    if (!press.moved && Math.hypot(deltaX, deltaY) <= 8) tapToClose = true;
                    else if (Math.abs(deltaX) >= 70 && Math.abs(deltaX) > Math.abs(deltaY) * 1.5) {
                        var initial = press.state;
                        var atEdge = deltaX > 0 ? initial.x >= -1 : initial.x + initial.width * initial.scale <= width + 1;
                        if (initial.scale <= 1.01 || atEdge) turn(deltaX > 0 ? -1 : 1);
                    }
                }
                if (name !== 'pointerup' && press) press.multiple = true;
                if (!pointers.size) press = null;
                rebase();
            });
        });
        listen(picture, 'load', function () {
            fit();
        });
        listen(picture, 'error', function () { overlay.setAttribute('aria-label', '图片加载失败，轻点关闭'); });
        resize();
        picture.src = source.currentSrc || source.src;
        overlay.focus({preventScroll: true});
    }

    var touchOrigin = null;
    var suppressClickUntil = 0;
    var inlinePress = null;
    root.document.addEventListener('touchstart', function (event) {
        touchOrigin = {x: event.touches[0].clientX, y: event.touches[0].clientY};
        if (event.touches.length > 1) suppressClickUntil = Date.now() + 700;
        var box = event.target && event.target.closest && event.target.closest('.detail-popup-content.center .img-box');
        if (!box && event.target && event.target.closest && event.target.closest('.detail-popup-translate')) box = root.document.querySelector('.detail-popup-content.center .img-box');
        if (box) {
            if (!inlinePress) inlinePress = {point: touchOrigin, multiple: false};
            if (event.touches.length > 1) inlinePress.multiple = true;
            event.stopImmediatePropagation();
        }
    }, {capture: true, passive: true});
    root.document.addEventListener('touchmove', function (event) {
        if (touchOrigin && Math.hypot(event.touches[0].clientX - touchOrigin.x, event.touches[0].clientY - touchOrigin.y) > 8) suppressClickUntil = Date.now() + 700;
        if (inlinePress) { event.stopImmediatePropagation(); if (event.cancelable) event.preventDefault(); }
    }, {capture: true, passive: false});
    ['touchend', 'touchcancel'].forEach(function (name) {
        root.document.addEventListener(name, function (event) {
            if (!inlinePress) return;
            event.stopImmediatePropagation();
            if (event.touches.length) return;
            var press = inlinePress;
            inlinePress = null;
            if (name === 'touchcancel' || press.multiple) return;
            var last = event.changedTouches[0];
            var deltaX = last.clientX - press.point.x;
            var deltaY = last.clientY - press.point.y;
            if (Math.abs(deltaX) >= 70 && Math.abs(deltaX) > Math.abs(deltaY) * 1.5) {
                suppressClickUntil = Date.now() + 700;
                navigate(deltaX > 0 ? -1 : 1, function () { return !root.frameElement || root.frameElement.isConnected; });
            }
        }, {capture: true, passive: true});
    });
    root.document.addEventListener('click', function (event) {
        var box = event.target.closest && event.target.closest('.detail-popup-content.center .img-box');
        if (!box) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (Date.now() < suppressClickUntil) return;
        var source = box.querySelector('img.max') || box.querySelector('img');
        if (source && source.src) open(source);
    }, true);
})(typeof window === 'undefined' ? null : window);
