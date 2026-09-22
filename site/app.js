// KAYA site — starfield backdrop, live HUD demo, dropdown nav, scroll reveals.

(function () {
  "use strict";

  /* ---------- starfield ---------- */
  var canvas = document.getElementById("starfield");
  if (canvas && canvas.getContext) {
    var ctx = canvas.getContext("2d");
    var stars = [];
    var warp = 0;            // 0 = calm, 1 = boosted (mouse down on hero)
    var targetWarp = 0;
    var W = 0, H = 0;

    function resize() {
      W = canvas.width = window.innerWidth;
      H = canvas.height = window.innerHeight;
      var count = Math.min(220, Math.floor((W * H) / 9000));
      stars = [];
      for (var i = 0; i < count; i++) {
        stars.push({
          x: Math.random() * W,
          y: Math.random() * H,
          z: Math.random() * 0.9 + 0.1,
          r: Math.random() * 1.4 + 0.3
        });
      }
    }
    resize();
    window.addEventListener("resize", resize);

    var last = performance.now();
    function tick(now) {
      var dt = Math.min(50, now - last); last = now;
      warp += (targetWarp - warp) * 0.04;

      ctx.clearRect(0, 0, W, H);
      var speed = 0.02 + warp * 0.6;
      for (var i = 0; i < stars.length; i++) {
        var s = stars[i];
        s.y += speed * s.z * dt * 0.06 * (1 + s.z);
        if (s.y > H + 4) { s.y = -4; s.x = Math.random() * W; }
        var alpha = 0.25 + s.z * 0.6;
        ctx.fillStyle = "rgba(180, 235, 205, " + alpha.toFixed(2) + ")";
        ctx.fillRect(s.x, s.y, s.r, s.r * (1 + warp * 3));
      }
      requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);

    // warp while pressing anywhere on the hero HUD
    var hud = document.getElementById("hud");
    if (hud) {
      hud.addEventListener("pointerdown", function () { targetWarp = 1; });
      window.addEventListener("pointerup", function () { targetWarp = 0; });
    }
  }

  /* ---------- live HUD demo ---------- */
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

    setInterval(function () {
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
    }, 900);

    function draw() {
      var w = spark.width, h = spark.height;
      sctx.clearRect(0, 0, w, h);
      // grid lines
      sctx.strokeStyle = "rgba(255,255,255,0.07)";
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
      // area
      sctx.beginPath();
      sctx.moveTo(0, h);
      for (var i = 0; i < history.length; i++) {
        var y = h - ((history[i] - min) / (max - min)) * (h - 14) - 6;
        sctx.lineTo(i * sx, y);
      }
      sctx.lineTo((history.length - 1) * sx, h);
      sctx.closePath();
      sctx.fillStyle = "rgba(0,229,106,0.12)";
      sctx.fill();
      // line
      sctx.beginPath();
      for (var j = 0; j < history.length; j++) {
        var yy = h - ((history[j] - min) / (max - min)) * (h - 14) - 6;
        if (j === 0) sctx.moveTo(0, yy); else sctx.lineTo(j * sx, yy);
      }
      sctx.strokeStyle = "#00E56A";
      sctx.lineWidth = 2.4;
      sctx.lineJoin = "round";
      sctx.shadowColor = "rgba(0,229,106,.8)";
      sctx.shadowBlur = 8;
      sctx.stroke();
      sctx.shadowBlur = 0;
      // head dot
      var ly = h - ((history[history.length - 1] - min) / (max - min)) * (h - 14) - 6;
      sctx.fillStyle = "#7DFFB2";
      sctx.beginPath();
      sctx.arc((history.length - 1) * sx, ly, 4, 0, Math.PI * 2);
      sctx.fill();
    }
    push(18); draw();
  }

  /* ---------- dropdown nav (click + hover for desktop) ---------- */
  document.querySelectorAll(".dropdown").forEach(function (dd) {
    var btn = dd.querySelector(".dropbtn");
    if (btn) {
      btn.addEventListener("click", function (e) {
        e.stopPropagation();
        var wasOpen = dd.classList.contains("open");
        document.querySelectorAll(".dropdown.open").forEach(function (o) { o.classList.remove("open"); });
        if (!wasOpen) dd.classList.add("open");
      });
    }
  });
  document.addEventListener("click", function () {
    document.querySelectorAll(".dropdown.open").forEach(function (o) { o.classList.remove("open"); });
  });

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
  }

  /* ---------- scroll reveal ---------- */
  var revealTargets = document.querySelectorAll(".fcard, .timeline li, .game-tile, .spec, .dl, .faq details");
  revealTargets.forEach(function (el) { el.classList.add("reveal"); });
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) {
      if (en.isIntersecting) {
        en.target.classList.add("in");
        io.unobserve(en.target);
      }
    });
  }, { threshold: 0.12 });
  revealTargets.forEach(function (el) { io.observe(el); });

  /* ---------- card tilt on hover ---------- */
  document.querySelectorAll(".tilt").forEach(function (card) {
    card.addEventListener("pointermove", function (e) {
      var r = card.getBoundingClientRect();
      var rx = ((e.clientY - r.top) / r.height - 0.5) * -6;
      var ry = ((e.clientX - r.left) / r.width - 0.5) * 8;
      card.style.transform = "perspective(700px) rotateX(" + rx.toFixed(1) + "deg) rotateY(" + ry.toFixed(1) + "deg)";
    });
    card.addEventListener("pointerleave", function () { card.style.transform = ""; });
  });
})();
