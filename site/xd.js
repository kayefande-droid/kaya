// KAYA — "The System" exploded-device scroll driver.
// Self-contained: only touches #xdStage / #xdStack and the xd- plates.
// Desktop: sticky stage, plates Z-split apart as you scroll, labels fade in
// sequentially. Mobile / reduced-motion: static timeline (CSS handles it).

(function () {
  "use strict";

  var stage = document.getElementById("xdStage");
  var stack = document.getElementById("xdStack");
  if (!stage || !stack) return;

  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var desktop = window.matchMedia("(min-width: 1180px)");

  var top = stack.querySelector(".xd-layer-top");
  var mid = stack.querySelector(".xd-layer-mid");
  var base = stack.querySelector(".xd-layer-base");
  var labels = [
    stack.querySelector(".xd-label-1"),
    stack.querySelector(".xd-label-2"),
    stack.querySelector(".xd-label-3")
  ];
  if (!top || !mid || !base) return;

  var ticking = false;

  function update() {
    ticking = false;

    if (!desktop.matches || reduceMotion) {
      [top, mid, base].forEach(function (el) { el.style.transform = ""; });
      labels.forEach(function (l) { if (l) l.classList.add("on"); });
      return;
    }

    var rect = stage.getBoundingClientRect();
    var total = Math.max(1, rect.height - window.innerHeight);
    var p = Math.min(1, Math.max(0, -rect.top / total));
    var e = p < 0.5 ? 2 * p * p : 1 - Math.pow(-2 * p + 2, 2) / 2; // easeInOutQuad

    // Explode: top plate lifts back/up, base drops forward, mid holds the line.
    var spread = 116 * e;
    top.style.transform =
      "translate3d(0," + (-spread).toFixed(1) + "px," + (74 * e).toFixed(1) + "px)";
    mid.style.transform =
      "translate3d(0," + (spread * 0.08).toFixed(1) + "px," + (12 * e).toFixed(1) + "px)";
    base.style.transform =
      "translate3d(0," + (spread * 0.64).toFixed(1) + "px," + (-62 * e).toFixed(1) + "px)";

    // Labels appear one by one as their plate separates.
    if (labels[0]) labels[0].classList.toggle("on", e > 0.05);
    if (labels[1]) labels[1].classList.toggle("on", e > 0.42);
    if (labels[2]) labels[2].classList.toggle("on", e > 0.72);
  }

  function onScroll() {
    if (!ticking) {
      ticking = true;
      requestAnimationFrame(update);
    }
  }

  window.addEventListener("scroll", onScroll, { passive: true });
  window.addEventListener("resize", onScroll);
  if (desktop.addEventListener) desktop.addEventListener("change", onScroll);
  else if (desktop.addListener) desktop.addListener(onScroll);
  update();
})();
