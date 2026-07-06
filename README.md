# Stats Exporter

A server-side Fabric mod for Minecraft 26.2 that exposes player scoreboard
statistics over a lightweight HTTP endpoint, so a website can render a
statistics page.

The mod reads the scoreboard **live from the running server** via
`MinecraftServer#getScoreboard()` — it does **not** parse `scoreboard.dat` as
NBT, because it runs server-side and has direct in-memory access.

## Features

- Exposes any scoreboard objectives you choose (configurable).
- Optionally hides banned players from the response.
- CORS-restricted to your website's origin.
- Cached snapshot — recomputed every 5–15 minutes, served cheaply per request.
- Fully asynchronous — all refresh/serving work runs on minimum-priority
  daemon threads; the server thread only does a sub-millisecond snapshot copy.
- Simple rate limiting (30 requests/second).
- No dependencies beyond Fabric API.

---

## Installation

1. Download the latest release `.jar` (or build from source — see below).
2. Drop the jar into your server's `mods/` folder.
3. Start (or restart) the server. The mod creates a default config file at
   `config/statsexporter.json`.
4. Edit the config (see below) and restart the server.

---

## Configuration

The config file is created automatically at
`<server>/config/statsexporter.json` on first launch. It is plain JSON (no
comments — see this README for documentation of each field).

| Key                    | Type    | Default      | Notes                                              |
|------------------------|---------|--------------|----------------------------------------------------|
| `port`                 | int/string | `"your_port"` | Port the HTTP server listens on. Replace with a real port number (e.g. `8790`). |
| `cacheIntervalMinutes` | int     | `10`         | How often the stats snapshot is recomputed. Clamped to **5–15**. |
| `allowedOrigin`        | string  | `*`          | Origin allowed via CORS (`Access-Control-Allow-Origin`). Set to your website's origin, e.g. `https://example.com`. `*` allows any origin (not recommended for production). |
| `objectives`           | array   | `[]`         | List of scoreboard objective names to expose. Each becomes a field in the JSON response. |
| `hideBannedPlayers`    | bool    | `false`      | If `true`, banned players are excluded from the JSON response. |

Example:

```json
{
  "port": 8790,
  "cacheIntervalMinutes": 10,
  "allowedOrigin": "https://example.com",
  "objectives": ["bac_advancements", "hc_playTimeShow", "deathCount"],
  "hideBannedPlayers": true
}
```

### Finding your scoreboard objective names

To see which objectives exist on your server, run in the server console:

```
/scoreboard objectives list
```

This prints lines like `[bac_advancements]` — use the name in brackets as an
entry in the `objectives` array.

---

## HTTP API

### `GET /api/stats`

Returns the cached stats snapshot as JSON. The snapshot is recomputed every
`cacheIntervalMinutes`; requests always serve the cached copy to stay cheap.

**200 response:**

```json
{
  "lastUpdated": "2026-07-04T14:00:00Z",
  "players": [
    {
      "name": "Steve",
      "bac_advancements": 42,
      "hc_playTimeShow": 128394
    }
  ]
}
```

- `lastUpdated` — ISO-8601 UTC timestamp of the last cache refresh.
- `players` — array of player objects. **Empty array** when the scoreboard
  has no data yet (the endpoint never errors for missing data).
- Each player object contains a `name` field plus one field per configured
  objective (the objective name as key, the score as integer value).

CORS headers are sent on every response:

```
Access-Control-Allow-Origin: <allowedOrigin>
Access-Control-Allow-Methods: GET, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Max-Age: 600
```

`OPTIONS` requests are answered with `204 No Content` for CORS preflight.

---

## Deployment: exposing the endpoint over HTTPS

The mod's HTTP server runs on plain HTTP. Since most websites are served over
HTTPS, browsers will block a plain-HTTP fetch (mixed content). You need to put
HTTPS in front of the mod's port and expose it as a public HTTPS URL.

This section walks through the recommended setup using **Cloudflare**, which
requires no root access and no certificate management.

### Step 1: Open the port on your hosting panel

The mod's `port` must be opened as an **additional allocation** in your
hosting panel (Pterodactyl, Folium, etc.) — it is separate from the
Minecraft game port.

1. In your hosting panel, go to **Settings → Allocations** (or **Network**).
2. Add a new allocation for the port you set in the config (e.g. `8790`).
3. Note the **public address** of your server node (e.g. `n-nyc-19.folium.host`
   or the IP address shown in the panel).

Verify the endpoint is reachable from your own machine:

```bash
curl -i http://<your-server-address>:<port>/api/stats
```

You should get `200` with JSON. If this times out, the port allocation is not
public — check your panel settings or ask your host's support.

### Step 2: Create a Cloudflare Worker (reverse proxy)

A Cloudflare Worker sits on Cloudflare's edge, terminates HTTPS for you, and
forwards requests to your server's HTTP port. It's free and requires no server
access.

1. Go to the [Cloudflare dashboard](https://dash.cloudflare.com/) →
   **Workers & Pages** → **Create** → **Create Worker**.
2. Name it (e.g. `stats-proxy`) → click **Deploy**.
3. Click **Edit code**.
4. Replace all the code with the following, substituting your server address
   and port:

```js
export default {
  async fetch(request) {
    // Forward to the mod's HTTP endpoint on your server.
    const upstream = "http://<your-server-address>:<port>/api/stats";

    // Handle CORS preflight.
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: corsHeaders(),
      });
    }

    if (request.method !== "GET") {
      return new Response("Method Not Allowed", { status: 405 });
    }

    try {
      const r = await fetch(upstream, { cache: "no-store" });
      const body = await r.text();
      return new Response(body, {
        status: r.status,
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          ...corsHeaders(),
        },
      });
    } catch (e) {
      return new Response(JSON.stringify({ error: "upstream unreachable" }), {
        status: 502,
        headers: { "Content-Type": "application/json", ...corsHeaders() },
      });
    }
  },
};

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": "https://your-website.com",
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
    "Access-Control-Max-Age": "600",
  };
}
```

5. Replace `<your-server-address>:<port>` with your server's public address
   and the mod's port (e.g. `http://n-nyc-19.folium.host:8790/api/stats`).
6. Replace `https://your-website.com` in `corsHeaders()` with your website's
   origin (the same value as `allowedOrigin` in the mod config).
7. Click **Save and deploy**.

Test the Worker URL (looks like `stats-proxy.<your-subdomain>.workers.dev`):

```bash
curl -i https://stats-proxy.<your-subdomain>.workers.dev/api/stats
```

You should get `200` with JSON.

> **Note:** Cloudflare Workers cannot fetch bare IP addresses on non-standard
> ports. Use a hostname (e.g. `n-nyc-19.folium.host`) instead of a raw IP.
> You can find the hostname by reverse-DNS-looking-up your server's IP, or by
> checking your hosting panel.

### Step 3: Bind a custom domain (optional but recommended)

Instead of the long `*.workers.dev` URL, you can use a clean subdomain like
`api.your-domain.com`.

1. In Cloudflare, go to your domain → **DNS** → **Records** → **Add record**:
   - **Type:** `AAAA`
   - **Name:** `api` (or whatever subdomain you want)
   - **IPv6 address:** `100::` (Cloudflare's dummy address for Workers)
   - **Proxy status:** Proxied (orange cloud)
2. Go to **Workers & Pages** → your worker → **Settings** → **Triggers** →
   **Add Custom Domain** (or **Routes** → **Add route**):
   - Route: `api.your-domain.com/*` → Zone: `your-domain.com`
3. Wait 1–2 minutes for the SSL certificate to be issued.

Test:

```bash
curl -i https://api.your-domain.com/api/stats
```

### Step 4: Point your website at the endpoint

In your website's JavaScript, fetch the endpoint:

```js
const STATS_ENDPOINT = 'https://api.your-domain.com/api/stats';
```

Make sure the `allowedOrigin` in the mod config and the
`Access-Control-Allow-Origin` in the Worker both match your website's origin
exactly (e.g. `https://your-website.com` — no trailing slash, no path).

---

## Building from source

Requirements: Java 25, Gradle (the wrapper is included).

```bash
./gradlew build
```

The built jar is at `build/libs/statsexporter-<version>.jar`.

---

## Performance notes

All of the mod's own work (JSON serialization, encoding, HTTP request
handling) runs on daemon threads at minimum thread priority
(`Thread.MIN_PRIORITY`), so under CPU contention it yields to the server
threads. The only thing executed on the server thread is the scoreboard
snapshot itself — a plain copy of player names and scores, sub-millisecond
even for thousands of players — because live server data may only be touched
safely from the server thread. Responses are served from pre-encoded bytes,
so requests allocate almost nothing.

**Linux note:** by default the JVM *ignores* Java thread priorities on Linux.
To make the OS actually schedule the mod's threads below the server threads,
add these flags to your JVM startup arguments (in Pterodactyl: **Startup**
→ JVM arguments):

```
-XX:ThreadPriorityPolicy=1 -XX:JavaPriority1_To_OSPriority=19
```

With these flags the mod's threads run at `nice 19` (the lowest CPU
priority); server threads are unaffected. Lowering priorities does not
require root on any recent JDK. Without the flags the mod still behaves
well — the refresh is cheap and fully asynchronous — the flags just add an
OS-level guarantee on busy hosts.

---

## Assumptions

- **Objective names.** The objective names in the `objectives` config array
  must match the vanilla scoreboard criteria registered on the server. Use
  `/scoreboard objectives list` to find them.
- **Player identity.** The scoreholder's scoreboard name is used as the player
  name. For offline-mode servers this is the username; for online-mode servers
  it is also the username (the scoreboard keys on player names, not UUIDs).
- **Play time unit.** The raw value of a play-time objective depends on how
  the objective is configured on your server. The mod passes the raw integer
  through unchanged — your website is responsible for formatting it.

## License

CC0.
