/* ============================================================================
   DSH Pocket — phone shell for the DSH web UI.
   Runs after page load (injected by WebActivity) and:
     • raises the reading font size a notch
     • turns the desktop sidebar/rightbar into overlay drawers with a scrim
     • injects a native-feeling bottom navigation bar
     • provides back-navigation that closes a drawer before leaving the app
   Idempotent; a MutationObserver keeps the shell in sync with React re-renders.
   ========================================================================== */
(function () {
  if (window.__dshpInstalled) { window.__dshpRefresh && window.__dshpRefresh(); return; }
  window.__dshpInstalled = true;

  var html = document.documentElement;
  html.classList.add('dshp-mobile');

  /* ------------------------------------------------------------- helpers --- */
  function byLabel(label) {
    var list = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < list.length; i++) {
      if ((list[i].getAttribute('aria-label') || '') === label) return list[i];
    }
    return null;
  }
  function byLabelRe(re) {
    var list = document.querySelectorAll('button,[role="button"]');
    for (var i = 0; i < list.length; i++) {
      if (re.test(list[i].getAttribute('aria-label') || '')) return list[i];
    }
    return null;
  }
  function click(el) { if (el) { try { el.click(); } catch (e) {} } }
  function closeSidebar() { click(byLabel('Collapse sidebar') || byLabelRe(/close sidebar/i)); }
  function closeRightbar() { click(byLabel('Collapse right sidebar') || byLabelRe(/close right sidebar/i)); }
  function openSidebar() { click(byLabel('Open sidebar') || byLabelRe(/open sidebar/i)); }

  /* ------------------------------------------------------------ font size --- */
  function tuneType() {
    var b = document.body;
    if (!b) return;
    b.style.setProperty('--dsh-content-font-size', '15.5px');
    b.style.setProperty('--dsh-code-font-size', '13px');
    b.style.setProperty('--dsh-terminal-font-size', '13px');
  }

  /* --------------------------------------------------------------- scrim --- */
  var scrim = document.getElementById('dshp-scrim');
  if (!scrim) {
    scrim = document.createElement('div');
    scrim.id = 'dshp-scrim';
    document.body.appendChild(scrim);
    scrim.addEventListener('click', function () {
      if (html.classList.contains('dshp-drawer-open')) closeSidebar();
      if (html.classList.contains('dshp-rightbar-open')) closeRightbar();
    });
  }

  /* ------------------------------------------------------------ bottom nav -- */
  var ICONS = {
    chats: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><line x1="4" y1="6" x2="20" y2="6"/><line x1="4" y1="12" x2="14" y2="12"/><line x1="4" y1="18" x2="10" y2="18"/></svg>',
    new: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>',
    search: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><line x1="16.5" y1="16.5" x2="21" y2="21"/></svg>',
    more: '<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.9"/><circle cx="12" cy="12" r="1.9"/><circle cx="19" cy="12" r="1.9"/></svg>'
  };
  function buildNav() {
    var old = document.getElementById('dshp-nav');
    if (old) old.parentNode.removeChild(old);
    var nav = document.createElement('div');
    nav.id = 'dshp-nav';
    var items = [
      { key: 'chats', label: 'Chats', icon: ICONS.chats, action: function () { if (html.classList.contains('dshp-drawer-open')) closeSidebar(); else openSidebar(); } },
      { key: 'new', label: 'New', icon: ICONS.new, action: function () { var b = byLabel('New session'); if (!b) b = byLabelRe(/^New session/); click(b); if (html.classList.contains('dshp-drawer-open')) closeSidebar(); } },
      { key: 'more', label: 'More', icon: ICONS.more, action: function () { if (window.DshPocketNative && window.DshPocketNative.openMenu) { window.DshPocketNative.openMenu(); } else { click(byLabel('Account menu')); } } }
    ];
    items.forEach(function (it) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'dshp-nb';
      b.setAttribute('data-dshp', it.key);
      b.setAttribute('aria-label', it.label);
      b.innerHTML = it.icon + '<span>' + it.label + '</span>';
      b.addEventListener('click', function (ev) { ev.preventDefault(); it.action(); });
      nav.appendChild(b);
    });
    document.body.appendChild(nav);
  }

  /* ------------------------------------------------------- state tracking --- */
  function readFrame() {
    var f = document.querySelector('[data-slot="root"] > div');
    if (!f) return { left: 0, right: 0 };
    var cols = (f.style.gridTemplateColumns || '').split(/\s+/);
    function num(s) { var m = /([\d.]+)px/.exec(s || ''); return m ? parseFloat(m[1]) : 0; }
    return { left: num(cols[0]), right: num(cols[2]), el: f };
  }
  function syncState() {
    var s = readFrame();
    var drawer = s.left >= 120;
    // the right panel does not resize the grid: DSH marks it on the panel element
    var rightbar = s.right >= 120 ||
      !!document.querySelector('[data-slot="rightbar"] [data-sidebar-right-open="true"]');
    html.classList.toggle('dshp-drawer-open', drawer);
    html.classList.toggle('dshp-rightbar-open', rightbar);
    var chats = document.querySelector('#dshp-nav [data-dshp="chats"]');
    if (chats) chats.classList.toggle('dshp-on', drawer);
    // hide desktop-only dialog chrome that collides with the phone close button
    var cfg = document.querySelectorAll('[role="dialog"] button, [role="alertdialog"] button');
    for (var ci = 0; ci < cfg.length; ci++) {
      var ct = (cfg[ci].textContent || '').toLowerCase();
      if (ct.indexOf('configuration file') >= 0) { cfg[ci].setAttribute('data-dshp-hidden', '1'); }
    }
    // a modal dialog owns the screen: hide the app nav bar underneath it
    var dlg = document.querySelector('[role="dialog"],[role="alertdialog"]');
    html.classList.toggle('dshp-dialog-open', !!dlg);
    // empty-session hero: CSS bottom-aligns the composer for thumb reach
    html.classList.toggle('dshp-hero', !!document.querySelector('[class*="composerHero"]'));
  }

  /* keyboard visibility: hide the nav bar and give the composer the space */
  var baseViewportH = window.innerHeight || 0;
  function updateKeyboard() {
    var active = document.activeElement;
    var editing = !!(active && (active.isContentEditable ||
      active.tagName === 'TEXTAREA' || active.tagName === 'INPUT'));
    var h = window.innerHeight || 0;
    if (!editing && h > baseViewportH) { baseViewportH = h; }
    var shrink = editing && (baseViewportH - h) > 120;
    html.classList.toggle('dshp-keyboard', shrink);
  }

  window.__dshpBack = function () {
    if (html.classList.contains('dshp-drawer-open')) { closeSidebar(); return true; }
    if (html.classList.contains('dshp-rightbar-open')) { closeRightbar(); return true; }
    // close an open modal through its own close control first
    var dlg = document.querySelector('[role="dialog"],[role="alertdialog"]');
    if (dlg) {
      var closeBtn = dlg.querySelector('button[aria-label*="Close"], [class*="close"] button, button[class*="close"]');
      if (closeBtn) { try { closeBtn.click(); } catch (e) {} return true; }
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
      return true;
    }
    if (document.querySelector('[role="menu"]')) {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }));
      return true;
    }
    return false;
  };

  window.__dshpRefresh = function () {
    tuneType();
    var oldNav = document.getElementById('dshp-nav');
    if (oldNav && oldNav.parentNode) { oldNav.parentNode.removeChild(oldNav); }
    navReady = false;
    ensureNav();
    shortenPlaceholder();
    updateKeyboard();
    syncState();
  };

  /* tap inside a drawer → close it (phone behaviour) */
  function wireDrawerAutoClose() {
    document.addEventListener('click', function (ev) {
      if (!html.classList.contains('dshp-drawer-open') && !html.classList.contains('dshp-rightbar-open')) return;
      var t = ev.target;
      if (!t || !t.closest) return;
      var side = t.closest('[data-slot="sidebar"]');
      var right = t.closest('[data-slot="rightbar"]');
      if (!side && !right) return;
      if (t.closest('button[aria-label*="sidebar"]')) return;
      setTimeout(function () {
        if (side) closeSidebar();
        if (right) closeRightbar();
      }, 220);
    }, true);
  }

  /* keyboard: keep the composer visible and let the nav bar get out of the way */
  function wireKeyboard() {
    var vv = window.visualViewport;
    if (vv) {
      vv.addEventListener('resize', updateKeyboard);
      vv.addEventListener('scroll', updateKeyboard);
    }
    window.addEventListener('resize', updateKeyboard);
    document.addEventListener('focusin', function (ev) {
      updateKeyboard();
      if (ev.target && (ev.target.isContentEditable || ev.target.tagName === 'TEXTAREA')) {
        setTimeout(function () {
          var seat = document.querySelector('[class*="composerSeat"]');
          if (seat) seat.scrollIntoView({ block: 'end', behavior: 'smooth' });
          updateKeyboard();
        }, 320);
      }
    }, true);
    document.addEventListener('focusout', function () {
      setTimeout(updateKeyboard, 260);
    }, true);
    setInterval(updateKeyboard, 400);
  }

  /* the desktop placeholder is too long once the composer is 380px wide */
  function shortenPlaceholder() {
    var short = 'Message, / command, or task';
    var tas = document.querySelectorAll('textarea');
    for (var i = 0; i < tas.length; i++) {
      var ta = tas[i];
      if (ta.placeholder && ta.placeholder.length > 24 && !ta.dataset.dshpShort) {
        ta.dataset.dshpShort = '1';
        ta.placeholder = short;
      }
    }
    // the composer is a Lexical contenteditable using data-placeholder + CSS ::before
    var ces = document.querySelectorAll('[contenteditable][data-placeholder]');
    for (var j = 0; j < ces.length; j++) {
      var ce = ces[j];
      var ph = ce.getAttribute('data-placeholder') || '';
      if (ph.length > 24 && ph !== short) { ce.setAttribute('data-placeholder', short); }
    }
    // DSH renders the visible hint in a dedicated aria-hidden node; keep it short.
    // IMPORTANT: compare against the exact short string — comparing by length can
    // re-trigger this MutationObserver forever and freeze the page's main thread.
    var phEls = document.querySelectorAll('[data-composer-placeholder]');
    for (var k = 0; k < phEls.length; k++) {
      var phEl = phEls[k];
      if ((phEl.textContent || '') !== short) { phEl.textContent = short; }
      if (!phEl.__dshpObs) {
        (function bind(el) {
          el.__dshpObs = new MutationObserver(function () {
            if ((el.textContent || '') !== short) { el.textContent = short; }
          });
          el.__dshpObs.observe(el, { childList: true, characterData: true, subtree: true });
        })(phEl);
      }
    }
  }
  var navReady = false;
  function ensureNav() {
    if (!navReady) { buildNav(); navReady = true; }
    else if (!document.getElementById('dshp-nav')) { buildNav(); }
  }

  /* keep everything in sync while React re-renders */
  function observe() {
    var mo = new MutationObserver(function () { syncState(); });
    var f = document.querySelector('[data-slot="root"] > div');
    if (f) mo.observe(f, { attributes: true, attributeFilter: ['style', 'class', 'data-sidebar-collapsed'] });
    mo.observe(document.body, { childList: true, subtree: true });
    // Lexical rewrites data-placeholder on every render: put the short copy back
    var ce = document.querySelector('[contenteditable][data-placeholder]');
    if (ce) {
      var ceMo = new MutationObserver(function () { shortenPlaceholder(); });
      ceMo.observe(ce, { attributes: true, attributeFilter: ['data-placeholder'] });
    }
    setInterval(syncState, 700);
  }

  function boot() {
    tuneType();
    ensureNav();
    syncState();
    wireDrawerAutoClose();
    wireKeyboard();
    observe();
    shortenPlaceholder();
    setInterval(shortenPlaceholder, 1500);
    document.body.setAttribute('data-dshp-mobile', '1');
    // land on the conversation, not on a drawer DSH restored from last session
    // (DSH restores the open state asynchronously, so retry a few times)
    [300, 900, 1800].forEach(function (ms) {
      setTimeout(function () {
        if (readFrame().left >= 120) { closeSidebar(); }
        if (readFrame().right >= 120 ||
            document.querySelector('[data-slot="rightbar"] [data-sidebar-right-open="true"]')) { closeRightbar(); }
      }, ms);
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();




