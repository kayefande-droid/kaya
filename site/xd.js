// KAYA — "The System" exploded-device scroll driver.
// Desktop: sticky stage; scroll maps to the stack pulling apart quietly
// along Z, labels fade in sequentially. Mobile / reduced-motion: the CSS
// static timeline takes over (this script gets out of the way).

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

    // Quiet, deliberate separation: the screen pushes forward out of the
    // chassis, the base plate recedes — small Y travel, gentle Z travel.
    var z = 90 * e;
    top.style.transform =
      "translate3d(0," + (-46 * e).toFixed(1) + "px," + z.toFixed(1) + "px)";
    mid.style.transform =
      "translate3d(0," + (6 * e).toFixed(1) + "px," + (14 * e).toFixed(1) + "px)";
    base.style.transform =
      "translate3d(0," + (40 * e).toFixed(1) + "px," + (-56 * e).toFixed(1) + "px)";

    // Labels appear one by one as their plate separates.
    if (labels[0]) labels[0].classList.toggle("on", e > 0.06);
    if (labels[1]) labels[1].classList.toggle("on", e > 0.4);
    if (labels[2]) labels[2].classList.toggle("on", e > 0.7);
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
