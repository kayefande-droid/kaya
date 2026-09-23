// KAYA — "The app" device choreography.
// Desktop: the phone is pinned; scroll drives a real session arc —
// idle → fast lane armed → Game Focus → live route, with the ping
// countdown synced to the scroll position. The on-screen UI mirrors
// the real app: status pill, FAST LANE ring, JITTER/STABILITY stats,
// LIVE LATENCY sparkline and the DNS Steering Feed. Labels fade in
// per phase. Mobile / reduced-motion: CSS shows the static live state.

(function () {
  "use strict";

  var stage = document.getElementById("dvStage");
  var device = document.getElementById("dvDevice");
  if (!stage || !device) return;

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var desktop = window.matchMedia("(min-width: 1180px)");

  var msEl = document.getElementById("dvMs");
  var jitEl = document.getElementById("dvJit");
  var staEl = document.getElementById("dvSta");
  var subEl = document.getElementById("dvBoostSub");
  var pillEl = document.getElementById("dvPill");
  var pillText = document.getElementById("dvPillText");
  var ringEl = document.getElementById("dvRing");
  var ringLabel = document.getElementById("dvRingLabel");
  var sparkEl = document.getElementById("dvSparkLine");
  var feedEl = document.getElementById("dvFeed");
  var labels = [
    document.getElementById("dvL1"),
    document.getElementById("dvL2"),
    document.getElementById("dvL3"),
    document.getElementById("dvL4")
  ];

  var ticking = false;
  var phase = "";
  var lastMs = -1;
  var samples = [];          // last N ping samples for the sparkline
  var SPARK_MAX = 40;        // svg viewBox width units
  var feedLines = [];

  var SUBS = {
    idle: "Engine idle",
    armed: "Fast lane live: DNS steered, locks held.",
    focus: "Game Focus on · calls allowed through",
    live: "Riding the fastest edge · 41 ms session"
  };

  var FEEDS = {
    idle: "Nothing resolved yet. Launch a game and the engine will show every matchmaker lookup it steers here.",
    armed: "◉ matchmaker.demonware.net → Cloudflare edge (anycast) — steered",
    focus: "◉ Game Focus holding — WhatsApp + calls allowed, rest quieted",
    live: "◉ session release-us-e4.codm.activision.com → fastest edge — locked"
  };

  function setPhase(next) {
    if (next === phase) return;
    phase = next;

    var live = next !== "idle";
    if (pillEl) pillEl.classList.toggle("live", live);
    if (pillText) pillText.textContent = live ? "FAST LANE" : "IDLE";
    if (ringEl) ringEl.classList.toggle("live", live);
    if (ringLabel) ringLabel.textContent = live ? "FAST LANE LIVE" : "BOOST";
    if (subEl) subEl.textContent = SUBS[next];
    if (feedEl) {
      feedEl.textContent = FEEDS[next];
      feedEl.classList.toggle("live", live);
    }
    if (jitEl) jitEl.textContent = live ? "3" : "—";
    if (staEl) staEl.textContent = next === "live" ? "97%" : live ? "88%" : "0%";
  }

  function drawSpark() {
    if (!sparkEl) return;
    var step = SPARK_MAX / Math.max(1, samples.length - 1);
    var pts = samples.map(function (v, i) {
      var x = (i * step).toFixed(1);
      var y = (32 - ((v - 36) / 42) * 28).toFixed(1); // 36–78 ms → 32→4
      return x + "," + y;
    }).join(" ");
    sparkEl.setAttribute("points", pts);
  }

  function pushSample(ms) {
    samples.push(ms);
    if (samples.length > 14) samples.shift();
    drawSpark();
  }

  function msAt(p) {
    if (p < 0.12) return -1;                                   // idle: no number yet
    if (p < 0.68) return 68 + Math.round(Math.random() * 6);   // armed: ~70 ± jitter
    var t = Math.min(1, (p - 0.68) / 0.2);                     // live: glide 74 → 41
    var eased = 1 - Math.pow(1 - t, 3);
    var v = 74 - 33 * eased + (Math.random() * 2.4 - 1.2);
    return Math.round(v);
  }

  function render() {
    ticking = false;

    if (!desktop.matches || reduceMotion) {
      device.style.transform = "";
      setPhase("live"); // static state = fully armed
      labels.forEach(function (l) { if (l) l.classList.add("on"); });
      if (lastMs !== 41) {
        if (msEl) { msEl.textContent = "41"; msEl.classList.remove("dim"); }
        samples = [64, 61, 58, 55, 52, 49, 47, 45, 44, 43, 42, 41];
        drawSpark();
        lastMs = 41;
      }
      return;
    }

    var rect = stage.getBoundingClientRect();
    var total = Math.max(1, rect.height - window.innerHeight);
    var p = Math.min(1, Math.max(0, -rect.top / total));

    // The device turns from the studio angle (like the reference photo)
    // to face you as the session arms — real 3D rotation on scroll.
    var e2 = 1 - Math.pow(1 - p, 3); // easeOutCubic
    var rx = 10 - 10 * e2;
    var ry = -26 + 19 * e2;
    device.style.transform =
      "translate3d(0," + (-16 * e2).toFixed(1) + "px,0) rotateX(" + rx.toFixed(2) + "deg) rotateY(" + ry.toFixed(2) + "deg)";

    // Phases
    if (p < 0.12) setPhase("idle");
    else if (p < 0.46) setPhase("armed");
    else if (p < 0.7) setPhase("focus");
    else setPhase("live");

    // Ping value (only touch the DOM when the rounded number changes)
    var ms = msAt(p);
    if (msEl && ms !== lastMs) {
      msEl.textContent = ms < 0 ? "—" : String(ms);
      msEl.classList.toggle("dim", ms < 0);
      if (ms > 0) pushSample(ms);
      lastMs = ms;
    }

    // Labels, one per phase
    if (labels[0]) labels[0].classList.toggle("on", true);
    if (labels[1]) labels[1].classList.toggle("on", p > 0.14);
    if (labels[2]) labels[2].classList.toggle("on", p > 0.48);
    if (labels[3]) labels[3].classList.toggle("on", p > 0.74);
  }

  function onScroll() {
    if (!ticking) {
      ticking = true;
      requestAnimationFrame(render);
    }
  }

  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  if (desktop.addEventListener) desktop.addEventListener("change", onScroll);
  else if (desktop.addListener) desktop.addListener(onScroll);
  render();
})();
