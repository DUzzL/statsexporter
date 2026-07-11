# Stats Exporter

Stats Exporter is a server-side Fabric mod. It turns chosen
scoreboard objectives into a clean statistics page that is served by your
Minecraft server. Install the mod, choose the objectives, and open the page.
You do not need to build a website, although it is am option.

The mod reads the live scoreboard from the running server. It never reads or
edits `scoreboard.dat`.

## What players and admins get

- A built-in statistics website at `/`
- A sortable player table and headline values
- A TOML config with comments for every setting
- A JSON API at `/api/stats` for anyone who wants to build their own site
- Optional filtering of banned players
- Cached responses and a small request limit to keep the endpoint performant.

## Quick start: use the built-in website

1. Download the release jar and put it in your server's `mods` folder.
2. Open up a new port! Often this is under the Network tab of your panel.
2. Start the server once. It creates `config/statsexporter.toml`.
3. Open that file and set the port plus the scoreboard objectives you want to
   show.
4. Restart the server.
5. Open `http://your-server-address:the-port/` in a browser.

This is enough for testing on your own network. If you want to share the page
publicly, continue with [Put it behind HTTPS](#put-it-behind-https).

### Example configuration

The generated `statsexporter.toml` explains the same options with comments.
This is a complete example:

```toml
# The port for both the dashboard and /api/stats.
port = 8790

# The cache refresh interval. It must be between 5 and 15 minutes.
cacheIntervalMinutes = 10

# Keep this as "*" when you only use the included page. If you build another
# website, put that website's full origin here instead.
allowedOrigin = "*"

# Names from /scoreboard objectives list.
objectives = ["kills", "deaths", "playtime"]

# Hide banned players everywhere.
hideBannedPlayers = true

[dashboard]
# The heading shown on the built-in page.
title = "My Server Statistics"

# An empty list shows every exported objective.
visibleObjectives = ["kills", "playtime"]

# The player ranking starts with the highest kill count.
sortBy = "kills"
sortDirection = "desc"

[dashboard.labels]
# Optional names that look nicer than scoreboard IDs.
kills = "Kills"
playtime = "Play time"
```

To find objective names, run this in the server console:

```
/scoreboard objectives list
```
To get more scoreboard data, I recommend the Track Statistics datapack from
Vanilla Tweaks!
Use the name in square brackets in the `objectives` list. The mod only exports
objectives listed there.

### Dashboard settings

`objectives` controls what the mod reads and exposes. `visibleObjectives`
controls what the included website displays. This means you can export a
statistic for a custom integration without showing it on the public page.

Set `visibleObjectives = []` to display all exported objectives. If `sortBy`
is empty, players are sorted by name. Labels are optional.

## Put it behind HTTPS (Optional)

The mod itself serves plain HTTP. Many browsers and visitors expect a public page to
use HTTPS, so put a reverse proxy in front of it. Cloudflare Workers is a good
option.

First add the configured port as an extra allocation in your host panel. This
is separate from the Minecraft game port. Then test it from your own computer:

```bash
curl -i http://your-server-address:8790/
```

You should receive HTML. This checks that the allocation is open before adding
Cloudflare.

### Cloudflare Worker

Create a Worker in the Cloudflare dashboard and replace its code with this.
Set `upstream` to the public hostname and port from your hosting panel. Set
`allowedOrigin` to the website that should be allowed to call `/api/stats`.
For the included dashboard, use the Worker or custom-domain URL itself. It
forwards both the built-in website and the API.

```js
const upstream = "http://your-server-address:8790";
const allowedOrigin = "https://stats.example.com";

export default {
  async fetch(request) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    if (request.method !== "GET") {
      return new Response("Method Not Allowed", { status: 405 });
    }

    try {
      const response = await fetch(`${upstream}${url.pathname}`, {
        headers: { "Accept": request.headers.get("Accept") || "*/*" },
        cache: "no-store"
      });
      const headers = new Headers(response.headers);
      for (const [key, value] of Object.entries(corsHeaders())) headers.set(key, value);
      return new Response(response.body, { status: response.status, headers });
    } catch {
      return new Response("The statistics server is unavailable.", { status: 502 });
    }
  }
};

function corsHeaders() {
  return {
    "Access-Control-Allow-Origin": allowedOrigin,
    "Access-Control-Allow-Methods": "GET, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type"
  };
}
```

Deploy the Worker and open its `workers.dev` URL. The dashboard should load at
the root URL. You can later attach a custom domain such as
`https://stats.example.com/` in the Worker's **Settings** under **Domains &
Routes**.

If you use a custom domain, change `allowedOrigin` in both places: the Worker
code and `config/statsexporter.toml`. For example, the built-in page at
`https://stats.example.com/` needs this TOML setting:

```toml
allowedOrigin = "https://stats.example.com"
```

> Cloudflare Workers cannot use a bare IP address on a non-standard port.
> Use the hostname supplied by your host instead.

## Custom websites and the API

The included page is optional. Stats Exporter also exposes the same data at
`GET /api/stats`, so a website, Discord bot, or another service can use it.

```js
const response = await fetch("https://stats.example.com/api/stats");
const stats = await response.json();
console.log(stats.players);
```

Example response:

```json
{
  "lastUpdated": "2026-07-04T14:00:00Z",
  "players": [
    { "name": "Steve", "kills": 42, "playtime": 128394 }
  ],
  "dashboard": {
    "title": "My Server Statistics",
    "visibleObjectives": ["kills", "playtime"],
    "labels": { "kills": "Kills", "playtime": "Play time" },
    "sortBy": "kills",
    "sortDirection": "desc"
  }
}
```

The API response is cached according to `cacheIntervalMinutes`. A browser can
only call it from origins allowed by `allowedOrigin` in the TOML config. If you
use the Cloudflare Worker above, set the same origin in its `allowedOrigin`
constant. Use your custom site's full origin, for example:

```toml
allowedOrigin = "https://example.com"
```

Use `*` only when you intentionally want any website to read the API. Server
requests, bots, and `curl` are not restricted by CORS.


## Building from source

Java 25 is required. The Gradle wrapper is included:

```bash
./gradlew build
```

The finished jar is in `build/libs/`.

## Notes

- The mod is server-side only.
- Player names come from scoreboard score holders.
- Play time values are passed through unchanged. Your scoreboard setup decides
  their unit.
- The endpoint allows up to 30 requests per second.

## License

CC0.
