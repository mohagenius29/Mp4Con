/* HTML2MP4 virtual clock. __W__ is replaced by the output width. Injected before any page script.
   Audio elements are only stubbed so the page's own logic keeps running and so the app can tell when the audio ends;
   no sound is captured. */
(function () {
  if (window.__vc) return;
  var RD = Date;
  var vc = window.__vc = { t: 0, prev: 0, epoch: RD.now(), raf: [], rafId: 0, timers: [], timerId: 0,
    anims: new WeakMap(), synced: false, audios: [], errors: [], everPlayed: false,
    isReady: false, cfg: null, clicked: new WeakSet(), durByKey: {} };

  // ---- fixed device pixel ratio + viewport so CSS px == output px
  try { Object.defineProperty(window, 'devicePixelRatio', { get: function () { return 1; }, configurable: true }); } catch (e) {}
  function fixViewport() {
    var head = document.head; if (!head) return false;
    var m = document.querySelector('meta[name=viewport]');
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; head.appendChild(m); }
    m.setAttribute('content', 'width=__W__');
    var s = document.createElement('style'); s.textContent = 'html,body{overflow:hidden!important}'; head.appendChild(s);
    return true;
  }
  if (!fixViewport()) {
    var mo = new MutationObserver(function () { if (fixViewport()) mo.disconnect(); });
    mo.observe(document.documentElement, { childList: true, subtree: true });
  }

  // ---- clocks
  performance.now = function () { return vc.t; };
  function VDate() {
    var a = [].slice.call(arguments);
    if (!(this instanceof VDate)) return new RD(vc.epoch + vc.t).toString();
    if (a.length === 0) return new RD(vc.epoch + vc.t);
    return new (Function.prototype.bind.apply(RD, [null].concat(a)))();
  }
  VDate.prototype = RD.prototype; VDate.now = function () { return vc.epoch + vc.t; };
  VDate.UTC = RD.UTC; VDate.parse = RD.parse; window.Date = VDate;

  window.requestAnimationFrame = function (cb) { var id = ++vc.rafId; vc.raf.push({ id: id, cb: cb }); return id; };
  window.cancelAnimationFrame = function (id) { vc.raf = vc.raf.filter(function (r) { return r.id !== id; }); };
  function addTimer(fn, ms, args, rep) {
    if (typeof fn === 'string') fn = new Function(fn);
    ms = Math.max(0, +ms || 0);
    var id = ++vc.timerId;
    vc.timers.push({ id: id, due: vc.t + ms, fn: fn, args: args, rep: rep ? Math.max(ms, 1) : 0, seq: id });
    return id;
  }
  window.setTimeout = function (fn, ms) { return addTimer(fn, ms, [].slice.call(arguments, 2), false); };
  window.setInterval = function (fn, ms) { return addTimer(fn, ms, [].slice.call(arguments, 2), true); };
  window.clearTimeout = window.clearInterval = function (id) { vc.timers = vc.timers.filter(function (x) { return x.id !== id; }); };

  var P = HTMLMediaElement.prototype, S = '__vs';
  var nativeDur = Object.getOwnPropertyDescriptor(P, 'duration').get;

  // duration: native if the element loaded, else the app's probe of the embedded file (only used to know when the video should end)
  function durOf(el) {
    var d = nativeDur.call(el);
    if (isFinite(d) && d > 0) return d;
    var s = el.currentSrc || el.src || '';
    var k = s.indexOf('data:') === 0 ? s.slice(0, 60) : s;
    return vc.durByKey[k] || d;
  }

  // ---- stubbed media elements: the page's own currentTime / playbackRate logic runs against these
  function st(el) {
    var s = el[S];
    if (!s) { s = el[S] = { pos: 0, rate: 1, playing: false, ended: false, played: false }; vc.audios.push(el); }
    return s;
  }
  function def(name, d) { Object.defineProperty(P, name, { configurable: true, enumerable: true, get: d.get, set: d.set }); }
  def('currentTime', { get: function () { return st(this).pos; }, set: function (v) { var s = st(this); s.pos = Math.max(0, +v || 0); s.ended = false; } });
  def('playbackRate', { get: function () { return st(this).rate; }, set: function (v) { st(this).rate = +v; } });
  def('paused', { get: function () { return !st(this).playing; } });
  def('ended', { get: function () { return st(this).ended; } });
  P.play = function () {
    var s = st(this);
    if (s.ended) { s.pos = 0; s.ended = false; }
    s.playing = true; s.played = true; vc.everPlayed = true;
    this.dispatchEvent(new Event('play')); this.dispatchEvent(new Event('playing'));
    return Promise.resolve();
  };
  P.pause = function () { var s = st(this); if (s.playing) { s.playing = false; this.dispatchEvent(new Event('pause')); } };

  // ---- overlay buttons
  var startRe = /^\s*(▶|►|⏵|▷)?\s*(play|start|begin|tap to start|click to start|cheza|anza|washa|sikiliza|tazama|jouer|lancer|d[eé]marrer|lire|reproducir|iniciar|comenzar|spielen|starten|abspielen|riproduci|avvia|oynat|ba[sş]lat|putar|mulai|تشغيل|ابدأ|شغل|ابدا)/i;
  var hideRe = /(play|pause|replay|restart|▶|⏸|↻|⟳|↺)/i;
  function buttons() { return [].slice.call(document.querySelectorAll('button,[role=button],.btn,a.button,input[type=button]')); }
  function autoClick() {
    buttons().forEach(function (b) {
      if (vc.clicked.has(b)) return;
      var txt = (b.textContent || b.value || '').trim();
      if (txt.length < 40 && (startRe.test(txt) || /(^|[-_ ])(play|start)/i.test((b.id || '') + ' ' + (b.className || '')))) {
        vc.clicked.add(b); try { b.click(); } catch (e) { vc.errors.push(String(e)); }
      }
    });
  }
  // Fallback for pages whose start button has another label (e.g. "Cheza wimbo"): if nothing has played yet, click the first non-pause-like button once.
  function fallbackClick() {
    if (vc.everPlayed || vc.fallbackDone) return;
    var bs = buttons().filter(function (b) { var t = (b.textContent || b.value || '').trim(); return t.length < 40 && !/(pause|stop|replay|restart|mute|close|⏸|↻|⟳|↺)/i.test(t + ' ' + (b.id || '')); });
    if (bs.length) { vc.fallbackDone = true; try { bs[0].click(); } catch (e) { vc.errors.push(String(e)); } }
  }
  function hideOverlays() {
    buttons().forEach(function (b) {
      var txt = (b.textContent || b.value || '').trim();
      if (txt.length < 40 && (hideRe.test(txt) || hideRe.test((b.id || '') + ' ' + (b.className || '')) || startRe.test(txt)))
        b.style.setProperty('visibility', 'hidden', 'important');
    });
  }

  // ---- CSS / WAAPI animations follow the virtual clock
  function syncAnims() {
    if (!document.getAnimations) return;
    var list = document.getAnimations();
    for (var i = 0; i < list.length; i++) {
      var a = list[i], r = vc.anims.get(a);
      if (!r) { r = { start: vc.synced ? vc.prev : 0 }; vc.anims.set(a, r); try { a.pause(); } catch (e) {} }
      try { a.currentTime = vc.t - r.start; } catch (e) {}
    }
    vc.synced = true;
  }

  function runTimers() {
    var guard = 0;
    for (;;) {
      var next = null;
      for (var i = 0; i < vc.timers.length; i++) {
        var x = vc.timers[i];
        if (x.due <= vc.t && (!next || x.due < next.due || (x.due === next.due && x.seq < next.seq))) next = x;
      }
      if (!next || guard++ > 20000) break;
      if (next.rep) { next.due += next.rep; next.seq = ++vc.timerId; } else vc.timers.splice(vc.timers.indexOf(next), 1);
      try { next.fn.apply(window, next.args); } catch (e) { vc.errors.push(String(e)); }
    }
  }

  vc.prepare = function (click, hide) { vc.cfg = { click: !!click, hide: !!hide }; if (click) autoClick(); if (hide) hideOverlays(); return 1; };
  vc.ready = function () {
    var imgs = [].slice.call(document.images).map(function (i) {
      return i.complete ? 1 : new Promise(function (r) { i.addEventListener('load', r); i.addEventListener('error', r); });
    });
    var fnts = [];
    try { if (document.fonts && document.fonts.forEach) document.fonts.forEach(function (f) { try { fnts.push(Promise.resolve(f.load()).catch(function () {})); } catch (e) {} }); } catch (e) {}
    var auds = [].slice.call(document.querySelectorAll('audio')).map(function (a) {
      if (a.readyState >= 1) return 1;
      return new Promise(function (r) { a.addEventListener('loadedmetadata', r); a.addEventListener('error', r); setTimeout(r, 8000); });
    });
    Promise.all([document.fonts ? document.fonts.ready : 1].concat(imgs, auds, fnts)).then(function () { vc.isReady = true; });
    return 1;
  };
  vc.info = function () { return { w: innerWidth, h: innerHeight, dpr: 1, audios: vc.audios.length }; };
  /* Advance to absolute virtual time t (ms) for frame index f. Returns a small state object. */
  vc.step = function (t, f) {
    var dt = Math.max(0, t - vc.t); vc.prev = vc.t; vc.t = t;
    var playing = 0, played = 0, ended = 0, prog = -1, events = [];
    for (var i = 0; i < vc.audios.length; i++) {
      var el = vc.audios[i], s = el[S], dur = durOf(el), was = s.playing;
      if (was && dt > 0) {
        s.pos += dt / 1000 * s.rate;
        if (isFinite(dur) && s.pos >= dur) {
          if (el.loop && dur > 0) s.pos = s.pos % dur;
          else { s.pos = dur; s.playing = false; s.ended = true; events.push([el, 'ended']); }
        } else events.push([el, 'timeupdate']);
      }
      if (s.playing) playing++;
      if (s.played) played++;
      if (s.ended) ended++;
      if (s.played && isFinite(dur) && dur > 0) prog = Math.max(prog, s.pos / dur);
    }
    events.forEach(function (e) { try { e[0].dispatchEvent(new Event(e[1])); } catch (x) {} });
    runTimers();
    var q = vc.raf; vc.raf = [];
    for (var j = 0; j < q.length; j++) { try { q[j].cb(t); } catch (e) { vc.errors.push(String(e)); } }
    if (vc.cfg) {
      if (vc.cfg.click && f < 3) autoClick();
      if (vc.cfg.click && f >= 3 && f <= 40 && f % 5 === 0) fallbackClick();
      if (vc.cfg.hide && (f < 3 || f % 15 === 0)) hideOverlays();
    }
    syncAnims();
    return { n: vc.audios.length, playing: playing, played: played, ended: ended, prog: prog, errs: vc.errors.length };
  };
})();
