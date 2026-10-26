# DSH CLI integration notes (verified locally on Windows, 2026-10-24)

## Launch

`dsh web` == `dsh --profile web` — boots the shipped **web** profile:
`@deepseek-ai/dsh-base` + `@deepseek-ai/dsh-web-app` bundles (resolved from the CLI's own
node_modules; profile `package.json` has empty dependencies → **no pnpm/npm install at boot**).

Verified command + output (Node 24, DSH_HOME=work/dsh-home):

```
dsh web --no-open --host 127.0.0.1 --port 53511
→ "dsh web: http://127.0.0.1:53511/?token=sP0ieHxR88_OPIndN4sofXZ6QMhGQnTJIjOX5nMj0lo"
```

- The URL line is printed by `@deepseek-ai/dsh-web-app` (`console.log(\`dsh web: ${authenticatedUrl}\`)`,
  `config.printUrl` default true). Parse: line starting with `dsh web: ` → URL.
- `--port 0` lets the OS pick a free port; the printed URL then contains the actual port.
- `--no-open` prevents launching a browser (must use inside the Android guest).
- Token: query param `token=` on `/`; `GET /?token=…` → **303 redirect** + persistent signed
  cookie (`.credentials.yaml` in DSH_HOME); `GET /` without token → **401**
  "dsh web authentication required; reopen the URL printed by dsh web.".
  ⇒ Android WebView must load the full token URL (cookie persists in WebView afterwards).
- Other web flags: `--public-url`, `--trusted-host`, `--tls-cert/--tls-key`.
- Env var `DSH_WEB_URL` is exported into the bash runtime (advertised URL).

## Home / state

- `DSH_HOME` (default `~/.dsh`): created on first boot with `profiles/web/{cordis.yml,
  cordis.patch.yml,package.json,pnpm-workspace.yaml}`, `storages/workspace.json`,
  `.credentials.yaml`, `.anonymous-user-id`. All writable → set `DSH_HOME=/root/.dsh` in guest
  (inside app-private rootfs ⇒ state persists across app restarts).
- Workspace: `storages/workspace.json` starts with `workspaces: {}`; the web UI opens/picks a
  workspace folder. Keep guest cwd at `/root/workspace` and bind app workspace there.

## Native deps (why glibc Debian/Ubuntu + official Node works, bionic/Termux does not)

- `koffi@3.1.1` uses **optionalDependencies** `@koromix/koffi-linux-arm64` (prebuilt) for Linux
  arm64. On Android/bionic there is no matching optional dep → npm falls back to compiling
  cnoke build → fails (`statx` errors) — this is exactly the earlier Termux failure.
- `node-pty@1.2.0-beta.15` ships `prebuilds/linux-arm64/pty.node` (Node-API, ABI-stable).
- Dependents of koffi: dsh-fs-local, dsh-session-persistence-jsonl, dsh-subprocess-local,
  dsh-host-directory-picker-native, libreoffice-kit, (win-only dsh-win32-process,
  dsh-sandbox-windows-acl) → koffi is **mandatory**, must resolve to prebuilt.
- Node: koffi is napi 8, `node >= 16`; node-pty prebuild is Node-API ⇒ Node 22 LTS works.
  Use Node 22 (npm 10) — npm 12 blocks install scripts by default (`install-scripts` gating).

## Minimal Linux launch recipe for the app

```
export HOME=/root DSH_HOME=/root/.dsh TERM=xterm-256color
export PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
cd /root/workspace
dsh web --no-open --host 127.0.0.1 --port 0
```
Guest wrapper greps the `dsh web: ` line and prints `DSH_READY <url>` for the Android service.
