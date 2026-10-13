/* FB-SMP — shared behaviour for every page.
   No build step, no dependencies. Everything degrades gracefully offline. */
(function () {
  "use strict";

  var REPO = "firebreadga359-bot/FB-SMP.net";
  var JAVA = { edition: "java", host: "cairo-paste.tun.ply.gg", port: 25565 };
  var BEDROCK = { edition: "bedrock", host: "fried-toilette.tun.ply.gg", port: 37298 };
  var REFRESH_MS = 60000;

  function $(id) { return document.getElementById(id); }
  function api(path) { return "https://api.mcstatus.io/v2/status/" + path; }

  /* ---------- nav ---------- */
  var toggle = document.querySelector(".nav-toggle");
  var links = $("nav-links");
  if (toggle && links) {
    toggle.addEventListener("click", function () {
      var open = links.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }
  var here = location.pathname.split("/").pop() || "index.html";
  document.querySelectorAll(".nav-links a").forEach(function (a) {
    var href = a.getAttribute("href") || "";
    if (href.split("#")[0] === here) a.setAttribute("aria-current", "page");
  });

  /* ---------- reveal on scroll ---------- */
  var reveals = document.querySelectorAll(".reveal");
  if ("IntersectionObserver" in window && reveals.length) {
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (e, i) {
        if (e.isIntersecting) {
          var el = e.target;
          setTimeout(function () { el.classList.add("in"); }, Math.min(i, 6) * 70);
          io.unobserve(el);
        }
      });
    }, { rootMargin: "-40px 0px -60px 0px" });
    reveals.forEach(function (el) { io.observe(el); });
  } else {
    reveals.forEach(function (el) { el.classList.add("in"); });
  }

  /* ---------- counters ---------- */
  var counters = document.querySelectorAll("[data-count]");
  function finalText(el) {
    return parseFloat(el.dataset.count || "0").toLocaleString() + (el.dataset.suffix || "");
  }
  // Show the real number immediately so a counter is never wrong if it is never
  // scrolled into view; the animation below replays it from zero on reveal.
  counters.forEach(function (el) { el.textContent = finalText(el); });

  function countUp(el) {
    var target = parseFloat(el.dataset.count || "0");
    var suffix = el.dataset.suffix || "";
    var start = performance.now(), dur = 1100;
    function tick(now) {
      var p = Math.min(1, (now - start) / dur);
      var eased = 1 - Math.pow(1 - p, 3);
      el.textContent = Math.round(target * eased).toLocaleString() + suffix;
      if (p < 1) requestAnimationFrame(tick);
    }
    requestAnimationFrame(tick);
  }
  if ("IntersectionObserver" in window && counters.length) {
    var cio = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (e.isIntersecting) { countUp(e.target); cio.unobserve(e.target); }
      });
    }, { rootMargin: "-30px" });
    counters.forEach(function (el) { cio.observe(el); });
  }

  /* ---------- copy buttons ---------- */
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var el = $(btn.dataset.copy);
      if (!el) return;
      var text = el.textContent.trim();
      var done = function () {
        var old = btn.textContent;
        btn.textContent = "Copied";
        setTimeout(function () { btn.textContent = old; }, 1400);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () {});
      } else {
        var t = document.createElement("textarea");
        t.value = text; document.body.appendChild(t); t.select();
        try { document.execCommand("copy"); done(); } catch (e) {}
        document.body.removeChild(t);
      }
    });
  });

  /* ---------- live server status ---------- */
  function fetchStatus(server) {
    return fetch(api(server.edition + "/" + server.host + ":" + server.port),
      { headers: { Accept: "application/json" } })
      .then(function (r) { return r.json(); })
      .catch(function () { return { online: false }; });
  }

  function paint(prefix, data) {
    var stat = $(prefix + "-stat"), state = $(prefix + "-state"),
        players = $(prefix + "-players"), version = $(prefix + "-version"),
        bar = $(prefix + "-bar");
    if (!stat) return;
    var online = data && data.online === true;
    stat.className = "stat " + (online ? "online" : "offline");
    if (state) {
      state.className = "state " + (online ? "online" : "offline");
      state.innerHTML = '<span class="dot ' + (online ? "online" : "offline") + '"></span> ' +
        (online ? "Online" : "Offline");
    }
    if (players) {
      if (online && data.players) {
        var n = data.players.online || 0, max = data.players.max || 0;
        players.innerHTML = n + '<small> of ' + max + ' players online</small>';
        if (bar) bar.style.width = max > 0 ? Math.min(100, Math.round((n / max) * 100)) + "%" : "0%";
      } else {
        players.innerHTML = "—<small> players online</small>";
        if (bar) bar.style.width = "0%";
      }
    }
    if (version) version.textContent = (online && data.version && data.version.name) ? data.version.name : "—";
    return online;
  }

  function refreshStatus() {
    var btn = $("refresh-btn");
    if (btn) { btn.disabled = true; btn.textContent = "Checking…"; }
    return Promise.all([fetchStatus(JAVA), fetchStatus(BEDROCK)]).then(function (r) {
      var a = paint("java", r[0]), b = paint("bedrock", r[1]);
      var any = a === true || b === true;
      var heroDot = $("hero-dot"), heroState = $("hero-state");
      if (heroDot) heroDot.className = "dot " + (any ? "online" : "offline");
      if (heroState) heroState.textContent = any ? "Online now" : "Currently offline";
      // console widget
      var cj = $("con-java"), cb = $("con-bedrock"), ct = $("con-time"), cs = $("con-summary");
      if (cj) {
        cj.textContent = a ? "up" : "down";
        cj.className = a ? "ok" : "bad";
      }
      if (cb) {
        cb.textContent = b ? "up" : "down";
        cb.className = b ? "ok" : "bad";
      }
      if (ct) ct.textContent = new Date().toLocaleTimeString();
      if (cs) cs.textContent = any ? "all systems nominal" : "degraded";
      var at = $("checked-at");
      if (at) at.textContent = "Last checked: " + new Date().toLocaleTimeString();
      if (btn) { btn.disabled = false; btn.textContent = "Refresh now"; }
      return any;
    });
  }

  if ($("java-stat") || $("con-java")) {
    var rb = $("refresh-btn");
    if (rb) rb.addEventListener("click", refreshStatus);
    refreshStatus();
    setInterval(refreshStatus, REFRESH_MS);
  }

  /* ---------- control-room address resolver ---------- */
  (function () {
    var RAW = "https://raw.githubusercontent.com/" + REPO + "/main/panel-url.txt";
    fetch(RAW, { cache: "no-store" })
      .then(function (r) { return r.ok ? r.text() : ""; })
      .then(function (t) {
        var url = (t || "").trim();
        if (!/^https:\/\/[a-z0-9.-]+$/i.test(url)) throw new Error("no url");
        var link = $("panel-link");
        if (link) link.href = url;
        var note = $("panel-status");
        if (note) note.innerHTML = 'Live address: <a href="' + url + '" rel="noopener">' + url + "</a>";
        document.querySelectorAll("[data-panel-link]").forEach(function (a) { a.href = url; });
      })
      .catch(function () {
        var note = $("panel-status");
        if (note) note.textContent =
          "Control-room address is set by the host; see the repository for the current link.";
      });
  })();

  /* ---------- GitHub: latest commits -> changelog ---------- */
  var log = $("commit-log");
  if (log) {
    fetch("https://api.github.com/repos/" + REPO + "/commits?per_page=14", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (commits) {
        if (!commits.length) throw new Error("none");
        log.innerHTML = commits.map(function (c) {
          var msg = (c.commit.message || "").split("\n")[0];
          var date = new Date(c.commit.author.date).toLocaleDateString(undefined,
            { year: "numeric", month: "short", day: "numeric" });
          var who = (c.author && c.author.login) ? "@" + c.author.login : (c.commit.author.name || "");
          var url = c.html_url || "#";
          return '<div class="log-item"><div class="when">' + date + "</div>" +
            '<div><div class="what"><a href="' + url + '" rel="noopener">' +
            escapeHtml(msg) + "</a></div>" +
            '<div class="who">' + escapeHtml(who) + " · " + (c.sha || "").slice(0, 7) + "</div></div></div>";
        }).join("");
      })
      .catch(function () {
        log.innerHTML = '<p class="note">The change log is read from GitHub. ' +
          'See <a href="https://github.com/' + REPO + '/commits/main" rel="noopener">all commits</a>.</p>';
      });
  }

  /* ---------- GitHub: open issues counter ---------- */
  document.querySelectorAll("[data-issues]").forEach(function (el) {
    fetch("https://api.github.com/repos/" + REPO, { headers: { Accept: "application/vnd.github+json" } })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) { if (d) el.textContent = d.open_issues_count; })
      .catch(function () {});
  });

  /* ---------- uptime alert history -> issues ---------- */
  var alerts = $("alert-log");
  if (alerts) {
    fetch("https://api.github.com/repos/" + REPO + "/issues?labels=uptime&state=all&per_page=8", {
      headers: { Accept: "application/vnd.github+json" }
    })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (issues) {
        if (!issues.length) {
          alerts.innerHTML = '<p class="note">No outages recorded. The checker runs every 5 minutes.</p>';
          return;
        }
        alerts.innerHTML = issues.map(function (i) {
          var state = i.state === "closed" ? "recovered" : "open";
          var cls = i.state === "closed" ? "ok" : "bad";
          return '<div class="log-item"><div class="when">' +
            new Date(i.created_at).toLocaleDateString() + '</div>' +
            '<div><div class="what"><a href="' + i.html_url + '" rel="noopener">' +
            escapeHtml(i.title) + '</a></div><div class="who">status: <span class="' + cls + '">' +
            state + "</span></div></div></div>";
        }).join("");
      })
      .catch(function () {
        alerts.innerHTML = '<p class="note">Alert history is read from GitHub issues labelled ' +
          '<code>uptime</code>.</p>';
      });
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  /* ---------- lead form ---------- */
  var form = $("join-form");
  if (form) {
    var ERROR_TEXT = {
      missing_field: "Please fill in every required field.",
      invalid_value: "One of the values was not valid. Please check and try again.",
      not_an_option: "Please choose one of the offered platforms.",
      value_too_long: "One of your answers is too long.",
      consent_not_given: "Please tick the consent box so we may reply.",
      unknown_field: "Something went wrong with the form. Please refresh and retry.",
      invalid_body: "Something went wrong with the form. Please refresh and retry.",
      too_many_submissions: "Too many attempts. Please wait a few minutes and try again.",
      project_busy: "The server is busy right now. Please try again a little later.",
      preview_not_accepted: "This preview cannot take submissions — use the live site.",
      no_such_form: "The form is not available right now. Please try again later."
    };
    var formStatus = $("form-status");
    var submitBtn = $("submit-btn");
    var fallback = $("signup-fallback");
    // The intake endpoint only exists on the server's own host. Anywhere else
    // (GitHub Pages, local preview) point people at a working route instead.
    var FORM_HOSTS = ["fb-smp.birta.link", "fb-smp.net", "www.fb-smp.net"];

    function showForm(cls, msg) {
      if (!formStatus) return;
      formStatus.className = "status-msg " + cls;
      formStatus.textContent = msg;
    }

    if (FORM_HOSTS.indexOf(location.hostname) === -1) {
      var card = form.closest(".form-card");
      if (card) card.hidden = true;
      form.hidden = true;
      if (fallback) fallback.hidden = false;
    } else {
      form.addEventListener("submit", function (e) {
        e.preventDefault();
        var name = $("f-name").value.trim();
        var email = $("f-email").value.trim();
        var platform = $("f-platform").value;
        var message = $("f-message").value.trim();
        var consent = $("f-consent").checked;
        if (!name || !email || !platform || !consent) {
          showForm("err", ERROR_TEXT.missing_field);
          return;
        }
        var payload = { name: name, email: email, platform: platform, consent: consent };
        if (message) payload.message = message;
        submitBtn.disabled = true;
        submitBtn.textContent = "Sending…";
        if (formStatus) formStatus.className = "status-msg";
        fetch("/_forms/join", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        })
          .then(function (res) {
            return res.json().catch(function () { return {}; }).then(function (data) {
              return { ok: res.ok, status: res.status, data: data };
            });
          })
          .then(function (r) {
            if (r.ok && (r.status === 201 || r.data.status === "accepted")) {
              form.reset();
              showForm("ok", "Thanks — your request is in. We will be in touch about joining.");
            } else {
              var code = r.data && r.data.code;
              showForm("err", ERROR_TEXT[code] || "We could not send that. Please try again in a moment.");
            }
          })
          .catch(function () {
            showForm("err", "Network problem — please check your connection and try again.");
          })
          .then(function () {
            submitBtn.disabled = false;
            submitBtn.textContent = "Send my request";
          });
      });
    }
  }

  /* ---------- year ---------- */
  document.querySelectorAll("[data-year]").forEach(function (el) {
    el.textContent = new Date().getFullYear();
  });
})();
