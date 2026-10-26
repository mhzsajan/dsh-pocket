// Minimal WebView DevTools (CDP) client for the DSH Pocket app.
// Usage:
//   node dsh-pocket/tools/cdp.mjs eval "<js expression>"
//   node dsh-pocket/tools/cdp.mjs outline
// Requires: adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>
const PORT = process.env.CDP_PORT || 9222;

async function targets() {
  const r = await fetch(`http://127.0.0.1:${PORT}/json`);
  return r.json();
}

function wsUrl(t) {
  // webSocketDebuggerUrl points at ws://127.0.0.1:<port>/devtools/page/... ; re-host on our forward
  const u = new URL(t.webSocketDebuggerUrl);
  return `ws://127.0.0.1:${PORT}${u.pathname}`;
}

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.onopen = () => resolve(ws);
    ws.onerror = (e) => reject(new Error('ws error: ' + (e.message || e.type)));
  });
}

function call(ws, id, method, params) {
  return new Promise((resolve, reject) => {
    const onMsg = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id === id) {
        ws.removeEventListener('message', onMsg);
        if (m.error) reject(new Error(JSON.stringify(m.error)));
        else resolve(m.result);
      }
    };
    ws.addEventListener('message', onMsg);
    ws.send(JSON.stringify({ id, method, params }));
  });
}

const OUTLINE = `(() => {
  const out = [];
  const walk = (el, depth) => {
    if (depth > 9) return;
    const r = el.getBoundingClientRect();
    const cs0 = getComputedStyle(el);
    const contents = cs0.display === 'contents';
    if (!contents && (r.width < 2 || r.height < 2) && el.children.length === 0) return;
    if (!contents && (r.width < 2 || r.height < 2)) { for (const c of el.children) walk(c, depth); return; }
    if (contents) { for (const c of el.children) walk(c, depth); return; }
    const cs = cs0;
    const role = el.getAttribute('role') || '';
    const cls = (typeof el.className === 'string' ? el.className : '').split(/\\s+/).filter(Boolean).slice(0, 3).join('.');
    const txt = (el.childElementCount === 0 ? (el.textContent || '') : '').trim().slice(0, 24);
    out.push({ d: depth, tag: el.tagName.toLowerCase() + (el.id ? '#' + el.id : '') + (cls ? '.' + cls : '') + (role ? '[' + role + ']' : ''),
      x: Math.round(r.x), y: Math.round(r.y), w: Math.round(r.width), h: Math.round(r.height),
      fs: cs.fontSize, pos: cs.position, ov: cs.overflow, txt });
    for (const c of el.children) walk(c, depth + 1);
  };
  walk(document.body, 0);
  return out;
})()`;

const mode = process.argv[2] || 'eval';
const arg = process.argv[3] || '';
const arg2 = process.argv[4] || '';

const ts = await targets();
const t = ts.find((x) => x.type === 'page') || ts[0];
if (!t) { console.error('no CDP target (is the app WebView open?)'); process.exit(2); }
const ws = await connect(wsUrl(t));
try {
  if (mode === 'shot') {
    const res = await call(ws, 1, 'Page.captureScreenshot', { format: 'png' });
    const fs = await import('node:fs');
    fs.writeFileSync(arg, Buffer.from(res.data, 'base64'));
    console.log('screenshot ->', arg);
  } else if (mode === 'click') {
    const r = await call(ws, 1, 'Runtime.evaluate', {
      expression: `(() => { const els=[...document.querySelectorAll(${JSON.stringify(arg)})]; if(!els.length) return 'NOT_FOUND'; const el=els[${Number(arg2) || 0}]; el.scrollIntoView({block:'center'}); el.click(); return 'CLICKED ' + (el.getAttribute('aria-label')||el.tagName); })()`,
      returnByValue: true, userGesture: true,
    });
    console.log(r.result.value);
  } else if (mode === 'inject') {
    const fs = await import('node:fs');
    const css = fs.readFileSync(arg, 'utf8');
    const cssRes = await call(ws, 1, 'Runtime.evaluate', {
      expression: `(function(){var s=document.getElementById('dshp-skin');if(!s){s=document.createElement('style');s.id='dshp-skin';document.head.appendChild(s);}s.textContent=${JSON.stringify(css)};return 'css ok '+s.textContent.length;})()`,
      returnByValue: true,
    });
    console.log(cssRes.result.value);
    if (arg2) {
      const js = fs.readFileSync(arg2, 'utf8');
      const jsRes = await call(ws, 2, 'Runtime.evaluate', { expression: js, returnByValue: true, awaitPromise: true });
      if (jsRes.exceptionDetails) console.error('JS EXCEPTION', JSON.stringify(jsRes.exceptionDetails).slice(0, 400));
      else console.log('js ok');
    }
  } else if (mode === 'tap') {
    const x = Number(process.argv[3]);
    const y = Number(process.argv[4]);
    await call(ws, 1, 'Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', clickCount: 1 });
    await call(ws, 2, 'Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', clickCount: 1 });
    console.log('tap', x, y);
  } else if (mode === 'key') {
    const key = process.argv[3] || 'Escape';
    const vk = key === 'Escape' ? 27 : (key === 'Enter' ? 13 : 0);
    await call(ws, 1, 'Input.dispatchKeyEvent', { type: 'rawKeyDown', key, code: key, windowsVirtualKeyCode: vk });
    await call(ws, 2, 'Input.dispatchKeyEvent', { type: 'keyUp', key, code: key, windowsVirtualKeyCode: vk });
    console.log('key', key);
  } else {
  const expr = mode === 'outline' ? OUTLINE : arg;
  const res = await call(ws, 1, 'Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: true,
    userGesture: true,
  });
  if (res.exceptionDetails) {
    console.error('EXCEPTION:', JSON.stringify(res.exceptionDetails, null, 2));
    process.exit(1);
  }
  const v = res.result && res.result.value;
  if (mode === 'outline' && Array.isArray(v)) {
    console.log('viewport', await (async () => {
      const r = await call(ws, 2, 'Runtime.evaluate', { expression: 'JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio,scrollW:document.documentElement.scrollWidth,meta:(document.querySelector("meta[name=viewport]")||{}).content||null})', returnByValue: true });
      return r.result.value;
    })());
    for (const n of v) {
      console.log(`${'  '.repeat(n.d)}${n.tag}  [${n.x},${n.y} ${n.w}x${n.h}] fs=${n.fs} pos=${n.pos} ov=${n.ov}${n.txt ? '  "' + n.txt + '"' : ''}`);
    }
  } else {
    console.log(typeof v === 'string' ? v : JSON.stringify(v, null, 2));
  }
  }
} finally {
  ws.close();
  process.exit(0);
}



