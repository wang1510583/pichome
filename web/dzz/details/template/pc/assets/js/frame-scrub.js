(function (global) {
	'use strict';

	var STYLE_ID = 'pichome-desktop-frame-scrub-style';
	var INTERACTIVE_SELECTOR = [
		'.xgplayer-controls',
		'.xgplayer-start',
		'button',
		'input',
		'select',
		'textarea',
		'a',
		'[role="button"]',
		'[contenteditable="true"]'
	].join(',');
	var MOVE_THRESHOLD = 6;
	var DEFAULT_PIXELS_PER_FRAME = 7;
	var MAX_SEGMENTS = 3;
	var MAX_ACTIVE_REQUESTS = 2;

	function finiteNumber(value, fallback) {
		var number = parseFloat(value);
		return isFinite(number) ? number : fallback;
	}

	function normalisePixelsPerFrame(value) {
		var parsed = parseInt(value, 10);
		return parsed === 14 || parsed === 3 ? 14 : DEFAULT_PIXELS_PER_FRAME;
	}

	function frameDeltaForDistance(distance, pixelsPerFrame) {
		var step = normalisePixelsPerFrame(pixelsPerFrame);
		return distance < 0
			? Math.ceil(distance / step)
			: Math.floor(distance / step);
	}

	function frameDuration(fps) {
		return 1 / Math.max(1, finiteNumber(fps, 30));
	}

	function atlasSegmentSeconds(manifest) {
		return Math.max(0.05, finiteNumber(manifest && manifest.segmentSeconds, 1));
	}

	function atlasSegmentIndex(manifest, time) {
		var maximum = Math.max(0, parseInt(manifest && manifest.maxSegment, 10) || 0);
		return Math.max(0, Math.min(
			maximum,
			Math.floor(Math.max(0, finiteNumber(time, 0)) / atlasSegmentSeconds(manifest) + 0.0000001)
		));
	}

	function atlasSegmentStart(manifest, index) {
		return Math.max(0, finiteNumber(index, 0)) * atlasSegmentSeconds(manifest);
	}

	function clampTime(video, value) {
		var duration = finiteNumber(video && video.duration, 0);
		var maximum = duration > 0
			? Math.max(0, duration - Math.min(0.001, frameDuration(60) * 0.05))
			: Math.max(0, value);
		return Math.max(0, Math.min(maximum, finiteNumber(value, 0)));
	}

	function commitSeekTime(target, fps, duration) {
		var value = target + frameDuration(fps) * 0.5;
		if (duration > 0) value = Math.min(value, Math.max(0, duration - 0.001));
		return Math.max(0, value);
	}

	function presentedFrameMatches(mediaTime, target, fps) {
		return isFinite(mediaTime)
			&& Math.abs(mediaTime - target) <= frameDuration(fps) * 0.65 + 0.003;
	}

	function installStyle(doc) {
		if (!doc || doc.getElementById(STYLE_ID)) return;
		var style = doc.createElement('style');
		style.id = STYLE_ID;
		style.textContent = [
			'.xgplayer-skin-default.pichome-desktop-frame-host{position:relative!important;}',
			'.xgplayer-skin-default.pichome-frame-touch-host{touch-action:pan-y!important;}',
			'.pichome-desktop-frame-overlay{position:absolute;display:none;pointer-events:none!important;z-index:8;max-width:none!important;max-height:none!important;}',
			'.pichome-desktop-frame-overlay.pichome-mirror-preview-target{transform:scaleX(-1)!important;transform-origin:center center!important;}',
			'.xgplayer-skin-default.pichome-desktop-frame-scrubbing{cursor:ew-resize!important;user-select:none!important;}',
			'.xgplayer-skin-default.pichome-desktop-frame-scrubbing *{cursor:ew-resize!important;}',
			'.xgplayer-skin-default .xgplayer-controls{z-index:20!important;}'
		].join('');
		(doc.head || doc.documentElement).appendChild(style);
	}

	function normaliseOptions(options) {
		options = options || {};
		return {
			rid: options.rid === undefined || options.rid === null ? '' : String(options.rid),
			path: options.path === undefined || options.path === null ? '' : String(options.path),
			endpoint: options.endpoint ? String(options.endpoint) : 'index.php?mod=details&op=fileinterface',
			profile: options.profile ? String(options.profile) : 'mobile-hq',
			pixelsPerFrame: normalisePixelsPerFrame(options.pixelsPerFrame),
			allowMouse: options.allowMouse !== false,
			allowTouch: options.allowTouch === true,
			preload: options.preload !== false,
			trackPlayback: options.trackPlayback !== false
		};
	}

	function descriptorForVideo(video, options) {
		if (!video) return null;
		var descriptor = normaliseOptions(options);
		try {
			var win = video.ownerDocument && video.ownerDocument.defaultView;
			var frameRid = '';
			var framePath = '';
			while (win && win.frameElement) {
				var frame = win.frameElement;
				var raw = frame.getAttribute('src') || frame.src || '';
				try {
					var parsed = new URL(raw, frame.ownerDocument.baseURI);
					if (!frameRid && parsed.searchParams.get('pichome_rid')) {
						frameRid = parsed.searchParams.get('pichome_rid');
					}
					if (!framePath && parsed.searchParams.get('path')) {
						framePath = parsed.searchParams.get('path');
					}
				} catch (ignore) {}
				win = frame.ownerDocument && frame.ownerDocument.defaultView;
			}
			if (frameRid && descriptor.rid && frameRid !== descriptor.rid) return null;
			if (!descriptor.rid) descriptor.rid = frameRid;
			if (!descriptor.path) descriptor.path = framePath;
		} catch (ignore) {}
		if (!descriptor.rid || !descriptor.path) return null;
		return descriptor;
	}

	function findPlayerRoot(video) {
		if (!video || !video.closest) return null;
		return video.closest('.xgplayer-skin-default')
			|| video.closest('.xgplayer')
			|| video.parentElement;
	}

	function manifestUrl(state) {
		var url = new URL(state.descriptor.endpoint, state.document.baseURI);
		url.searchParams.set('op', 'fileinterface');
		url.searchParams.set('operation', 'frameAtlasManifest');
		url.searchParams.set('profile', state.descriptor.profile);
		url.searchParams.set('path', state.descriptor.path);
		return url.href;
	}

	function segmentUrl(state, second) {
		var url = new URL(state.atlas.manifestUrl);
		url.searchParams.set('operation', 'frameAtlas');
		url.searchParams.set('revision', state.atlas.manifest.revision);
		url.searchParams.set('second', String(second));
		return url.href;
	}

	function releaseEntry(entry) {
		if (!entry) return;
		if (entry.cancel) entry.cancel();
		if (entry.image) {
			entry.image.onload = null;
			entry.image.onerror = null;
			try { entry.image.src = ''; } catch (ignore) {}
		}
		entry.image = null;
	}

	function removeQueuedEntry(atlas, entry) {
		var index = atlas.queue.indexOf(entry);
		if (index >= 0) atlas.queue.splice(index, 1);
	}

	function trimAtlas(state, allowed) {
		var atlas = state.atlas;
		var center = atlas.center;
		var ready = [];
		Object.keys(atlas.entries).forEach(function (key) {
			var entry = atlas.entries[key];
			if (!entry) return;
			if (allowed && !allowed[key]) {
				if (entry.status === 'queued') removeQueuedEntry(atlas, entry);
				releaseEntry(entry);
				delete atlas.entries[key];
				return;
			}
			if (entry.status === 'ready') ready.push(entry);
		});
		ready.sort(function (left, right) {
			var leftDistance = Math.abs(left.second - center);
			var rightDistance = Math.abs(right.second - center);
			if (leftDistance !== rightDistance) return leftDistance - rightDistance;
			return right.lastUse - left.lastUse;
		});
		for (var index = MAX_SEGMENTS; index < ready.length; index += 1) {
			var oldEntry = ready[index];
			releaseEntry(oldEntry);
			delete atlas.entries[String(oldEntry.second)];
		}
	}

	function drainAtlas(state) {
		var atlas = state.atlas;
		if (state.destroyed || !atlas.manifest) return;
		while (atlas.active < MAX_ACTIVE_REQUESTS && atlas.queue.length) {
			var entry = atlas.queue.shift();
			if (!entry || entry.status !== 'queued') continue;
			startAtlasEntry(state, entry);
		}
	}

	function startAtlasEntry(state, entry) {
		var atlas = state.atlas;
		var ImageConstructor = state.window.Image || global.Image;
		if (!ImageConstructor) {
			entry.status = 'failed';
			entry.failedAt = Date.now();
			return;
		}
		entry.status = 'loading';
		entry.cancelled = false;
		atlas.active += 1;
		var image = new ImageConstructor();
		var completed = false;
		entry.image = image;

		function complete(status) {
			if (completed) return;
			completed = true;
			entry.cancel = null;
			atlas.active = Math.max(0, atlas.active - 1);
			if (status === 'ready') {
				entry.status = 'ready';
				entry.lastUse = Date.now();
			} else if (status === 'failed') {
				entry.status = 'failed';
				entry.failedAt = Date.now();
				entry.image = null;
			} else {
				entry.status = 'cancelled';
				entry.image = null;
			}
			if (!state.destroyed) {
				trimAtlas(state);
				drainAtlas(state);
				scheduleGesturePump(state);
			}
		}

		entry.cancel = function () {
			if (completed) return;
			entry.cancelled = true;
			image.onload = null;
			image.onerror = null;
			try { image.src = ''; } catch (ignore) {}
			complete('cancelled');
		};
		image.onload = function () {
			if (completed || state.destroyed || entry.cancelled) return;
			var decoded;
			try {
				decoded = typeof image.decode === 'function' ? image.decode() : null;
			} catch (ignore) {
				decoded = null;
			}
			if (decoded && typeof decoded.then === 'function') {
				decoded.then(function () {
					if (!completed && !state.destroyed && !entry.cancelled) complete('ready');
				}).catch(function () {
					if (!completed && !state.destroyed && !entry.cancelled) {
						complete(image.naturalWidth ? 'ready' : 'failed');
					}
				});
			} else {
				complete(image.naturalWidth ? 'ready' : 'failed');
			}
		};
		image.onerror = function () {
			if (!completed) complete('failed');
		};
		image.src = segmentUrl(state, entry.second);
	}

	function requestSegment(state, second, priority) {
		var atlas = state.atlas;
		var manifest = atlas.manifest;
		if (!manifest) return null;
		second = Math.max(0, Math.min(parseInt(manifest.maxSegment, 10) || 0, parseInt(second, 10) || 0));
		var key = String(second);
		var entry = atlas.entries[key];
		if (entry) {
			if (entry.status === 'ready') {
				entry.lastUse = Date.now();
			} else if (entry.status === 'queued' && priority) {
				removeQueuedEntry(atlas, entry);
				atlas.queue.unshift(entry);
				drainAtlas(state);
			} else if ((entry.status === 'failed' || entry.status === 'cancelled')
				&& Date.now() - (entry.failedAt || 0) > 2500) {
				entry.status = 'queued';
				priority ? atlas.queue.unshift(entry) : atlas.queue.push(entry);
				drainAtlas(state);
			}
			return entry;
		}
		entry = {
			second: second,
			status: 'queued',
			lastUse: Date.now(),
			failedAt: 0,
			image: null,
			cancel: null
		};
		atlas.entries[key] = entry;
		priority ? atlas.queue.unshift(entry) : atlas.queue.push(entry);
		drainAtlas(state);
		return entry;
	}

	function warmAtlas(state, time, direction) {
		var atlas = state.atlas;
		var manifest = atlas.manifest;
		if (!manifest || state.destroyed) return;
		var center = atlasSegmentIndex(manifest, time);
		atlas.center = center;
		var allowed = {};
		[center - 1, center, center + 1].forEach(function (second) {
			if (second >= 0 && second <= manifest.maxSegment) allowed[String(second)] = true;
		});
		trimAtlas(state, allowed);
		requestSegment(state, center, true);
		var first = direction < 0 ? center - 1 : center + 1;
		var second = direction < 0 ? center + 1 : center - 1;
		if (allowed[String(first)]) requestSegment(state, first, false);
		if (allowed[String(second)]) requestSegment(state, second, false);
	}

	function ensureManifest(state) {
		var atlas = state.atlas;
		if (state.destroyed || atlas.status === 'loading' || atlas.status === 'ready') return;
		if (atlas.status === 'failed' && Date.now() - atlas.failedAt < 4000) return;
		atlas.status = 'loading';
		atlas.manifestUrl = manifestUrl(state);
		var requestOptions = { credentials: 'include', cache: 'no-store' };
		if (state.window.AbortController) {
			atlas.controller = new state.window.AbortController();
			requestOptions.signal = atlas.controller.signal;
		}
		state.window.fetch(atlas.manifestUrl, requestOptions)
			.then(function (response) {
				if (!response.ok) throw new Error('manifest ' + response.status);
				return response.json();
			})
			.then(function (manifest) {
				if (state.destroyed) return;
				if (!manifest || !manifest.success || !manifest.revision) throw new Error('invalid manifest');
				atlas.manifest = manifest;
				atlas.status = 'ready';
				state.video.dataset.pichomeAtlasFps = String(manifest.fps || '');
				if (state.preload || state.activated) warmAtlas(state, state.video.currentTime, 1);
				scheduleGesturePump(state);
			})
			.catch(function (error) {
				if (state.destroyed || (error && error.name === 'AbortError')) return;
				atlas.status = 'failed';
				atlas.failedAt = Date.now();
				atlas.error = String(error && error.message || error);
				if (state.gesture && state.gesture.active) {
					state.window.setTimeout(function () { ensureManifest(state); scheduleGesturePump(state); }, 4100);
				}
			});
	}

	function syncOverlayMirror(state) {
		if (!state.overlay) return;
		state.overlay.classList.toggle(
			'pichome-mirror-preview-target',
			state.video.classList.contains('pichome-mirror-preview-target')
		);
	}

	function syncOverlayLayout(state) {
		if (!state.overlay || state.destroyed) return;
		var rootRect = state.root.getBoundingClientRect();
		var videoRect = state.video.getBoundingClientRect();
		if (videoRect.width <= 0 || videoRect.height <= 0) return;
		var style = state.window.getComputedStyle(state.video);
		state.overlay.style.left = (videoRect.left - rootRect.left - state.root.clientLeft) + 'px';
		state.overlay.style.top = (videoRect.top - rootRect.top - state.root.clientTop) + 'px';
		state.overlay.style.width = videoRect.width + 'px';
		state.overlay.style.height = videoRect.height + 'px';
		state.overlay.style.objectFit = style.objectFit || 'contain';
		state.overlay.style.objectPosition = style.objectPosition || '50% 50%';
		syncOverlayMirror(state);
	}

	function ensureOverlay(state) {
		var manifest = state.atlas.manifest;
		if (!manifest) return null;
		if (!state.overlay) {
			var canvas = state.document.createElement('canvas');
			canvas.className = 'pichome-desktop-frame-overlay';
			canvas.setAttribute('aria-hidden', 'true');
			state.root.appendChild(canvas);
			state.overlay = canvas;
			if (state.window.ResizeObserver) {
				state.resizeObserver = new state.window.ResizeObserver(function () {
					syncOverlayLayout(state);
				});
				state.resizeObserver.observe(state.root);
				state.resizeObserver.observe(state.video);
			}
		}
		if (state.overlay.width !== manifest.cellWidth) state.overlay.width = manifest.cellWidth;
		if (state.overlay.height !== manifest.cellHeight) state.overlay.height = manifest.cellHeight;
		syncOverlayLayout(state);
		return state.overlay;
	}

	function hideOverlay(state) {
		if (state.overlay) state.overlay.style.display = 'none';
		state.previewVisible = false;
	}

	function renderAtlasFrame(state, target) {
		var atlas = state.atlas;
		var manifest = atlas.manifest;
		if (!manifest || !isFinite(target)) return 'pending';
		var duration = finiteNumber(manifest.duration, finiteNumber(state.video.duration, 0));
		target = Math.max(0, Math.min(Math.max(0, duration - 0.000001), target));
		var fps = Math.max(1, finiteNumber(manifest.fps, 30));
		var segmentSeconds = atlasSegmentSeconds(manifest);
		var second = atlasSegmentIndex(manifest, target);
		var segmentStart = atlasSegmentStart(manifest, second);
		warmAtlas(state, target, target < state.video.currentTime ? -1 : 1);
		var entry = requestSegment(state, second, true);
		if (!entry || entry.status !== 'ready' || !entry.image || !entry.image.naturalWidth) {
			if (entry && entry.status === 'failed') {
				state.window.setTimeout(function () { scheduleGesturePump(state); }, 2600);
			}
			return 'pending';
		}
		var segmentDuration = Math.min(segmentSeconds, Math.max(0, duration - segmentStart));
		var frameCount = Math.max(1, Math.min(
			parseInt(manifest.framesPerSegment, 10) || Math.ceil(fps),
			Math.ceil(segmentDuration * fps)
		));
		var index = Math.max(0, Math.min(
			frameCount - 1,
			Math.floor((target - segmentStart) * fps + 0.0001)
		));
		var overlay = ensureOverlay(state);
		if (!overlay) return 'failed';
		try {
			var context = overlay.getContext('2d', { alpha: false });
			var sourceX = (index % manifest.columns) * manifest.cellWidth;
			var sourceY = Math.floor(index / manifest.columns) * manifest.cellHeight;
			context.drawImage(
				entry.image,
				sourceX,
				sourceY,
				manifest.cellWidth,
				manifest.cellHeight,
				0,
				0,
				manifest.cellWidth,
				manifest.cellHeight
			);
			overlay.style.display = 'block';
			state.previewVisible = true;
			state.previewTime = Math.min(duration, segmentStart + index / fps);
			state.previewFps = fps;
			state.previewSecond = second;
			state.previewIndex = index;
			entry.lastUse = Date.now();
			state.video.dataset.pichomeFrameTarget = String(state.previewTime);
			state.video.pause();
			return 'ready';
		} catch (error) {
			entry.status = 'failed';
			entry.failedAt = Date.now();
			return 'failed';
		}
	}

	function cancelCommit(state, hide) {
		var commit = state.commit;
		if (commit) {
			if (commit.timer) state.window.clearTimeout(commit.timer);
			if (commit.seekTimer) state.window.clearTimeout(commit.seekTimer);
			if (commit.seeked) {
				try { state.video.removeEventListener('seeked', commit.seeked); } catch (ignore) {}
			}
			if (commit.callbackId !== undefined
				&& typeof state.video.cancelVideoFrameCallback === 'function') {
				try { state.video.cancelVideoFrameCallback(commit.callbackId); } catch (ignore) {}
			}
			state.commit = null;
			delete state.video.dataset.pichomeFrameCommitting;
		}
		if (hide) hideOverlay(state);
	}

	function commitPreview(state) {
		if (!state.previewVisible || !isFinite(state.previewTime)) return;
		cancelCommit(state, false);
		var video = state.video;
		var target = state.previewTime;
		var fps = Math.max(1, finiteNumber(state.previewFps, 30));
		var duration = finiteNumber(video.duration, finiteNumber(state.atlas.manifest && state.atlas.manifest.duration, 0));
		var commit = {
			target: target,
			fps: fps,
			callbackId: undefined,
			seeked: null,
			seekTimer: 0,
			timer: 0,
			corrections: 0
		};
		state.commit = commit;
		video.dataset.pichomeFrameCommitting = '1';
		video.pause();

		function finish(matched) {
			if (state.commit !== commit) return;
			cancelCommit(state, matched);
			video.pause();
			video.dataset.pichomeFrameTarget = String(target);
		}

		function presented(now, metadata) {
			if (state.commit !== commit) return;
			commit.callbackId = undefined;
			var mediaTime = metadata && metadata.mediaTime;
			if (presentedFrameMatches(mediaTime, target, fps)) {
				finish(true);
				return;
			}
			if (isFinite(mediaTime) && commit.corrections < 1) {
				commit.corrections += 1;
				seekTo(target + frameDuration(fps) * (mediaTime > target ? 0.2 : 0.8));
				return;
			}
			finish(false);
		}

		function requestPresentedFrame() {
			if (state.commit !== commit) return;
			if (typeof video.requestVideoFrameCallback === 'function') {
				commit.callbackId = video.requestVideoFrameCallback(presented);
			} else {
				state.window.requestAnimationFrame(function () { finish(true); });
			}
		}

		function seekTo(position) {
			if (state.commit !== commit) return;
			if (commit.seeked) {
				try { video.removeEventListener('seeked', commit.seeked); } catch (ignore) {}
			}
			if (commit.seekTimer) state.window.clearTimeout(commit.seekTimer);
			commit.seeked = function () {
				if (state.commit !== commit) return;
				try { video.removeEventListener('seeked', commit.seeked); } catch (ignore) {}
				commit.seeked = null;
				if (commit.seekTimer) state.window.clearTimeout(commit.seekTimer);
				commit.seekTimer = 0;
				requestPresentedFrame();
			};
			video.addEventListener('seeked', commit.seeked);
			try { video.currentTime = clampTime(video, position); } catch (ignore) { requestPresentedFrame(); }
			commit.seekTimer = state.window.setTimeout(function () {
				if (state.commit !== commit || !commit.seeked) return;
				try { video.removeEventListener('seeked', commit.seeked); } catch (ignore) {}
				commit.seeked = null;
				requestPresentedFrame();
			}, 160);
		}

		commit.timer = state.window.setTimeout(function () { finish(false); }, 1600);
		seekTo(commitSeekTime(target, fps, duration));
	}

	function scheduleGesturePump(state) {
		var gesture = state.gesture;
		if (!gesture || !gesture.active || gesture.raf || state.destroyed) return;
		gesture.raf = state.window.requestAnimationFrame(function () {
			gesture.raf = 0;
			if (state.gesture !== gesture || !gesture.active || state.destroyed) return;
			if (state.atlas.status !== 'ready' || !state.atlas.manifest) {
				ensureManifest(state);
				return;
			}
			var manifest = state.atlas.manifest;
			var fps = Math.max(1, finiteNumber(manifest.fps, 30));
			if (!isFinite(gesture.originFrame)) gesture.originFrame = Math.round(gesture.originTime * fps);
			var totalFrames = Math.max(1, Math.ceil(finiteNumber(manifest.duration, state.video.duration) * fps));
			var desiredDelta = gesture.desiredDelta;
			var targetFrame = Math.max(0, Math.min(totalFrames - 1, gesture.originFrame + desiredDelta));
			var result = renderAtlasFrame(state, targetFrame / fps);
			if (result === 'ready') gesture.appliedDelta = desiredDelta;
			if (gesture.desiredDelta !== desiredDelta) scheduleGesturePump(state);
		});
	}

	function isInteractiveTarget(target, root) {
		if (!target || !target.closest) return false;
		var interactive = target.closest(INTERACTIVE_SELECTOR);
		return !!(interactive && root.contains(interactive));
	}

	function activateGesture(state, gesture, event) {
		gesture.active = true;
		state.activated = true;
		gesture.originTime = state.previewVisible && isFinite(state.previewTime)
			? state.previewTime
			: finiteNumber(state.video.currentTime, 0);
		gesture.originFrame = NaN;
		gesture.appliedDelta = 0;
		cancelCommit(state, false);
		state.video.pause();
		state.root.classList.add('pichome-desktop-frame-scrubbing');
		if (event) {
			event.preventDefault();
			event.stopImmediatePropagation();
		}
		ensureManifest(state);
	}

	function finishGesture(state, event) {
		var gesture = state.gesture;
		if (!gesture) return;
		state.gesture = null;
		if (gesture.raf) state.window.cancelAnimationFrame(gesture.raf);
		state.root.classList.remove('pichome-desktop-frame-scrubbing');
		try {
			if (state.root.hasPointerCapture && state.root.hasPointerCapture(gesture.pointerId)) {
				state.root.releasePointerCapture(gesture.pointerId);
			}
		} catch (ignore) {}
		if (!gesture.active) return;
		if (event && event.cancelable) event.preventDefault();
		if (event && event.stopImmediatePropagation) event.stopImmediatePropagation();
		gesture.desiredDelta = gesture.appliedDelta;
		state.suppressClickUntil = Date.now() + 700;
		state.video.pause();
		commitPreview(state);
	}

	function installVideo(video, rawOptions) {
		var descriptor = descriptorForVideo(video, rawOptions);
		var root = findPlayerRoot(video);
		if (!descriptor || !root || !root.addEventListener) return null;
		var signature = descriptor.rid + '|' + descriptor.path + '|' + descriptor.endpoint + '|' + descriptor.profile;
		var previous = video.__pichomeDesktopFrameScrub;
		if (previous && previous.signature === signature && previous.root === root && !previous.destroyed) {
			previous.pixelsPerFrame = descriptor.pixelsPerFrame;
			previous.allowMouse = descriptor.allowMouse;
			previous.allowTouch = descriptor.allowTouch;
			previous.preload = descriptor.preload;
			previous.trackPlayback = descriptor.trackPlayback;
			previous.descriptor.pixelsPerFrame = descriptor.pixelsPerFrame;
			previous.descriptor.allowMouse = descriptor.allowMouse;
			previous.descriptor.allowTouch = descriptor.allowTouch;
			previous.descriptor.preload = descriptor.preload;
			previous.descriptor.trackPlayback = descriptor.trackPlayback;
			previous.root.classList.toggle('pichome-frame-touch-host', descriptor.allowTouch);
			return previous;
		}
		if (previous && previous.destroy) previous.destroy();
		var rootState = root.__pichomeDesktopFrameScrubState;
		if (rootState && rootState.video !== video && rootState.destroy) rootState.destroy();

		var doc = video.ownerDocument;
		var win = doc.defaultView;
		if (!win || typeof win.fetch !== 'function') return null;
		installStyle(doc);
		root.classList.add('pichome-desktop-frame-host');
		root.classList.toggle('pichome-frame-touch-host', descriptor.allowTouch);
		var state = {
			signature: signature,
			descriptor: descriptor,
			video: video,
			root: root,
			document: doc,
			window: win,
			pixelsPerFrame: descriptor.pixelsPerFrame,
			allowMouse: descriptor.allowMouse,
			allowTouch: descriptor.allowTouch,
			preload: descriptor.preload,
			trackPlayback: descriptor.trackPlayback,
			activated: false,
			destroyed: false,
			gesture: null,
			commit: null,
			overlay: null,
			resizeObserver: null,
			mirrorObserver: null,
			previewVisible: false,
			previewTime: NaN,
			previewFps: 0,
			suppressClickUntil: 0,
			atlas: {
				status: 'idle',
				failedAt: 0,
				error: '',
				manifestUrl: '',
				manifest: null,
				entries: {},
				queue: [],
				active: 0,
				center: -1,
				controller: null
			}
		};

		function pointerDown(event) {
			if (state.destroyed || state.gesture || video.readyState < 1) return;
			var pointerType = event.pointerType || 'mouse';
			if (pointerType === 'mouse' && (!state.allowMouse || event.button !== 0)) return;
			if (pointerType === 'touch' && (!state.allowTouch || event.isPrimary === false)) return;
			if (pointerType !== 'mouse' && pointerType !== 'touch') return;
			if (isInteractiveTarget(event.target, root)) return;
			state.gesture = {
				pointerId: event.pointerId,
				pointerType: pointerType,
				startX: event.clientX,
				startY: event.clientY,
				active: false,
				originTime: NaN,
				originFrame: NaN,
				desiredDelta: 0,
				appliedDelta: 0,
				raf: 0
			};
			try { root.setPointerCapture(event.pointerId); } catch (ignore) {}
		}

		function pointerMove(event) {
			var gesture = state.gesture;
			if (!gesture || event.pointerId !== gesture.pointerId) return;
			var distanceX = event.clientX - gesture.startX;
			var distanceY = event.clientY - gesture.startY;
			if (!gesture.active) {
				var baseThreshold = gesture.pointerType === 'touch' ? 10 : MOVE_THRESHOLD;
				var moveThreshold = Math.min(baseThreshold, state.pixelsPerFrame);
				if (Math.abs(distanceY) >= moveThreshold && Math.abs(distanceY) > Math.abs(distanceX)) {
					finishGesture(state, null);
					return;
				}
				if (Math.abs(distanceX) < moveThreshold || Math.abs(distanceX) < Math.abs(distanceY) * 1.05) return;
				activateGesture(state, gesture, event);
			}
			event.preventDefault();
			event.stopImmediatePropagation();
			var desiredDelta = frameDeltaForDistance(distanceX, state.pixelsPerFrame);
			if (desiredDelta === gesture.desiredDelta) return;
			gesture.desiredDelta = desiredDelta;
			scheduleGesturePump(state);
		}

		function pointerUp(event) {
			if (!state.gesture || event.pointerId !== state.gesture.pointerId) return;
			finishGesture(state, event);
		}

		function pointerCancel(event) {
			if (!state.gesture || (event.pointerId !== undefined && event.pointerId !== state.gesture.pointerId)) return;
			finishGesture(state, event);
		}

		function suppressClick(event) {
			if (Date.now() >= state.suppressClickUntil) return;
			event.preventDefault();
			event.stopImmediatePropagation();
		}

		function warmCurrent() {
			if (state.destroyed || !state.atlas.manifest || (state.gesture && state.gesture.active)) return;
			if (!state.trackPlayback) return;
			if (!state.preload && !state.activated) return;
			warmAtlas(state, video.currentTime, 1);
		}

		function hideForPlayback() {
			if (state.destroyed) return;
			cancelCommit(state, true);
		}

		function hideForExternalSeek() {
			if (state.destroyed || state.commit || (state.gesture && state.gesture.active)) return;
			hideOverlay(state);
		}

		function handleVisibilityChange() {
			if (doc.hidden) pointerCancel({});
		}

		root.addEventListener('pointerdown', pointerDown, true);
		root.addEventListener('pointermove', pointerMove, { capture: true, passive: false });
		root.addEventListener('pointerup', pointerUp, true);
		root.addEventListener('pointercancel', pointerCancel, true);
		root.addEventListener('lostpointercapture', pointerCancel, true);
		root.addEventListener('click', suppressClick, true);
		video.addEventListener('timeupdate', warmCurrent);
		video.addEventListener('seeked', warmCurrent);
		video.addEventListener('seeking', hideForExternalSeek);
		video.addEventListener('playing', hideForPlayback);
		win.addEventListener('blur', pointerCancel);
		doc.addEventListener('visibilitychange', handleVisibilityChange);
		if (win.MutationObserver) {
			state.mirrorObserver = new win.MutationObserver(function () { syncOverlayMirror(state); });
			state.mirrorObserver.observe(video, { attributes: true, attributeFilter: ['class'] });
		}

		state.destroy = function () {
			if (state.destroyed) return;
			state.destroyed = true;
			if (state.gesture && state.gesture.raf) win.cancelAnimationFrame(state.gesture.raf);
			state.gesture = null;
			cancelCommit(state, false);
			if (state.atlas.controller) {
				try { state.atlas.controller.abort(); } catch (ignore) {}
			}
			Object.keys(state.atlas.entries).forEach(function (key) {
				releaseEntry(state.atlas.entries[key]);
			});
			state.atlas.entries = {};
			state.atlas.queue = [];
			root.removeEventListener('pointerdown', pointerDown, true);
			root.removeEventListener('pointermove', pointerMove, true);
			root.removeEventListener('pointerup', pointerUp, true);
			root.removeEventListener('pointercancel', pointerCancel, true);
			root.removeEventListener('lostpointercapture', pointerCancel, true);
			root.removeEventListener('click', suppressClick, true);
			video.removeEventListener('timeupdate', warmCurrent);
			video.removeEventListener('seeked', warmCurrent);
			video.removeEventListener('seeking', hideForExternalSeek);
			video.removeEventListener('playing', hideForPlayback);
			win.removeEventListener('blur', pointerCancel);
			doc.removeEventListener('visibilitychange', handleVisibilityChange);
			if (state.resizeObserver) state.resizeObserver.disconnect();
			if (state.mirrorObserver) state.mirrorObserver.disconnect();
			if (state.overlay && state.overlay.parentNode) state.overlay.parentNode.removeChild(state.overlay);
			root.classList.remove('pichome-desktop-frame-host', 'pichome-desktop-frame-scrubbing', 'pichome-frame-touch-host');
			if (root.__pichomeDesktopFrameScrubState === state) delete root.__pichomeDesktopFrameScrubState;
			if (video.__pichomeDesktopFrameScrub === state) delete video.__pichomeDesktopFrameScrub;
		};

		root.__pichomeDesktopFrameScrubState = state;
		video.__pichomeDesktopFrameScrub = state;
		ensureManifest(state);
		return state;
	}

	function scanDocument(doc, options) {
		if (!doc || !doc.querySelectorAll) return;
		doc.querySelectorAll('video').forEach(function (video) {
			installVideo(video, options);
		});
	}

	function installDocument(doc, rawOptions) {
		if (!doc || !doc.documentElement) return;
		var options = normaliseOptions(rawOptions);
		var documentState = doc.__pichomeDesktopFrameScrubDocument;
		if (!documentState) {
			documentState = { options: options, observer: null, timer: 0 };
			doc.__pichomeDesktopFrameScrubDocument = documentState;
			var win = doc.defaultView;
			if (win && win.MutationObserver) {
				documentState.observer = new win.MutationObserver(function () {
					if (documentState.timer) win.clearTimeout(documentState.timer);
					documentState.timer = win.setTimeout(function () {
						documentState.timer = 0;
						scanDocument(doc, documentState.options);
					}, 40);
				});
				documentState.observer.observe(doc.documentElement, { childList: true, subtree: true });
			}
		} else {
			documentState.options = options;
		}
		scanDocument(doc, options);
	}

	function setPixelsPerFrame(doc, value) {
		var pixelsPerFrame = normalisePixelsPerFrame(value);
		if (!doc || !doc.querySelectorAll) return pixelsPerFrame;
		var documentState = doc.__pichomeDesktopFrameScrubDocument;
		if (documentState && documentState.options) {
			documentState.options.pixelsPerFrame = pixelsPerFrame;
		}
		doc.querySelectorAll('video').forEach(function (video) {
			var state = video.__pichomeDesktopFrameScrub;
			if (!state || state.destroyed) return;
			state.pixelsPerFrame = pixelsPerFrame;
			state.descriptor.pixelsPerFrame = pixelsPerFrame;
		});
		doc.querySelectorAll('iframe').forEach(function (frame) {
			try { setPixelsPerFrame(frame.contentDocument, pixelsPerFrame); } catch (ignore) {}
		});
		return pixelsPerFrame;
	}

	var api = {
		installDocument: installDocument,
		installVideo: installVideo,
		setPixelsPerFrame: setPixelsPerFrame,
		__test: {
			frameDeltaForDistance: frameDeltaForDistance,
			normalisePixelsPerFrame: normalisePixelsPerFrame,
			frameDuration: frameDuration,
			atlasSegmentSeconds: atlasSegmentSeconds,
			atlasSegmentIndex: atlasSegmentIndex,
			atlasSegmentStart: atlasSegmentStart,
			commitSeekTime: commitSeekTime,
			presentedFrameMatches: presentedFrameMatches
		}
	};

	global.PichomeDesktopFrameScrub = api;
	global.PichomeFrameScrub = api;
	if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : (typeof global !== 'undefined' ? global : this));
