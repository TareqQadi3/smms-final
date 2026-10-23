# Full Page Screenshot — Claude Code Skill

A zero-dependency Claude Code skill that captures **full-page screenshots** of any web page as a single PNG image, including all content below the fold.

Connects directly to Chrome DevTools Protocol (CDP) — no Puppeteer, no Playwright, no npm install needed.

## Features

- **Full-page capture** — renders the entire scrollable page into one PNG
- **SPA support** — auto-detects `overflow-y: scroll/auto` containers and expands them
- **DOM stability wait** — monitors element count changes until SPA rendering completes
- **Lazy-load handling** — scrolls through the page to trigger `IntersectionObserver` images
- **Ultra-long page tiling** — pages > 16,000px are captured in 8,000px tiles and stitched with Python PIL
- **CDP Proxy fallback** — when a CDP proxy (e.g., `web-access` skill) holds the WebSocket, automatically falls back to proxy API
- **Two capture modes** — screenshot an existing browser tab by `targetId`, or provide a URL to open → capture → close automatically

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Node.js | 22+ |
| Chrome | Remote debugging enabled |
| Python 3 + Pillow | Optional, for tiling stitch on ultra-long pages |

Enable Chrome remote debugging:
1. Open `chrome://inspect/#remote-debugging`
2. Check **"Allow remote debugging for this browser instance"**

## Install

```bash
npx skills add LewisLiu007/full-page-screenshot
```

Or manually clone into your skills directory:

```bash
git clone https://github.com/LewisLiu007/full-page-screenshot.git ~/.claude/skills/full-page-screenshot
```

## Usage

### In Claude Code

Just ask Claude:

> "Take a full-page screenshot of https://example.com"

> "Screenshot this page as a long image"

> "截个长图"

Claude will automatically invoke this skill when it detects screenshot-related intent.

### CLI (standalone)

```bash
# Check prerequisites
node scripts/full-page-screenshot.mjs --check

# List open browser tabs
node scripts/full-page-screenshot.mjs --list

# Screenshot a URL (opens tab → captures → closes)
node scripts/full-page-screenshot.mjs --url "https://example.com" output.png

# Screenshot an existing tab by targetId
node scripts/full-page-screenshot.mjs <targetId> output.png
```

### Parameters

| Flag | Description | Default |
|------|-------------|---------|
| `output` | Output file path | `/tmp/screenshot.png` |
| `--width` | Viewport width (CSS px). 1200 for articles, 1440–1920 for wide tables | `1200` |
| `--dpr` | Device pixel ratio. 2 = Retina quality (4× file size) | `1` |
| `--wait` | Page load timeout in ms (`--url` mode only) | `15000` |

## How It Works

```
Chrome DevToolsActivePort file
        │
        ▼
   Discover debug port ──► WebSocket connect
        │
        ▼
   Set viewport (Emulation.setDeviceMetricsOverride)
        │
        ▼
   Wait for readyState=complete + DOM stable
        │
        ▼
   Detect & expand scroll containers (override CSS)
        │
        ▼
   Scroll to trigger lazy-loaded images
        │
        ▼
   Page.captureScreenshot (or tile + stitch if > 16000px)
        │
        ▼
   Save PNG ✓
```

## Notes

- Ultra-long pages (> 20,000px) at high DPR may cause Chrome memory issues — lower `--dpr` to 1
- `--url` mode creates a background tab that auto-closes after capture
- Viewport and scroll container styles are temporarily modified during capture, then restored
- No external npm dependencies — uses only Node.js built-in modules (`fs`, `net`, `path`, `os`, `child_process`)

## License

MIT
