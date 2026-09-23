// KAYA site — flat HUD demo, nav, reveals. Monochrome; the only color is
// the single functional green of the live dot.

(function () {
  "use strict";

  /* ---------- nav border on scroll ---------- */
  var nav = document.getElementById("nav");
  if (nav) {
    var onNavScroll = function () {
      nav.classList.toggle("scrolled", window.scrollY > 8);
    };
    window.addEventListener("scroll", onNavScroll, { passive: true });
    onNavScroll();
  }

  /* ---------- live HUD demo (illustrative sample) ---------- */
  var pingEl = document.getElementById("pingNum");
  var spark = document.getElementById("sparkline");
  if (pingEl && spark && spark.getContext) {
    var sctx = spark.getContext("2d");
    var history = [];
    var HLEN = 42;
    var jitterEl = document.getElementById("jitterNum");
    var lossEl = document.getElementById("lossNum");
    var routeEl = document.getElementById("routeNum");
    var regions = ["SA", "SA", "SA", "KE", "NG"];
    var ri = 0;
    var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    function push(p) {
      history.push(p);
      if (history.length > HLEN) history.shift();
      pingEl.textContent = p;
      if (jitterEl) {
        var j = 0;
        for (var k = Math.max(1, history.length - 6); k < history.length; k++) {
          j += Math.abs(history[k] - history[k - 1]);
        }
        jitterEl.textContent = Math.round(j / 6);
      }
    }

    function draw() {
      var w = spark.width, h = spark.height;
      sctx.clearRect(0, 0, w, h);
      // hairline grid
      sctx.strokeStyle = "rgba(255,255,255,0.06)";
      sctx.lineWidth = 1;
      for (var g = 1; g <= 3; g++) {
        sctx.beginPath();
        sctx.moveTo(0, (h / 4) * g);
        sctx.lineTo(w, (h / 4) * g);
        sctx.stroke();
      }
      if (history.length < 2) return;
      var min = Math.min.apply(null, history) - 4;
      var max = Math.max.apply(null, history) + 6;
      var sx = w / (HLEN - 1);
      // area — flat monochrome wash
      sctx.beginPath();
      sctx.moveTo(0, h);
      for (var i = 0; i < history.length; i++) {
        var y = h - ((history[i] - min) / (max - min)) * (h - 14) - 6;
        sctx.lineTo(i * sx, y);
      }
      sctx.lineTo((history.length - 1) * sx, h);
      sctx.closePath();
      sctx.fillStyle = "rgba(255,255,255,0.05)";
      sctx.fill();
      // line — white, no glow
      sctx.beginPath();
      for (var j = 0; j < history.length; j++) {
        var yy = h - ((history[j] - min) / (max - min)) * (h - 14) - 6;
        if (j === 0) sctx.moveTo(0, yy); else sctx.lineTo(j * sx, yy);
      }
      sctx.strokeStyle = "#F5F5F7";
      sctx.lineWidth = 1.8;
      sctx.lineJoin = "round";
      sctx.stroke();
      // head dot — the one functional green
      var ly = h - ((history[history.length - 1] - min) / (max - min)) * (h - 14) - 6;
      sctx.fillStyle = "#30D158";
      sctx.beginPath();
      sctx.arc((history.length - 1) * sx, ly, 3, 0, Math.PI * 2);
      sctx.fill();
    }

    function step() {
      var base = 17 + Math.round(Math.sin(Date.now() / 4000) * 3);
      var spike = Math.random() < 0.06 ? Math.round(Math.random() * 14) : 0;
      push(base + spike + Math.round(Math.random() * 3));
      draw();
      if (lossEl && Math.random() < 0.05) {
        lossEl.textContent = Math.random() < 0.5 ? "0%" : "0.2%";
      }
      if (routeEl && Math.random() < 0.08) {
        ri = (ri + 1) % regions.length;
        routeEl.textContent = regions[ri];
      }
    }

    push(18); draw();
    if (!reduceMotion) setInterval(step, 900);
  }

  /* ---------- mobile burger ---------- */
  var burger = document.getElementById("burger");
  var links = document.getElementById("navLinks");
  if (burger && links) {
    burger.addEventListener("click", function (e) {
      e.stopPropagation();
      links.classList.toggle("open");
    });
    links.querySelectorAll("a").forEach(function (a) {
      a.addEventListener("click", function () { links.classList.remove("open"); });
    });
    document.addEventListener("click", function (e) {
      if (!links.contains(e.target) && e.target !== burger) links.classList.remove("open");
    });
  }

  /* ---------- reveal on scroll (subtle) ---------- */
  var revealTargets = document.querySelectorAll(
    ".timeline li, .game-tile, .spec, .dl, .xd-item"
  );
  var reduce = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || !("IntersectionObserver" in window)) {
    revealTargets.forEach(function (el) { el.classList.add("in"); });
  } else {
    revealTargets.forEach(function (el) { el.classList.add("reveal"); });
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting) {
          en.target.classList.add("in");
          io.unobserve(en.target);
        }
      });
    }, { threshold: 0.1 });
    revealTargets.forEach(function (el) { io.observe(el); });
  }
})();
