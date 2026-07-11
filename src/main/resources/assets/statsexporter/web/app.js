const $ = (selector) => document.querySelector(selector);
const number = new Intl.NumberFormat();
const escapeHtml = (value) => String(value).replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"})[char]);

function label(key, dashboard) { return dashboard.labels?.[key] || key.replaceAll("_", " "); }

function render(data) {
  const dashboard = data.dashboard || {};
  const configured = dashboard.visibleObjectives || [];
  const discovered = [...new Set(data.players.flatMap(player => Object.keys(player).filter(key => key !== "name")))];
  const objectives = configured.length ? configured.filter(key => discovered.includes(key)) : discovered;
  const sortBy = objectives.includes(dashboard.sortBy) ? dashboard.sortBy : objectives[0];
  const direction = dashboard.sortDirection === "asc" ? 1 : -1;
  const players = [...data.players].sort((a,b) => sortBy ? direction * ((a[sortBy] || 0) - (b[sortBy] || 0)) || a.name.localeCompare(b.name) : a.name.localeCompare(b.name));
  document.title = `${dashboard.title || "Server Statistics"} — Server`;
  $("#title").textContent = dashboard.title || "Server Statistics";
  $("#serverName").textContent = dashboard.title || "Server";
  $("#updated").textContent = data.lastUpdated ? `Last updated ${new Date(data.lastUpdated).toLocaleString()}` : "No data yet";
  $("#playerCount").textContent = `${players.length} player${players.length === 1 ? "" : "s"}`;
  $("#summary").innerHTML = objectives.map(key => `<article class="stat"><span class="stat-value">${number.format(Math.max(0, ...players.map(p => p[key] || 0)))}</span><span class="stat-label">Top ${escapeHtml(label(key, dashboard))}</span></article>`).join("");
  $("#head").innerHTML = `<tr><th>#</th><th>Player</th>${objectives.map(key => `<th>${escapeHtml(label(key, dashboard))}</th>`).join("")}</tr>`;
  $("#body").innerHTML = players.length ? players.map((player, index) => `<tr><td class="rank">${index + 1}</td><td>${escapeHtml(player.name)}</td>${objectives.map(key => `<td>${number.format(player[key] || 0)}</td>`).join("")}</tr>`).join("") : `<tr><td class="empty" colspan="${objectives.length + 2}">No scoreboard data is available yet.</td></tr>`;
}

async function load() { try { const response = await fetch("/api/stats", {cache:"no-store"}); if (!response.ok) throw new Error(); render(await response.json()); } catch { $("#updated").textContent = "Statistics are currently unavailable."; $("#body").innerHTML = '<tr><td class="empty">Could not load statistics.</td></tr>'; } }
load(); setInterval(load, 60_000);
