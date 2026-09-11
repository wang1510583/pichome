(function () {
    'use strict';
    window.PichomeFastScroll = function (scroller, enabled) {
        if (!scroller) return function () {};
        var rail = document.createElement('div');
        rail.className = 'pichome-fast-scroll';
        rail.setAttribute('role', 'slider');
        rail.setAttribute('aria-label', '上下拖动浏览素材，松手滑块回中');
        rail.setAttribute('aria-orientation', 'vertical');
        rail.setAttribute('aria-valuemin', '-100');
        rail.setAttribute('aria-valuemax', '100');
        rail.tabIndex = 0;
        rail.innerHTML = '<span class="pichome-fast-scroll-track"></span><span class="pichome-fast-scroll-thumb"></span>';
        scroller.parentElement.parentElement.appendChild(rail);
        var thumb = rail.lastElementChild;
        var pointer = null, offset = 0, ratio = 0.5, startTop = 0, frame = 0, disposed = false;
        function maximum() { return Math.max(0, scroller.scrollHeight - scroller.clientHeight); }
        function scrollFromCenter() {
            var origin = Math.min(startTop, maximum());
            var distance = (ratio - 0.5) * 2;
            scroller.scrollTop = origin + distance * (distance < 0 ? origin : maximum() - origin);
        }
        function render() {
            frame = 0;
            if (disposed) return;
            rail.hidden = !enabled() || maximum() <= 1 || scroller.clientHeight === 0;
            if (rail.hidden) { finish(); return; }
            var height = rail.clientHeight;
            var size = Math.min(height, Math.max(44, height * scroller.clientHeight / scroller.scrollHeight));
            thumb.style.height = size + 'px';
            if (pointer !== null) scrollFromCenter();
            else ratio = 0.5;
            thumb.style.transform = 'translateY(' + ratio * (height - size) + 'px)';
            rail.setAttribute('aria-valuenow', String(Math.round((ratio - 0.5) * 200)));
        }
        function schedule() { if (!disposed && !frame) frame = requestAnimationFrame(render); }
        function move(event) {
            if (event.pointerId !== pointer) return;
            event.preventDefault();
            event.stopPropagation();
            var bounds = rail.getBoundingClientRect();
            var travel = bounds.height - thumb.offsetHeight;
            ratio = travel > 0 ? Math.max(0, Math.min(1, (event.clientY - bounds.top - offset) / travel)) : 0;
            scrollFromCenter();
            schedule();
        }
        function down(event) {
            if (pointer !== null || event.button !== 0 || rail.hidden) return;
            event.preventDefault();
            event.stopPropagation();
            pointer = event.pointerId;
            startTop = scroller.scrollTop;
            offset = event.target === thumb ? event.clientY - thumb.getBoundingClientRect().top : thumb.offsetHeight / 2;
            rail.setPointerCapture(pointer);
            rail.classList.add('active');
            move(event);
        }
        function finish(event) {
            if (event && event.pointerId !== pointer) return;
            if (pointer === null) return;
            var previous = pointer;
            pointer = null;
            if (rail.hasPointerCapture(previous)) rail.releasePointerCapture(previous);
            rail.classList.remove('active');
            schedule();
        }
        function block(event) { event.stopPropagation(); }
        function keydown(event) {
            var target = scroller.scrollTop;
            if (event.key === 'Home') target = 0;
            else if (event.key === 'End') target = maximum();
            else if (event.key === 'ArrowDown' || event.key === 'PageDown') target += scroller.clientHeight * 0.9;
            else if (event.key === 'ArrowUp' || event.key === 'PageUp') target -= scroller.clientHeight * 0.9;
            else return;
            event.preventDefault();
            event.stopPropagation();
            scroller.scrollTop = target;
            schedule();
        }
        rail.addEventListener('pointerdown', down);
        rail.addEventListener('pointermove', move);
        ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (name) { rail.addEventListener(name, finish); });
        ['touchstart', 'touchmove', 'touchend', 'touchcancel', 'click', 'dblclick', 'contextmenu'].forEach(function (name) { rail.addEventListener(name, block); });
        rail.addEventListener('keydown', keydown);
        scroller.addEventListener('scroll', schedule, {passive: true});
        window.addEventListener('resize', schedule);
        var observer = new ResizeObserver(schedule);
        observer.observe(scroller);
        if (scroller.firstElementChild) observer.observe(scroller.firstElementChild);
        render();
        return function () {
            disposed = true;
            finish();
            cancelAnimationFrame(frame);
            observer.disconnect();
            scroller.removeEventListener('scroll', schedule);
            window.removeEventListener('resize', schedule);
            rail.remove();
        };
    };
})();
