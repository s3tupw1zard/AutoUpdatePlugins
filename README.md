<div align="center">

# **AutoUpdatePlugins v12.2.0**

*Keep your server’s plugins up-to-date - automatically, safely, and across platforms.*

![Platforms](https://img.shields.io/badge/Platforms-Spigot%20%7C%20Paper%20%7C%20Folia%20%7C%20Velocity%20%7C%20BungeeCord-5A67D8)
![MC](https://img.shields.io/badge/Minecraft-1.8%E2%86%92Latest-2EA043)
![Java](https://img.shields.io/badge/Java-8%2B%20\(11%2B%20HTTP%2F2,%2021%2B%20Virtual%20Threads\)-1F6FEB)
![License](https://img.shields.io/badge/License-MIT-0E8A16)

</div>

> **TL;DR**
> Drop in the jar ➜ list the plugins you want ➜ it finds, downloads, validates, and stages updates from a ton of
> sources - on a schedule, in parallel, without blocking your main thread.

---

## Table of Contents

* [Highlights](#highlights)
* [Supported Sources & Platforms](#supported-sources--platforms)
* [Requirements](#requirements)
* [Installation](#installation)
* [Quick Start](#quick-start)
* [Configuration](#configuration)

    * [`config.yml`](#configyml)
    * [`list.yml`](#listyml)
    * [Scheduling Cheat Sheet](#scheduling-cheat-sheet)
    * [Performance Tuning Guide](#performance-tuning-guide)
* [Commands & Permissions](#commands--permissions)
* [How It Works (Under the Hood)](#how-it-works-under-the-hood)
* [Examples for Every Source](#examples-for-every-source)
* [Troubleshooting & FAQ](#troubleshooting--faq)
* [Building from Source](#building-from-source)
* [Security Notes](#security-notes)
* [Contributing, Issues & Support](#contributing-issues--support)
* [License](#license)

---

## Highlights

* **One list, many sources.** Pull updates from GitHub Releases/Actions, GitLab packages, Jenkins, PlaceholderAPI
  eCloud, VoxelShop/Polymart, SpigotMC (Spiget), dev.bukkit, Modrinth, Hangar, BusyBiscuit, blob.build, Guizhanss v2,
  MineBBS, CurseForge, or any page with a direct `.jar` link.
* **Local sources.** Point at local jar files or run scripts that output a jar path for custom/patched builds.
* **Smart file selection.** Use `?get=<regex>`, `[N]` (pick the N-th asset), `?artifact=2` / `?index=2` / `?zip=2` for
  GitHub Actions bundles, `?prerelease=true` (or `?pre-release=true`), `?alpha=true`, `?beta=true`, `?latest=true`,
  `?channel=Alpha`, `?autobuild=true`, `?branch=dev` - mix and match to land on the exact build you need.
* **Per-entry install routing.** Override `filePath` and `updatePath` directly in each `list.yml` line, and choose
  whether that entry uses an update folder (`useUpdateFolder=true|false`).
* **Zero-friction config evolution.** New options are **auto-added** to `config.yml` without clobbering your comments or
  existing values.
* **HTTP flexibility.** Add custom headers, rotate User-Agents, and route through proxies when needed.
* **Performance built-in.**

    * Fully **async** downloads; no main-thread stalls.
    * **Java 11+ HTTP/2** client for modern, non-blocking transfers.
    * **Java 21+ virtual threads** for ultra-lightweight concurrency.
    * **Connection pooling**, **parallel downloads**, and **retry with exponential backoff**.
* **Flexible scheduling.** Interval + boot delay *or* cron with timezone support.
* **Cross-platform.** Works on **Spigot**, **Paper**, **Folia**, **Velocity**, **BungeeCord**.
* **Safe updates.** Download ➜ validate ➜ atomic replace, staged through temp/update paths.
* **Integrity + dedupe.** Optional zip integrity checks and MD5 comparison skip corrupted or unchanged downloads.
* **Metadata-aware updates.** GitHub, GitLab, Jenkins, Modrinth, Hangar, eCloud, and VoxelShop skip unchanged payloads
  before spending bandwidth; generic direct URLs can opt in to strong-validator HEAD checks.
* **Compatibility and policy controls.** Filter Modrinth/Hangar by Minecraft version and limit each entry to patch,
  same-major, any, or no automatic version transitions.
* **Operator visibility.** Daily update history, `/aup log`, bounded installed-to-selected changelog ranges for
  GitHub and Modrinth (with selected-release fallback elsewhere), pending-update details, and a paginated
  Spigot/Paper/Folia inventory GUI.
* **Rollback safety net.** Snapshot the previous jar and auto-restore if the new build fails to start.

---

## Supported Sources & Platforms

### Download Sources

| Source                | Release/Build Discovery | Notes / Selectors Supported                                                                                            |
|-----------------------|-------------------------|------------------------------------------------------------------------------------------------------------------------|
| **GitHub**            | Releases & Actions      | `[N]`, `?artifact=2`, `?index=2`, `?zip=2`, `?get=regex`, `?prerelease=true`, `?pre-release=true`, `?alpha=true`, `?beta=true`, `?latest=true`, `?autobuild=true`, `?branch=dev` |
| **GitLab**            | Generic/Maven packages  | `[N]`, `?get=regex`, `?packageType=generic|maven`, `?packageName=...`, `?account=name`                                  |
| **Jenkins**           | Latest successful build | Any HTTP(S) `/job/` URL; `[N]`, `?index=N`, `?get=regex`                                                               |
| **PlaceholderAPI eCloud** | Expansion releases | ExtendedClip/eCloud expansion URL; installs to `plugins/PlaceholderAPI/expansions` by default                            |
| **VoxelShop / Polymart** | Resource/update API  | Public resources auto-download; paid resources use `updates.voxelShopTokens.default` or `?account=name`, otherwise a manual action is queued |
| **SpigotMC (Spiget)** | Resource page URL       | Auto-resolves latest                                                                                                   |
| **dev.bukkit**        | Project page            | Auto-resolves latest                                                                                                   |
| **Modrinth**          | Project/version URL     | `?get=regex`, `?loader=velocity`, `?platform=bungeecord`, `?alpha=true`, `?beta=true`, `?latest=true`                  |
| **Hangar**            | Project/releases        | `?get=regex`, `?alpha=true`, `?beta=true`, `?latest=true`, `?channel=release|beta|alpha|latest`                      |
| **BusyBiscuit**       | Project index           | Auto-resolves                                                                                                          |
| **blob.build**        | Build artifacts         | `?get=regex`                                                                                                           |
| **Guizhanss v2**      | Project index           | Auto-resolves                                                                                                          |
| **MineBBS**           | Resource page           | Auto-resolves                                                                                                          |
| **CurseForge**        | Project/files           | `?get=regex`                                                                                                           |
| **Generic**           | Direct `.jar` link      | Exact file URL; optional strong ETag/Last-Modified HEAD metadata                                                       |
| **Local file**        | Local path              | `file:`, `local:`, `path:`, or absolute/relative path                                                                  |
| **Local script**      | Script stdout path      | `script:` or `exec:` (script prints jar path to stdout)                                                                |

> **Note:** GitHub and Jenkins links both accept `[N]` to pick the **N-th** artifact on their release/build pages (
> 1-indexed).
> **Tip:** You can pass just the **project root URL** for most sources (e.g., a GitHub repo or Spigot resource page) and
> let AutoUpdatePlugins choose the latest artifact. Add selectors for precision.

### Server Platforms

* **Spigot**, **Paper**, **Folia**
* **Velocity**, **BungeeCord**

### Minecraft & Java

* **Minecraft:** 1.8 → Latest
* **Java:** 8+ (uses advanced features automatically on 11+ and 21+)

---

## Requirements

* **Java 8 or newer.**

    * Uses Java 11+ HTTP/2 client when available.
    * Uses Java 21+ virtual threads when available.
* **Network access** for source builds (Gradle/Maven wrappers or system `gradle`/`mvn`, if used).

---

## Installation

1. Download the latest `.jar` from **[Spigot](https://www.spigotmc.org/resources/autoupdateplugins.109683/)**.
2. Place it into your server’s **`plugins/`** directory.
3. Start the server to generate default config files.
4. Edit **`plugins/AutoUpdatePlugins/config.yml`** and **`plugins/AutoUpdatePlugins/list.yml`**.
5. Run **`/update`** or **`/aup update`** (or restart) to kick off the first check.

> **Velocity / BungeeCord:** Same process, but management commands are `/vaup ...` and `/baup ...` respectively so
> the proxy does not shadow a backend server's `/aup` command.

---

## Quick Start

1. Add a few entries to `list.yml`:

   ```yaml
   ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
   Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
   EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
   ```
2. Leave `updates.interval` at the default (every 120 minutes), or set a cron schedule.
3. Optional: Add a GitHub token in `config.yml` to avoid rate limits.
4. Trigger a run:

   ```
   /update
   ```
5. New jars are staged atomically in your plugins folder (using temp/update paths under the hood). **Restart** the
   server to load the updated jars.

---

## Configuration

### `config.yml`

> The plugin **adds new options automatically** without overwriting your comments. Below is the full schema with
> defaults and notes.

```yaml
################################################################################
# AutoUpdatePlugins - Main Configuration
################################################################################

updates:
  # How often to run plugin updates (minutes). Default: 120 (every 2 hours)
  interval: 120

  # Delay after server startup (seconds) before the first run. Default: 50
  bootTime: 50

  # Schedule (experimental): Use a cron expression to control exactly when
  # updates run. When set, this overrides both interval and bootTime.
  # Examples:
  #   Every day at 03:30: "30 3 * * *"
  #   Every 15 minutes:   "*/15 * * * *"
  #   At 5 past every hour on weekdays: "5 * * * 1-5"
  schedule:
    cron: ""         # Cron expression (UNIX 5-field). Leave blank to disable.
    timezone: "UTC"  # Timezone for the cron schedule, e.g. "America/New_York"

  # Optional GitHub personal access token (PAT). Strongly recommended if you use
  # many GitHub links to avoid API rate-limits when listing releases/artifacts.
  # Scope: public_repo is enough for public repos.
  # Generate a token: https://github.com/settings/tokens
  key:
  # Optional named GitHub tokens. Use ?account=name on a GitHub entry to select one.
  githubTokens: {}
  # Optional named GitLab PRIVATE-TOKEN values. Use ?account=name on a GitLab entry.
  gitlabTokens: {}
  # Optional VoxelShop user tokens for paid resources the account is entitled to.
  # A "default" entry is used when the resource URL has no ?account=name selector.
  voxelShopTokens: {}

# HTTP configuration (optional)
http:
  # If blank, a rotating pool of realistic User-Agents will be used.
  userAgent: ""
  # Extra request headers added to every request. Only add if you know you need it.
  headers: [ ]
  # Verify TLS certificates (set to false to trust all; only if you must)
  sslVerify: true
  # Optional pool of User-Agents; the plugin will rotate between them to avoid
  # strict CDNs blocking automation.
  userAgents:
    - { ua: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36" }
    - { ua: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15" }
    - { ua: "Mozilla/5.0 (X11; Linux x86_64; rv:126.0) Gecko/20100101 Firefox/126.0" }

# Proxy configuration (optional)
proxy:
  type: "NONE" # HTTP | SOCKS | (anything else = disabled)
  host: "proxy.example"
  port: 8080

# Behavior toggles
behavior:
  useUpdateFolder: true
  # Check for updates without installing them automatically. Scheduled manual-mode
  # runs still check every updates.interval after updates.bootTime. Entries with
  # ?auto=true still install during scheduled manual-mode runs.
  manualMode: false
  # Open downloaded .jar/.zip to ensure integrity before install (recommended)
  zipFileCheck: true
  # Skip replacing an existing plugin if the new jar has the same MD5
  ignoreDuplicates: true
  # Allow GitHub/Modrinth/Hangar pre-releases by default for release queries.
  allowPreRelease: false
  # Enable source-build fallback for GitHub repositories.
  autoCompile:
    enable: false
    # Build when a release has no .jar asset (zip-only)
    whenNoJarAsset: true
    # Build from source if default branch is newer than latest (pre)release by N months
    branchNewerMonths: 6
  # Verbose debug logging. Toggle with /aup debug on|off
  debug: false
  # Restart the server/proxy automatically after updates.
  restartAfterUpdate: false
  # Delay in seconds before restarting after updates.
  restartDelaySec: 5
  # Broadcast message before restarting (supports {delay}).
  restartMessage: "Server restarting to apply updates."
  # Optional console command executed when restart is scheduled.
  # Supports placeholders: {delay}, {seconds}, {timeToRestart}, {totalDelay}, {totalSeconds}
  preRestartCommand: ""
  # Optional timed pre-restart actions.
  # Each entry may define:
  #   timeToRestart: <seconds before restart>
  #   command: "<console command>"
  #   message: "<broadcast message>"
  # Entries where timeToRestart > restartDelaySec are skipped.
  restartCommands: [ ]

# Lightweight provider metadata cache. Provider metadata is checked each run;
# unchanged artifacts skip the large payload download.
metadata:
  enabled: true
  file: "metadata.json"
  ttlMinutes: 0
  skipDownloadWhenUnchanged: true
  # Opt in to lightweight HEAD metadata for generic direct URLs. Only strong ETags
  # or Last-Modified are accepted; weak W/ ETags are ignored.
  directUrlHeadMetadata: false
  # Blank auto-detects on Spigot/Paper/Folia; set explicitly on proxies.
  minecraftVersion: ""

compatibility:
  modrinthMinecraftVersionCheck: true
  hangarMinecraftVersionCheck: true
  strictMinecraftVersionMetadata: false

versioning:
  # any | patch (same X.Y) | same-major | none
  policy: "any"
  unknownVersionPolicy: "allow"
  allowSameVersionSnapshotUpdates: true
  allowSameVersionReleaseHashUpdates: false

logging:
  updates:
    enabled: true
    path: "logs"
    filePattern: "yyyy-MM-dd'.log'"
    commandPageSize: 8
    includeUnchanged: false
    includeChecks: true

# Optional custom paths
paths:
  tempPath: ''
  updatePath: ''
  filePath: ''
  rollbackPath: ''

# Performance and reliability options
performance:
  # Maximum parallel downloads. Higher is faster but uses more IO/CPU.
  # If set above CPU cores, it is clamped internally.
  maxParallel: 4
  # HTTP connect timeout in milliseconds per request.
  connectTimeoutMs: 10000
  # HTTP read timeout in milliseconds per request.
  readTimeoutMs: 30000
  # Optional per-download hard timeout in seconds. 0 disables the cap.
  perDownloadTimeoutSec: 0
  # Retry behavior for transient HTTP errors (403/429/5xx)
  maxRetries: 3
  # Exponential backoff base and max delay in milliseconds between retries
  backoffBaseMs: 500
  backoffMaxMs: 5000
  # Limit concurrent downloads per host to avoid 429s and improve stability
  maxPerHost: 3

rollback:
  # Keep a backup of the previous version when updating a plugin and automatically revert back on plugin load failure (experimental!)
  enabled: false
  # Restart automatically after rollback restores plugin files. Rollback itself remains disabled unless rollback.enabled is true.
  restartAfterRollback: true
  # Maximum number of old versions to keep per plugin (0 = unlimited)
  maxBackups: 3
  # Case-insensitive regex patterns that trigger rollback when matched in logs.
  filters:
    - "Unsupported API version"
    - "Could not load plugin"
    - "Error occurred while enabling"
    - "Unsupported MC version"
    - "You are running an unsupported server version"

# Plugins List Configuration
# Edit the generated list.yml in this folder. Format:
#   {FileSaveName}: {link.to.plugin}
#
# Example list.yml template: https://github.com/NewAmazingPVP/AutoUpdatePlugins/blob/main/list.yml
# Examples:
#   ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
#   Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
#   EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
#
# Supported sources: GitHub, GitLab packages, Jenkins, PlaceholderAPI eCloud, SpigotMC, dev.bukkit, Modrinth,
# Hangar, BusyBiscuit, blob.build, Guizhanss v2, MineBBS, CurseForge, local files/scripts, and direct .jar links.
#
# Tips:
# - Select assets: append [N] to pick the Nth asset or use ?get=<regex> to match by filename.
# - GitHub Actions ZIPs: use ?artifact=2 (or ?index=2 / ?zip=2) to pick a specific build, default is the first.
# - Pre-releases: set behavior.allowPreRelease: true or append ?prerelease=true on a GitHub link.
# - Channel flags: ?beta=true, ?alpha=true, ?latest=true, or ?channel=Alpha/Beta for Hangar.
# - Modrinth/Hangar obey the same flags (?alpha, ?beta, ?latest) to pick non-release builds when desired.
# - Modrinth loader override: append ?loader=velocity or ?platform=bungeecord when the detected server platform is not the desired target.
# - Compatibility: append ?versionCheck=true&mcVersion=1.21.8 (Spigot-family servers auto-detect by default).
# - Version policy: append ?versionPolicy=patch|same-major|any|none; ?force=true bypasses policy/cache for one run.
# - Groups are supported: add `GroupName:` and indent plugin entries beneath it.
# - Per-entry install path override: append | plugins/SomeFolder/ (legacy) or | filePath=... | updatePath=... | useUpdateFolder=true
# - Force source build from GitHub: append ?autobuild=true to a GitHub repo URL.
# - Pick a non-default branch for GitHub source builds: append ?branch=dev (works with ?autobuild=true).
# - Missing source-build libraries: repeat URL-encoded ?buildLib=group:artifact:version=<jar-url> declarations.

```

#### Safety & Recovery

- `behavior.zipFileCheck`: validates `.jar` downloads extracted from archives before they ever reach the plugins
  directory.
- `behavior.ignoreDuplicates`: skips deployment if the incoming jar matches the installed checksum, avoiding pointless
  restarts.
- `rollback.*`: snapshots the previous jar, keeps a rotating history, and restores automatically when console output
  matches the configured failure patterns. `rollback.maxBackups` also trims archived failed jars per plugin
  (`0` disables trimming), and `rollback.restartAfterRollback` schedules a restart after a successful restore.

#### Advanced Selectors Cheat Sheet

| Flag / Selector              | Effect                                                                               | Providers                      |
|------------------------------|--------------------------------------------------------------------------------------|--------------------------------|
| `?get=<regex>`               | Picks assets whose filename matches a regex                                          | GitHub, Jenkins, Modrinth, etc |
| `[N]`                        | Chooses the N-th asset (1-indexed)                                                   | GitHub Releases/Actions, Jenkins |
| `?artifact=2` / `?index=` / `?zip=` | Chooses the N-th GitHub Actions artifact (defaults to 1)                       | GitHub Actions                 |
| `?prerelease=true` / `?pre-release=true` | Install the newest build regardless of stability tier                     | GitHub, Modrinth, Hangar       |
| `?alpha=true` / `?beta=true` | Prefer that channel while falling back to newer releases if no matching build exists | GitHub, Modrinth, Hangar       |
| `?latest=true`               | Same as `?prerelease=true`, but intended for explicit "always newest" behaviour      | GitHub, Modrinth, Hangar       |
| `?channel=release|beta|alpha|latest` | Channel preference (works with Hangar channel names and release-tier preference) | Hangar, GitHub, Modrinth       |
| `?loader=<name>` / `?platform=<name>` | Override the detected target loader/platform                                    | Modrinth                       |
| `?versionCheck=true` / `?mcVersion=1.21.8` | Require an exact compatible Minecraft release                              | Modrinth, Hangar               |
| `?versionPolicy=patch|same-major|any|none` | Limit the allowed local-to-remote version transition                         | Metadata-aware providers       |
| `?force=true`                 | Bypass metadata cache and version policy for this run                               | Metadata-aware providers       |
| `?cache=false`                | Disable metadata payload skipping for this entry                                    | Metadata-aware providers       |
| `?autobuild=true`            | Trigger Gradle/Maven source builds when binaries are missing                         | GitHub                         |
| `?branch=<name>`             | Build a specific GitHub branch instead of the default branch                         | GitHub                         |
| `?buildLib=group:artifact:version=<jar-url>` | Provision a missing Maven/Gradle dependency; repeat for multiple libraries | GitHub source builds           |
| `?account=<name>`            | Select a provider-scoped named token instead of its fallback/default                 | GitHub, GitLab, VoxelShop      |
| `?author=<name>`             | Alias for selecting a named token                                                     | GitHub, GitLab                 |
| `?auto=true`                 | In manual mode, allow this entry to install during scheduled runs                    | All providers                  |

**Key options explained**

* **`updates.schedule.cron` + `timezone`** - When set, cron **overrides** `interval`/`bootTime`.
* **`updates.key`** - GitHub PAT for Releases/Actions access and higher rate limits (especially useful for public repos
  under heavy use or any private repos).
* **`updates.githubTokens`** - Optional named GitHub PATs selected per entry with `?account=name`; `updates.key` remains the fallback.
* **`updates.gitlabTokens`** - Optional named GitLab `PRIVATE-TOKEN` values selected with `?account=name`; tokens are
  sent in headers and stripped before cross-origin payload redirects.
* **`updates.voxelShopTokens`** - Optional VoxelShop user tokens for entitled paid resources. The `default` key is used
  without a selector; `?account=name` selects only that name from the VoxelShop map. Public resources need no token. A
  paid resource stays pending with its product-page link when no token is configured or the API declines the download.
* **`metadata`** - Persists lightweight provider release/build IDs and target hashes, avoiding repeat payload downloads
  while invalidating the hit if the managed jar is replaced externally.
* **`metadata.directUrlHeadMetadata`** - When enabled, generic HTTP(S) entries make a lightweight HEAD request before
  downloading. A strong ETag or Last-Modified value can identify an unchanged payload; weak `W/` ETags are rejected,
  and URLs without a usable validator fall back to the normal download-and-compare flow.
* **`compatibility`** - Enables Minecraft-version filtering for Modrinth and Hangar. Proxies require
  `metadata.minecraftVersion`; Spigot-family servers auto-detect it.
* **`versioning`** - Controls version-transition policy globally; per-entry selectors override it.
* **`logging.updates`** - Configures daily history files and chat pagination for the log command.
* **`http.userAgents`** - Simple rotation to avoid brittle server-side filters.
* **`proxy`** - Full support for HTTP/SOCKS proxies.
* **`behavior.autoCompile`** - For GitHub repos: if there’s no release jar (or if the branch is newer than the last
  release by `branchNewerMonths`), the repo can be **built from source** using Gradle/Maven.
* **`behavior.zipFileCheck`** - Open downloaded jars/zips to ensure they aren't corrupt before install.
* **`behavior.ignoreDuplicates`** - Skip replacing a plugin if the new jar has the same MD5.
* **`behavior.useUpdateFolder`** - Stage new jars in the server’s `update/` directory for atomic swaps.
* **`behavior.restartAfterUpdate`** - Automatically restart after updates (delay via `behavior.restartDelaySec`, message
  via `behavior.restartMessage`).
* **`behavior.preRestartCommand`** - Run a console command when restart is scheduled.
* **`behavior.restartCommands`** - Run multiple timed pre-restart commands/messages (for example at 60/30/5 seconds).
* **`behavior.debug`** - Verbose logging toggle, also controllable via `/aup debug`.
* **Manual workflow:** enable `behavior.manualMode` to turn scheduled runs and `/update` into check-only passes, then use `/aup pending` and `/aup update`. Scheduled manual checks still use `updates.interval`/`bootTime`; add `?auto=true` to entries that should keep installing automatically.
* **`rollback`** - Configure automatic snapshots, retention, log-match triggers, and `rollback.restartAfterRollback`
  for self-healing updates.
* **`paths`** - Customize temp/staging/output locations (falls back to sane defaults).
* **`performance`** - See [Performance Tuning Guide](#performance-tuning-guide).

**Pre-restart commands example**

```yaml
behavior:
  restartAfterUpdate: true
  restartDelaySec: 60
  restartMessage: "&eServer restart in {seconds}s"
  preRestartCommand: "lp broadcast &eServer restart scheduled in {seconds}s"
  restartCommands:
    - timeToRestart: 30
      command: "lp broadcast &eRestart in {timeToRestart}s"
    - timeToRestart: 5
      message: "&cRestart in {timeToRestart}s"
```

---

### `list.yml`

A simple mapping of **plugin display name -> source string**, with optional selectors and per-entry path overrides.

```yaml
# A list of plugins to update.
# Format: <plugin-name>: <source>
# Source can be a URL, local file path (`file:`, `local:`, `path:`), or script (`script:`, `exec:`).

ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
Geyser: "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot"
EssentialsXChat: "https://github.com/EssentialsX/Essentials[3]"
GeyserExtension: "https://github.com/MCXboxBroadcast/Broadcaster[1] | plugins/Geyser-Spigot/extensions/"
GeyserExtensionStaged: "https://github.com/MCXboxBroadcast/Broadcaster[1] | filePath=plugins/Geyser-Spigot/extensions | updatePath=plugins/Geyser-Spigot/extensions/update | useUpdateFolder=true"

Core:
  LuckPerms: "https://www.spigotmc.org/resources/luckperms.28140/"
  PlaceholderAPI: "https://www.spigotmc.org/resources/placeholderapi.6245/"
```

> Comment out a line with `#` to temporarily disable an entry. The `/aup enable` and `/aup disable` commands toggle this
> for you.
> The active file on your server is `plugins/AutoUpdatePlugins/list.yml`.
> Groups can be targeted by name in commands, or with `group:<name>` to force a group match.

**Starter `list.yml` example**

```yaml
AuthMeReloaded: https://ci.codemc.io/job/AuthMe/job/AuthMeReloaded/[4]
BlueSlimeCore: https://www.spigotmc.org/resources/blueslimecore.83189/
Chunky: https://www.spigotmc.org/resources/chunky.81534/
ChunkyBorder: https://www.spigotmc.org/resources/chunkyborder.84278/
DiscordSRV: https://get.discordsrv.com/
Dynmap: https://dev.bukkit.org/projects/dynmap
EasyPrefix: https://www.spigotmc.org/resources/easyprefix-gui-custom-prefixes-sql-support.44580/
EssentialsX: https://github.com/EssentialsX/Essentials
EssentialsXChat: https://github.com/EssentialsX/Essentials[3]
FAWE: https://ci.athion.net/job/FastAsyncWorldEdit/
Floodgate: https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot
Geyser-spigot: https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot
GeyserExtension: "https://github.com/MCXboxBroadcast/Broadcaster[1] | plugins/Geyser-Spigot/extensions/"
GeyserExtensionStaged: "https://github.com/MCXboxBroadcast/Broadcaster[1] | filePath=plugins/Geyser-Spigot/extensions | updatePath=plugins/Geyser-Spigot/extensions/update | useUpdateFolder=true"
InventoryRollbackPlus: https://www.spigotmc.org/resources/inventory-rollback-plus.85811/
lssmp: https://www.spigotmc.org/resources/lifesteal-smp-plugin.94387/
LuckPerms: https://www.spigotmc.org/resources/luckperms.28140/
PlaceholderAPI: https://www.spigotmc.org/resources/placeholderapi.6245/
PlayerStats: https://www.spigotmc.org/resources/playerstats.102347/
ProtocolLib: https://ci.dmulloy2.net/job/ProtocolLib/
SkinsRestorer: https://ci.codemc.io/job/SkinsRestorer/job/SkinsRestorerX-DEV/
TAB: https://github.com/NEZNAMY/TAB
ViewDistanceTweaks: https://www.spigotmc.org/resources/view-distance-tweaks.75164/
Voicemod: https://modrinth.com/plugin/simple-voice-chat/
ViaVersion-Dev: https://ci.viaversion.com/job/ViaVersion-DEV/
ViaBackwards: https://hangar.papermc.io/ViaVersion/ViaBackwards
Worldedit: https://dev.bukkit.org/projects/worldedit
Worldguard: https://dev.bukkit.org/projects/worldguard
```

**Selectors you can use**

* **`[N]`** - Select the **N-th** jar asset (1-indexed) from GitHub/Jenkins listings. Example: `.../Essentials[3]`
* **`?get=<regex>`** - Choose files whose names match a regex. Example: `?get=.*(paper|spigot).*\.jar`
* **`?artifact=2`** / **`?index=2`** / **`?zip=2`** - Pick a specific GitHub Actions artifact (defaults to first).
* **`?prerelease=true`** / **`?pre-release=true`** - Permit pre-releases.
* **`?alpha=true`**, **`?beta=true`**, **`?latest=true`** - Fine-tune release channel preference.
* **`?channel=release|beta|alpha|latest`** - Explicit channel preference (especially useful on Hangar).
* **`?versionCheck=true&mcVersion=<version>`** - Require an exact Modrinth/Hangar Minecraft version match.
* **`?versionPolicy=patch|same-major|any|none`** - Restrict allowed version transitions for one entry.
* **`?force=true`** - Bypass metadata and version-policy skips for a deliberate one-off refresh.
* **`?autobuild=true`** - Force a source build on GitHub even if a jar asset exists.
* **`?branch=<name>`** - Use a specific GitHub branch for source builds/autobuild instead of the default branch.
* **`?buildLib=<coordinates>=<encoded-jar-url>`** - Supply a missing dependency to GitHub Maven/Gradle builds;
  repeat the parameter for multiple libraries.
* **`?account=<name>`** - Use a named GitHub, GitLab, or VoxelShop token from that provider's matching token map.
* **`?auto=true`** - In manual mode, this entry still installs during scheduled runs while the rest are check-only.

> **Pro tip:** Combine selectors, e.g. `...?prerelease=true&get=.*spigot.*\.jar`.

**Per-entry path options**

* **Legacy syntax:** `... | plugins/SomeFolder/` (treated as `filePath` override).
* **`filePath=<dir>`** - Per-entry destination directory for the installed jar.
* **`updatePath=<dir>`** - Per-entry update staging directory (used when `useUpdateFolder=true`), even if `filePath` is not overridden.
* **`useUpdateFolder=true|false`** - Per-entry staging toggle.
* **Default behavior:** if an entry sets `filePath`, `useUpdateFolder` defaults to **`false`** for that entry unless you explicitly set it to `true`.

---

### Scheduling Cheat Sheet

Common cron expressions (with examples using `America/New_York`):

* **Every 2 hours:** `0 */2 * * *`
* **Every day at 05:00:** `0 5 * * *`
* **Every Monday & Thursday at 03:30:** `30 3 * * 1,4`
* **Every 15 minutes:** `*/15 * * * *`

If cron is empty, the plugin uses **`interval`** (minutes) with an initial **`bootTime`** delay (seconds).

---

### Performance Tuning Guide

* **Java 21+ (virtual threads):** Keep `performance.maxParallel` higher (e.g., 8–16) for many small artifacts.
* **Java 11+ (HTTP/2):** Great default throughput. Tune `maxPerHost` if a single origin hosts most of your jars.
* **Java 8:** Increase `readTimeoutMs` if you see timeouts on large downloads; lower `maxParallel` if network is
  saturated.
* **Retries:** The plugin retries **`maxRetries`** times with exponential backoff (`backoffBaseMs` → `backoffMaxMs`).
* **Per-download timeout:** Set `perDownloadTimeoutSec` to protect against stuck transfers; keep `0` to disable.
* **I/O Paths:** Put `tempPath`/`updatePath` on fast local storage if possible.

---

## Commands & Permissions

### Commands

* **`/update`** - Trigger the default scheduled behavior. If `behavior.manualMode=true`, this is check-only.
* **`/aup download [names|groups...]`** - Install all, or only the selected plugins/groups.
* **`/aup update [names|groups...]`** - Alias of `download`.
* **`/aup check [names|groups...]`** - Check all, or only the selected plugins/groups, without installing them.
* **`/aup pending`** - Show updates found during check-only runs that still need a manual install.
* **`/aup log [today|yesterday|yyyy-MM-dd|page] [page]`** - Read paginated update history.
* **`/aup gui [page]`** - Open the paginated inventory manager on Spigot/Paper/Folia. Left-click checks,
  shift-left installs, and right-click enables/disables an entry.
* **`/aup stop`** - Request to stop the current updating process.
* **`/aup reload`** - Reload the plugin configuration.
* **`/aup add <name> <link>`** - Add a new entry to `list.yml`.
* **`/aup remove <name|group>`** - Remove one or more entries from `list.yml`.
* **`/aup list [page]`** - View the configured plugin list.
* **`/aup debug <on|off|toggle|status>`** - Toggle verbose logging and persist the setting.
* **`/aup enable|disable <name|group>`** - Toggle one or more entries (comment/uncomment in `list.yml`).

### Permissions

* `autoupdateplugins.update` - Allows `/update`.
* `autoupdateplugins.manage` - Allows `/aup ...` commands.

> On Velocity use `/vaup ...`; on BungeeCord use `/baup ...`. These proxy-only names intentionally leave backend
> `/aup` available to connected players. Inventory GUI commands are available only on Spigot/Paper/Folia.

> **Heads-up:** Most servers still require a **restart** to load updated jars. The plugin handles download & staging;
> you decide when to reboot (auto-restart available via `behavior.restartAfterUpdate`).

---

## How It Works (Under the Hood)

1. **Discovery.** For each entry in `list.yml`, the plugin detects the provider and computes the latest matching,
   policy-allowed, platform-compatible artifact using your selectors.
2. **Metadata preflight.** Provider release/build IDs are compared with `metadata.json`; an unchanged target skips the
   payload entirely. Changing the selected asset, target path, managed jar, or using `?force=true` invalidates the hit.
3. **Download (async).** Files are fetched with pooled connections; on Java 11+ it uses the HTTP/2 client; on Java 21+
   it schedules downloads on virtual threads when available.
4. **Validation.** Provider size/checksums, JAR integrity, plugin metadata, and duplicate hashes are checked before a
   managed target can be changed.
5. **Staging & Atomic Replace.** Files are written to a **temp** directory, then moved atomically into the
   **update/plugins** target so the server is never left in a partial state.
6. **Record.** Cache state and daily update/check/failure history are persisted, including bounded
   installed-to-selected GitHub/Modrinth changelog ranges and selected-release notes from other providers.
7. **Repeat.** According to your **interval** or **cron** schedule, with retries/backoff on transient failures.

---

## Examples for Every Source

> **Patterns below are illustrative.** Use your project’s actual URLs and apply selectors as needed.

* **GitHub Releases (pick 2nd asset, spigot-only):**

  ```
  MyPlugin: "https://github.com/Owner/MyPlugin[2]?get=.*spigot.*\\.jar"
  ```
* **GitHub Actions (latest alpha artifact, fallback to source build):**

  ```
  MyActionsPlugin: "https://github.com/Owner/MyActionsPlugin/actions?alpha=true&artifact=1"
  MyActionsPluginSource: "https://github.com/Owner/MyActionsPlugin?autobuild=true"
  MyActionsPluginDev: "https://github.com/Owner/MyActionsPlugin?autobuild=true&branch=dev"
  ```
* **Jenkins (any Jenkins job host; match a shaded jar or select by index):**

  ```
  CoolThing: "https://ci.example.com/job/CoolThing/lastSuccessfulBuild/artifact/?get=.*-all\\.jar"
  # second artifact
  CoolThingAlt: "https://ci.example.com/job/CoolThing/lastSuccessfulBuild/artifact/[2]"
  ```
* **GitLab package registry (named token is optional for public projects):**

  ```
  OpenCreative: "https://gitlab.com/eagles-creative/opencreative/-/packages?packageType=maven&get=.*\.jar&account=gitlab-main"
  ```
* **PlaceholderAPI eCloud / ExtendedClip expansion:**

  ```
  LocalTimeExpansion: "https://api.extendedclip.com/expansions/localtime/"
  ```
* **VoxelShop / legacy Polymart resource URLs:**

  ```
  # Public resource: the official API supplies a signed download URL without a token.
  PublicResource: "https://voxel.shop/product/12345"
  # Paid resource: selects updates.voxelShopTokens.voxel-main.
  PaidResource: "https://voxel.shop/product/67890?account=voxel-main"
  # Existing polymart.org resource links are accepted during the VoxelShop transition.
  LegacyResource: "https://polymart.org/resource/example-plugin.12345"
  ```

  A paid resource without an authorized token is kept as a pending manual action with its VoxelShop product link; the
  updater does not claim to bypass marketplace purchase or entitlement checks.
* **SpigotMC (resource page):**

  ```
  ViaVersion: "https://www.spigotmc.org/resources/viaversion.19254/"
  ```
* **dev.bukkit:**

  ```
  Vault: "https://dev.bukkit.org/projects/vault"
  ```
* **Modrinth (match platform flavor, latest alpha build):**

  ```
  Fancy: "https://modrinth.com/plugin/fancy?alpha=true&get=.*(paper|spigot).*\\.jar"
  VelocityFancy: "https://modrinth.com/plugin/fancy?loader=velocity"
  ```
* **Hangar (snapshot channel):**

  ```
  HangarThing: "https://hangar.papermc.io/Owner/Project?channel=Alpha&get=.*spigot.*\\.jar"
  ```
* **CurseForge (filter file name):**

  ```
  CFThing: "https://www.curseforge.com/minecraft/bukkit-plugins/cfthing/files?get=.*release.*\\.jar"
  ```
* **Generic direct jar:**

  ```
  DirectJar: "https://downloads.example.com/plugins/DirectJar-1.2.3.jar"
  ```
* **Custom install folder (direct replace):**

  ```
  GeyserExtension: "https://github.com/MCXboxBroadcast/Broadcaster[1] | plugins/Geyser-Spigot/extensions/"
  ```
* **Custom install folder (stage in custom update folder):**

  ```
  GeyserExtension: "https://github.com/MCXboxBroadcast/Broadcaster[1] | filePath=plugins/Geyser-Spigot/extensions | updatePath=plugins/Geyser-Spigot/extensions/update | useUpdateFolder=true"
  ```
* **Local file (patched jar on disk):**

  ```
  PatchedPlugin: "file:/home/me/builds/PatchedPlugin.jar"
  ```
* **Local script (builds jar and prints path):**

  ```
  PatchedPlugin: "script:./scripts/build-plugin.sh"
  ```
* **GitHub source build with a manually supplied library:**

  ```
  CustomBuild: "https://github.com/Owner/Project?autobuild=true&buildLib=com.example:api:1.0=https%3A%2F%2Fexample.com%2Fapi-1.0.jar"
  ```

---

## Troubleshooting & FAQ

**Q: Updates downloaded, but server didn’t change.**
A: Most platforms load jars only at startup. **Restart** your server after a run. Avoid hot-reloaders for complex
plugins.

**Q: GitHub rate-limited / cannot access Actions artifacts.**
A: Add a **GitHub PAT** in `config.yml` → `updates.key`. For private repos, ensure the token has read access to
Releases/Actions artifacts.

**Q: Different GitHub repos need different tokens.**
A: Add named tokens under `updates.githubTokens`, then add `?account=name` to the matching GitHub entry. If the account is missing, `updates.key` is used.

**Q: How do private GitLab package registries work?**
A: Put a least-privileged token under `updates.gitlabTokens.<name>` and add `?account=name` to the project/packages URL.
Generic and Maven package registries are supported; use `?packageType=` and `?get=` when a project publishes several jars.

**Q: How do paid VoxelShop resources work?**
A: Add the VoxelShop user token for an account that already owns the resource under
`updates.voxelShopTokens.default`, or use a named entry and add `?account=name` to that VoxelShop/Polymart resource URL.
Public resources do not need a token. Missing or rejected authorization produces a pending manual-download action.

**Q: Why did the updater skip the payload?**
A: A metadata-aware provider returned the same release/build and the managed target still matched its cached hash. Use
`?force=true` for a deliberate refresh or `?cache=false` for that entry.

For generic direct URLs, `metadata.directUrlHeadMetadata: true` enables the same early skip only when HEAD returns a
strong ETag or Last-Modified. Weak `W/` ETags and responses without a stable validator fall back to downloading and
comparing the payload.

**Q: Wrong file selected.**
A: Add a **`?get=regex`** or use **`[N]`** to pick an asset index. Confirm the regex escapes dots (e.g., `\\.jar`).

**Q: Timeouts or slow downloads.**
A: Increase `readTimeoutMs`; decrease `maxParallel`; or set `perDownloadTimeoutSec` and tune `maxRetries`/backoff.

**Q: Need pre-releases.**
A: Use per-link flags like `?prerelease=true`, `?beta=true`, `?alpha=true`, or `?latest=true` (works on GitHub,
Modrinth, and Hangar). Hangar also respects `?channel=Alpha`/`Beta`. Set `behavior.allowPreRelease: true` if you want
this to be the default.

**Q: Behind a corporate proxy.**
A: Configure `proxy.type/host/port`. If your proxy MITM-s TLS, ensure the Java trust store has the proxy CA.

**Q: Build from source didn’t trigger.**
A: Ensure `behavior.autoCompile.enable: true` and use `?autobuild=true` or let it kick in when no jar asset exists. Add `?branch=...` if you want a non-default branch. The
server must have outbound network access.

**Q: Manual mode still checks on a schedule.**
A: Yes. Manual mode turns scheduled runs into check-only runs using `updates.bootTime` and `updates.interval`. Add `?auto=true` to entries that should still install automatically.

---

## Building from Source

Use **Maven**:

```bash
mvn -DskipTests package
```

The built `.jar` will be in `target/`.

---

## Security Notes

* Prefer **least-privileged tokens**. Limit GitHub and GitLab credentials to read access for only the repositories and
  package registries you need. They are sent in request headers, never embedded in generated URLs, and are removed
  before a payload follows a cross-origin redirect.
* Store VoxelShop tokens only for accounts entitled to the paid resources you manage. The token is submitted to the
  official VoxelShop API, whose redirects must remain same-origin; it is not added to the returned signed payload URL or
  forwarded with that download. Public resources do not use a token.
* Optional direct-URL HEAD metadata rejects HTTPS-to-HTTP downgrades, strips configured headers after a cross-origin
  redirect, and never treats weak `W/` ETags as stable cache identities.
* Treat third-party download links as untrusted: keep `zipFileCheck: true`.
* Consider pinning sources with `?get=regex` to avoid accidentally switching to platform-incompatible jars.
* Source builds execute repository-provided Gradle/Maven wrappers and therefore run third-party build code. Enable
  `behavior.autoCompile` only for repositories you trust; manually supplied `buildLib` jars are isolated and validated
  as archives but are still executable dependencies.

---

## Contributing, Issues & Support

* **Bugs / Feature requests:** Open an issue:
  [https://github.com/NewAmazingPVP/AutoUpdatePlugins/issues](https://github.com/NewAmazingPVP/AutoUpdatePlugins/issues)
* PRs welcome! Please keep code style consistent and include a brief test plan in your PR description.

---

## Acknowledgements

**This is the original AutoUpdatePlugins project.** There are several forks of this plugin in the community; many of
their good ideas and improvements have been incorporated here over time as well. Thanks especially to contributors and
projects like ApliNi/AutoUpdatePlugins and others for inspiration and features.

---

## License

AutoUpdatePlugins is licensed under the **MIT License**. See **`LICENSE`** for details.

---

### Changelog Snapshot (v12.x)

* Expanded provider coverage and smarter selection (`[N]`, `?get=regex`, `?prerelease`, `?autobuild`).
* HTTP stack upgrades (HTTP/2 on Java 11+, virtual threads on Java 21+).
* Better parallelism, retry/backoff, and connection pooling defaults.
* Comment-preserving config regeneration and richer scheduling controls.

---





