# Third-party Claude Code skills registry

Skills reviewed and installed on 2026-10-23: **Arena**, **brag**, **HyperFrames**.

## Scope warning (read first)

Everything below was installed **in the remote Claude Code cloud sandbox used for this session only**:

- User-level (`/root/.claude/...`), **this remote sandbox only**.
- **Not** the user's local Claude Code, and **not** the GitHub Codespace.
- The container is ephemeral. When it is reclaimed, all of these installs vanish. The "Install on your own machine" sections give the commands to run locally.
- No application code was modified. Nothing was committed or pushed. No SMMS video was produced and no 16-agent Arena run was performed.

Status legend: PASS = observed working; FAIL = observed failing; BLOCKED = could not run because of an external restriction; NOT TESTED = deliberately not run.

## Environment (sandbox, observed)

| Item | Value |
|---|---|
| Claude Code CLI | 2.1.309 (`/opt/node22/bin/claude`) |
| Node.js | v22.22.0 |
| npm | 10.9.4 |
| Python | 3.11.17 |
| uv | 0.11.32 |
| FFmpeg / FFprobe | 6.1.1-3ubuntu5 (`/usr/bin/ffmpeg`) |
| Docker | 29.9.1 (daemon running) |
| Chromium (full) | 141.0.7390.37, `/opt/pw-browsers/chromium-1194/chrome-linux/chrome` |
| Chromium headless shell | 141.0.7390.37, `/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell` |
| Resources | 4 cores, ~15 GB RAM |
| Network | HTTPS only via agent proxy; `cdn.jsdelivr.net` returns 403 (policy), npm registry works |

## Summary

| Skill | Source @ commit | Installed at (sandbox) | Invoke | Status |
|---|---|---|---|---|
| Arena | https://github.com/0alfares082/arena-skill-16 @ `391013e890a82260dcca407d0c80d849dca93986` | `/root/.claude/skills/arena/` | `/arena` | PASS (discoverable, tests pass, no run) |
| brag 0.4.0 | https://github.com/latent-spaces/brag @ `069432cac935ed1895859df201c31436c07fb74e` | `/root/.claude/plugins/cache/brag/brag/0.4.0` | `/brag:brag`, `/brag:brag-slim` (plugin-namespaced) | PASS (installed and enabled); end-to-end video NOT TESTED |
| HyperFrames plugin 0.8.187 | https://github.com/heygen-com/hyperframes @ `b6de25391a5e5fbf385c5b17b192b61bc2d39573` | `/root/.claude/plugins/cache/hyperframes/hyperframes/0.8.187` | `/hyperframes:hyperframes` | Plugin PASS; bundled plugin launcher FAIL (see below); CLI 0.8.186 render PASS |

---

## 1. Arena (optional)

Use only for complex planning or option comparison. It is token-expensive (about 91 sub-agent calls per default run). **No competition was launched.**

- **Method:** manual copy of the skill folder (the README's "copy the folder yourself" route), chosen so the command is plain `/arena`. The plugin route would namespace it as `/arena-skill-16:arena`.
- **Commands run:**
  ```bash
  git clone https://github.com/0alfares082/arena-skill-16.git
  mkdir -p ~/.claude/skills
  cp -r arena-skill-16/skills/arena ~/.claude/skills/
  ```
- **Scope/path:** user-level, this remote sandbox only: `/root/.claude/skills/arena/` (`SKILL.md`, `bracket.py`, `rubric.md`, `strategies.json`).
- **Shadowing check:** no other `arena` skill found in `~/.claude/skills`, `~/.claude/plugins` (did not exist before), or the repo `.claude/`.
- **Dependencies:** Python 3.8+ (have 3.11.17), standard library only; nothing to pip install. Needs Claude Code's Agent tool.
- **Default competitor count: 16.** Evidence:
  - `/root/.claude/skills/arena/bracket.py:43`: `DEFAULT_AGENTS = 16`
  - `SKILL.md`: "Use N = 16 unless the user explicitly specifies another count." and frontmatter "default 16; --quick also uses 16"
  - `python3 bracket.py plan` printed "16 agents, 4 rounds, waves of 10 ... total 91 sub-agent calls, 16 waves".
- **Verification:**
  - Frontmatter `name: arena` present; the skill appeared in the session's skill list as `arena` (PASS).
  - `bracket.py plan` (writes nothing) PASS.
  - Upstream unit tests, run from the cloned repo: `python3 -m unittest discover -s tests` -> "Ran 29 tests ... OK" (PASS).
  - Real tournament: NOT TESTED (deliberately; cost).
- **Flags:** `--agents N`, `--quick` (alias of 16), `--seed S`, `--wave W`.
- **Troubleshooting:** not listed after copy -> restart Claude Code / start a new session. Permission prompts per file in `.arena/` -> use accept-edits mode. State lives in `.arena/` of the current dir (keep it out of git).
- **Security review:** read README, SKILL.md, `bracket.py`, rubric, strategies, plugin manifests. `bracket.py` only reads and writes local files (no network, subprocess, or env access found by grep). The SKILL.md instructs spawning sub-agents restricted to writing inside `.arena/`. No hooks, no MCP. No concerns found. Note: it intentionally spends many tokens.
- **Install on your own machine:**
  ```bash
  git clone https://github.com/0alfares082/arena-skill-16.git
  mkdir -p ~/.claude/skills && cp -r arena-skill-16/skills/arena ~/.claude/skills/
  # restart Claude Code, then: /arena
  ```
  Alternative (namespaced `/arena-skill-16:arena`): `/plugin marketplace add 0alfares082/arena-skill-16` then `/plugin install arena-skill-16@arena-skill-16`.

---

## 2. brag

Turns a project into a 15-25 s launch video using HyperFrames. **No SMMS video was created.**

- **Method:** Claude Code plugin via the non-interactive CLI (the slash-command equivalents `/plugin marketplace add ...` / `/plugin install ...` map to these).
- **Commands run (exact):**
  ```bash
  claude plugin marketplace add latent-spaces/brag
  claude plugin install brag@brag
  claude plugin list
  ```
  Output: "Successfully added marketplace: brag", "Successfully installed plugin: brag@brag (scope: user)", list shows `brag@brag  Version: 0.4.0  Scope: user  Status: enabled`.
- **Scope/path:** user scope, this remote sandbox only. Install path `/root/.claude/plugins/cache/brag/brag/0.4.0`; marketplace clone `/root/.claude/plugins/marketplaces/brag` (HEAD `069432c...`, matches `gitCommitSha` in `installed_plugins.json`).
- **Components** (`claude plugin details brag`): 2 skills (`brag`, `brag-slim`), 0 agents, **0 hooks**, 0 MCP servers; about 258 tokens always-on.
- **Invocation:** plugin skills are namespaced: `/brag:brag`, `/brag:brag-slim`; the natural-language trigger "let's /brag" also works per its description. Options: `--tone`, `--format landscape|vertical|square`, `--duration`, `--no-music`, `--no-sfx`, `--title`, `--voice`. Output goes to `brag-output/` in the project. The README also notes that on Opus 5.5 `/brag` hands off to `brag-slim` (no HyperFrames); `--full` keeps the full workflow.
- **Dependencies (documented):** Node 22+ (v22.22.0 OK), FFmpeg on PATH (6.1.1 OK), HyperFrames CLI via `npx hyperframes` (see section 3; `doctor` run). Optional: `uv` + librosa for custom-track beat analysis (uv 0.11.32 present; librosa not installed, not needed unless a custom track is used). `--voice` needs Kokoro (not installed; doctor reports it missing; optional).
- **Verification:**
  - Marketplace add, install, list, details: PASS.
  - Skill frontmatter names `brag` / `brag-slim` read and valid: PASS.
  - Documented gate `npx hyperframes check` and render: exercised on a throwaway composition (section 3): PASS. This does not validate brag's planning workflow.
  - Full `/brag` run (inspect -> plan -> compose -> render): **NOT TESTED** (would produce a video of the app; explicitly out of scope).
  - Music-cue script `analyze_music_cues.py`: NOT TESTED (needs librosa).
- **Troubleshooting:** after installing, start a new session or reload plugins so skills appear. brag relies on the HyperFrames skills (`hyperframes-core` etc. in its text); install the HyperFrames plugin too. Bundled music is by ende.app and SFX by Kenney (credits in upstream README); check licensing before publishing.
- **Security review:** read README, both manifests, `SKILL.md`, `slim.md`/`brag-slim/SKILL.md` (identical), all 5 `references/*.md`, `analyze_music_cues.py` header. No hooks, no MCP, no `curl|sh`, no credential or env reads, no exfiltration or injection-style text found. It explicitly tells the agent not to read `.env`/key files and not to put secrets into the video. Caveat: it instructs the agent to read the project's source, so run it only on code you are comfortable turning into a public video.
- **Install on your own machine** (inside Claude Code):
  ```
  /plugin marketplace add latent-spaces/brag
  /plugin install brag@brag
  ```
  or in a shell: `claude plugin marketplace add latent-spaces/brag && claude plugin install brag@brag`. Then in a project: `/brag:brag` (or "let's /brag").

---

## 3. HyperFrames

HTML-to-video renderer plus agent skills. Docs: https://hyperframes.heygen.com

- **Method:** (a) Claude Code plugin, (b) `npx hyperframes` CLI.
- **Commands run (exact):**
  ```bash
  claude plugin marketplace add heygen-com/hyperframes
  claude plugin install hyperframes@hyperframes
  claude plugin list
  npx --yes hyperframes@0.8.186 doctor
  ```
- **Scope/path:** user scope, this remote sandbox only. Plugin: `/root/.claude/plugins/cache/hyperframes/hyperframes/0.8.187`; marketplace clone HEAD `b6de25391a5e5fbf385c5b17b192b61bc2d39573`. CLI was run through npx cache (not globally installed). The CLI also created `/root/.hyperframes/` (config).
- **Components** (`claude plugin details`): 7 skills (hyperframes, hyperframes-cli, hyperframes-registry, media-use, figma, slideshow, remotion-to-hyperframes), 0 hooks, 0 MCP servers; about 783 tokens always-on. Invoke: `/hyperframes:hyperframes`.
- **Requirements** (README): Node.js 22+ (v22.22.0 OK), FFmpeg (6.1.1 OK). Chrome headless shell needed for local render.
- **Doctor result (CLI 0.8.186):** PASS for Node, FFmpeg, FFprobe, Docker (running), disk, memory, `/dev/shm`. Initially "Chrome: FAIL (not found)", fixed without installing anything by pointing at the preinstalled shell:
  ```bash
  export PRODUCER_HEADLESS_SHELL_PATH=/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell
  ```
  After that, doctor shows Chrome OK (source: env). Optional components not installed: whisper-cpp (transcription), Kokoro TTS, MusicGen BGM. Did not run `playwright install` or `browser ensure`.
- **Render test: PASS.** Temporary project created outside the app repo (`scratchpad/hyperframes-test/clip`, via `npx hyperframes init clip --non-interactive`), edited to a 3 s title animation, then:
  ```bash
  npx hyperframes@0.8.186 check            # "Check passed" (1 warning: first_frame_empty, cosmetic)
  npx hyperframes@0.8.186 render --quality draft --output out.mp4
  ```
  ffprobe of `out.mp4`: h264, 1920x1080, 30 fps, duration 3.000000 s, size 52,808 bytes (51.6 KB); render took 5.2 s. The temporary files were then deleted.
- **Known issues found (important):**
  1. **BLOCKED / FAIL: plugin launcher version mismatch.** The plugin (0.8.187) tells agents to run the CLI through `skills/hyperframes/scripts/plugin-cli.mjs`, which pins the manifest version. npm only had up to 0.8.186 (`latest`), so `node .../plugin-cli.mjs --version` failed with `ETARGET No matching version found for hyperframes@0.8.187`. Probably transient (plugin released ahead of npm); retry later. Workaround used: call `npx hyperframes@0.8.186` directly.
  2. **BLOCKED: template GSAP CDN.** `init` templates load GSAP from `https://cdn.jsdelivr.net/...`. In this sandbox the proxy returns 403 for that host, so the first render failed (`sub_timeline_script_failure`). Workaround: fetched the same `gsap@3.14.2` from the npm registry (allowed) and referenced it as a local `assets/gsap.min.js`. Real compositions for SMMS should vendor GSAP locally in the same way; on a normal machine the CDN works.
  3. Marketplace clone printed an LFS warning (84 pointer files). These belong to repo-internal skills (`.agents/`, `.claude/`), not the 7 published skills; no effect observed.
- **Telemetry:** the CLI and `media-use` scripts send coarse anonymous usage events to PostHog unless opted out. I ran everything with `HYPERFRAMES_NO_TELEMETRY=1` and `DO_NOT_TRACK=1` (also `npx hyperframes telemetry disable` writes the opt-out to config). Recommend doing the same.
- **Troubleshooting:** Chrome missing -> set `PRODUCER_HEADLESS_SHELL_PATH` (above) or `npx hyperframes browser ensure` on a normal machine. `check` fails with `page_error ... 'timeline'` -> GSAP script did not load. Slow render -> use `--quality draft`; `--docker` for reproducible output. `HYPERFRAMES_SKIP_SKILLS=1` stops `init` from auto-refreshing skills.
- **Security review:** read README, manifests, `hyperframes`/`hyperframes-cli` SKILL.md, plugin-installation and skill-lifecycle references, SECURITY.md; grepped all 301 skill files for `curl|sh`, credential paths, injection phrases, TLS disabling: none found. Findings: (a) PostHog telemetry (opt-out, above); (b) optional features read `GEMINI_API_KEY` or HeyGen credentials only when the user wires those providers; (c) the plugin launcher downloads a pinned CLI from npm at first use (supply-chain trust in npm package `hyperframes`); (d) the repo's own `.claude/settings.json` and `.codex/hooks.json` are for upstream contributors and are not part of the plugin (plugin reports 0 hooks). No blocking concerns.
- **Install on your own machine:**
  ```bash
  claude plugin marketplace add heygen-com/hyperframes
  claude plugin install hyperframes@hyperframes
  # CLI check (Node 22+ and FFmpeg required):
  npx hyperframes doctor
  # try it:
  npx hyperframes init my-video && cd my-video && npx hyperframes preview
  npx hyperframes render --output out.mp4
  ```
  Standalone-skills alternative: `npx hyperframes skills update`.

---

## Readiness for SMMS video production (this sandbox)

- HyperFrames CLI: **ready** with the `PRODUCER_HEADLESS_SHELL_PATH` env var and locally vendored GSAP; 3 s test render succeeded.
- brag: installed, workflow not exercised; depends on HyperFrames working (it does via the CLI path).
- Re-install everything in a new sandbox/Codespace/local machine using the commands above; nothing here persists.
