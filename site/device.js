// KAYA — "The app" device choreography.
// Desktop: the phone is pinned; scroll drives a real session arc —
// idle → fast lane armed → Game Focus → live route, with the ping
// countdown synced to the scroll position. Labels fade in per phase.
// Mobile / reduced-motion: CSS shows the static, fully-armed state.

(function () {
  "use strict";

  var stage = document.getElementById("dvStage");
  var device = document.getElementById("dvDevice");
  if (!stage || !device) return;

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var desktop = window.matchMedia("(min-width: 1180px)");

  var msEl = document.getElementById("dvMs");
  var subEl = document.getElementById("dvBoostSub");
  var boostEl = device.querySelector(".dv-boost");
  var netBar = device.querySelector(".dv-bar-net i");
  var labels = [
    document.getElementById("dvL1"),
    document.getElementById("dvL2"),
    document.getElementById("dvL3"),
    document.getElementById("dvL4")
  ];

  var ticking = false;
  var phase = "";
  var lastMs = -1;

  var SUBS = {
    idle: "Engine idle",
    armed: "Fast lane armed · locks on",
    focus: "Game focus on · calls allowed",
    live: "Riding the fastest edge"
  };

  function setPhase(next) {
    if (next === phase) return;
    phase = next;
    if (boostEl) boostEl.classList.toggle("active", next !== "idle");
    if (subEl) subEl.textContent = SUBS[next];
    if (netBar) netBar.style.width = next === "idle" ? "8%" : next === "live" ? "84%" : "62%";
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
      if (msEl && lastMs !== 41) { msEl.textContent = "41"; lastMs = 41; }
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
