function () {
  'use strict';
  // agent-webview in-page library. Evaluated as a function expression and invoked per call:
  //   (<this file>)().snapshot(startAt, opts)
  // Nothing is kept on the page except the monitor (window.__awm) and data-agent-ref stamps.
  var REF = 'data-agent-ref';
  var API = {};

  var SKIP = {script: 1, style: 1, noscript: 1, template: 1, head: 1, meta: 1, link: 1, title: 1, svg: 1, canvas: 1, object: 1, embed: 1};
  var INTERACTIVE = {button: 1, link: 1, textbox: 1, searchbox: 1, checkbox: 1, radio: 1, 'switch': 1, tab: 1,
    menuitem: 1, menuitemcheckbox: 1, menuitemradio: 1, option: 1, combobox: 1, listbox: 1, slider: 1, spinbutton: 1, treeitem: 1};
  var LANDMARK = {nav: 'navigation', main: 'main', aside: 'complementary', footer: 'contentinfo', form: 'form', dialog: 'dialog'};

  // ---------------------------------------------------------------- deep DOM helpers

  function collectRoots(root, out) {
    out.push(root);
    var all;
    try { all = root.querySelectorAll('*'); } catch (e) { return out; }
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.shadowRoot) collectRoots(el.shadowRoot, out);
      var ln = el.localName;
      if (ln === 'iframe' || ln === 'frame') {
        try { if (el.contentDocument) collectRoots(el.contentDocument, out); } catch (e) { /* cross-origin */ }
      }
    }
    return out;
  }

  function deepAll(selector) {
    var res = [];
    var roots = collectRoots(document, []);
    for (var i = 0; i < roots.length; i++) {
      var found;
      try { found = roots[i].querySelectorAll(selector); } catch (e) { continue; }
      for (var j = 0; j < found.length; j++) res.push(found[j]);
    }
    return res;
  }

  function find(ref) {
    if (typeof ref !== 'string' || !/^@e\d+$/.test(ref)) return {err: 'stale'};
    var found = deepAll('[' + REF + '="' + ref + '"]');
    if (found.length === 0) return {err: 'stale'};
    if (found.length > 1) return {err: 'ambiguous'};
    return {el: found[0]};
  }

  function fail(code, message) { return {ok: false, code: code, message: message}; }
  function lookupFail(f) {
    return f.err === 'ambiguous'
      ? fail('AMBIGUOUS_REF', 'more than one element carries this ref; observe again')
      : fail('STALE_REF', 'element is gone; observe again');
  }

  // ---------------------------------------------------------------- element facts

  function clip(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function isHidden(el, requireLayout) {
    if (el.hidden || el.getAttribute('aria-hidden') === 'true') return true;
    var win = el.ownerDocument.defaultView || window;
    var cs;
    try { cs = win.getComputedStyle(el); } catch (e) { return false; }
    if (cs.display === 'none' || cs.visibility === 'hidden' || cs.visibility === 'collapse') return true;
    if (requireLayout && cs.display !== 'contents' && el.getClientRects().length === 0) return true;
    return false;
  }

  function roleOf(el) {
    var explicit = (el.getAttribute('role') || '').trim().split(/\s+/)[0];
    var tag = el.localName;
    if (explicit) return explicit;
    switch (tag) {
      case 'a': return el.hasAttribute('href') ? 'link' : '';
      case 'button': case 'summary': return 'button';
      case 'select': return el.multiple ? 'listbox' : 'combobox';
      case 'textarea': return 'textbox';
      case 'input': {
        var t = (el.getAttribute('type') || 'text').toLowerCase();
        if (t === 'hidden') return '';
        if (t === 'checkbox' || t === 'radio') return t;
        if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
        if (t === 'range') return 'slider';
        if (t === 'number') return 'spinbutton';
        if (t === 'search') return 'searchbox';
        return 'textbox';
      }
    }
    if (/^h[1-6]$/.test(tag)) return 'heading';
    var ce = el.getAttribute('contenteditable');
    if (ce !== null && ce !== 'false') return 'textbox';
    if (el.hasAttribute('onclick') || typeof el.onclick === 'function') return 'button';
    return '';
  }

  function labelText(el) {
    var doc = el.ownerDocument;
    var lb = el.getAttribute('aria-labelledby');
    if (lb) {
      var parts = [];
      lb.split(/\s+/).forEach(function (id) { var n = doc.getElementById(id); if (n) parts.push(n.textContent); });
      if (parts.join(' ').trim()) return parts.join(' ');
    }
    var al = el.getAttribute('aria-label');
    if (al && al.trim()) return al;
    if (el.labels && el.labels.length) {
      var t = [];
      for (var i = 0; i < el.labels.length; i++) t.push(el.labels[i].textContent);
      if (t.join(' ').trim()) return t.join(' ');
    }
    return '';
  }

  function nameOf(el, role, max) {
    var tag = el.localName;
    var n = labelText(el);
    if (!n && tag === 'input') {
      var t = (el.getAttribute('type') || 'text').toLowerCase();
      if (t === 'submit' || t === 'button' || t === 'reset') n = el.value || '';
      else if (t === 'image') n = el.getAttribute('alt') || '';
    }
    if (!n && (tag === 'input' || tag === 'textarea')) n = el.getAttribute('placeholder') || el.getAttribute('title') || el.name || '';
    if (!n && tag === 'select') n = el.getAttribute('title') || el.name || '';
    if (!n && role !== 'textbox' && role !== 'searchbox') n = el.innerText || el.textContent || '';
    if (!n && role === 'textbox' && tag !== 'input' && tag !== 'textarea') n = el.getAttribute('title') || '';
    if (!n) n = el.getAttribute('title') || '';
    if (!n && tag === 'a') { var img = el.querySelector('img[alt]'); if (img) n = img.getAttribute('alt'); }
    return clip(n, max);
  }

  function sensitiveKind(el) {
    if (el.localName !== 'input' && el.localName !== 'textarea') return '';
    var t = (el.getAttribute('type') || 'text').toLowerCase();
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (t === 'password') return 'password';
    if (ac.indexOf('cc-') === 0) return 'cc';
    if (ac === 'one-time-code') return 'otp';
    var ident = ((el.name || '') + ' ' + (el.id || '')).toLowerCase();
    var ml = parseInt(el.getAttribute('maxlength') || '0', 10);
    if (/otp|2fa|passcode|verification[-_ ]?code|totp/.test(ident) && ml > 0 && ml <= 8) return 'otp';
    if (/card[-_ ]?(number|num)|cvv|cvc/.test(ident)) return 'cc';
    return '';
  }

  // ---------------------------------------------------------------- snapshot

  API.snapshot = function (startAt, opts) {
    opts = opts || {};
    var maxNodes = opts.maxNodes || 600;
    var maxText = opts.maxTextChars || 8000;
    var maxName = opts.maxNameChars || 120;
    var requireLayout = opts.requireLayout !== false;

    deepAll('[' + REF + ']').forEach(function (e) { e.removeAttribute(REF); });

    var n = startAt || 1;
    var count = 0, textChars = 0, truncated = false;
    var seen = new Set();
    var frames = [];
    var vh = window.innerHeight || 0;

    function budgetLeft() {
      if (count >= maxNodes || textChars >= maxText) { truncated = true; return false; }
      return true;
    }

    function pushText(kids, s) {
      s = clip(s, maxName * 3);
      if (!s || !budgetLeft()) return;
      textChars += s.length; count++;
      kids.push({role: 'text', name: s, children: []});
    }

    function walkChildren(el, kids) {
      var buf = '';
      var nodes = el.childNodes;
      for (var i = 0; i < nodes.length; i++) {
        var c = nodes[i];
        if (c.nodeType === 3) { buf += c.data; continue; }
        if (buf.trim()) { pushText(kids, buf); } buf = '';
        if (c.nodeType === 1) walk(c, kids);
        if (truncated) return;
      }
      if (buf.trim()) pushText(kids, buf);
    }

    function walk(el, kids) {
      if (!budgetLeft()) return;
      var tag = el.localName;
      if (SKIP[tag] || seen.has(el)) return;
      seen.add(el);
      if (tag === 'input' && (el.getAttribute('type') || '').toLowerCase() === 'hidden') return;
      if (isHidden(el, requireLayout)) return;

      if (tag === 'iframe' || tag === 'frame') {
        var doc = null;
        try { doc = el.contentDocument; } catch (e) { doc = null; }
        var src = el.getAttribute('src') || '';
        if (!doc || !doc.body) {
          frames.push({src: src, title: el.getAttribute('title') || '', crossOrigin: true});
          return;
        }
        frames.push({src: src, title: el.getAttribute('title') || '', crossOrigin: false});
        var fk = [];
        walkChildren(doc.body, fk);
        count++;
        kids.push({role: 'iframe', name: clip(el.getAttribute('title') || src, maxName), children: fk});
        return;
      }

      var role = roleOf(el);
      var interactive = !!INTERACTIVE[role];

      if (role === 'heading') {
        var lvl = /^h[1-6]$/.test(tag) ? parseInt(tag.charAt(1), 10) : parseInt(el.getAttribute('aria-level') || '2', 10);
        var hname = clip(el.innerText || el.textContent, maxName);
        if (hname) { count++; kids.push({role: 'heading', name: hname, level: lvl, children: []}); }
        return;
      }

      if (interactive) {
        var ref = '@e' + (n++);
        el.setAttribute(REF, ref);
        count++;
        var states = [];
        var node = {role: role, name: nameOf(el, role, maxName), ref: ref, children: []};
        var sk = sensitiveKind(el);
        if (sk) states.push(sk, 'sensitive');
        if (el.disabled || el.getAttribute('aria-disabled') === 'true') states.push('disabled');
        if (el.required || el.getAttribute('aria-required') === 'true') states.push('required');
        if (el.readOnly) states.push('readonly');
        if (el.checked === true || el.getAttribute('aria-checked') === 'true') states.push('checked');
        if (el.getAttribute('aria-expanded') === 'true') states.push('expanded');
        if (el.getAttribute('aria-selected') === 'true' || el.selected === true) states.push('selected');
        if (el.ownerDocument.activeElement === el) states.push('focused');
        var r = el.getBoundingClientRect();
        if (requireLayout && (r.bottom < 0 || r.top > vh)) states.push('offscreen');
        if (tag === 'a' && el.href) node.href = clip(el.href, 200);
        if (tag === 'select') {
          var opts2 = [];
          for (var oi = 0; oi < el.options.length && oi < 30; oi++) opts2.push(clip(el.options[oi].text, 60));
          node.options = opts2;
          var so = el.options[el.selectedIndex];
          node.value = so ? clip(so.text, 80) : '';
        } else if ((role === 'textbox' || role === 'searchbox' || role === 'spinbutton' || role === 'slider') && !sk) {
          if (typeof el.value === 'string') node.value = clip(el.value, 200);
          else if (el.isContentEditable) node.value = clip(el.textContent, 200);
        }
        if (states.length) node.states = states;
        kids.push(node);
        // Interactive elements may wrap further interactive elements (rare); keep them as children.
        if (tag !== 'input' && tag !== 'select' && tag !== 'textarea' && tag !== 'button') {
          var inner = [];
          walkInteractiveOnly(el, inner);
          if (inner.length) node.children = inner;
        }
        return;
      }

      var landmark = LANDMARK[tag] || (role === 'dialog' || role === 'alertdialog' ? 'dialog' : '');
      var target = kids;
      var container = null;
      if (landmark) {
        container = {role: landmark, name: clip(el.getAttribute('aria-label') || '', maxName), children: []};
        target = container.children;
      }
      walkChildren(el, target);
      if (el.shadowRoot) walkChildren(el.shadowRoot, target);
      if (container && container.children.length) { count++; kids.push(container); }
    }

    function walkInteractiveOnly(el, kids) {
      var all = el.querySelectorAll('*');
      for (var i = 0; i < all.length && budgetLeft(); i++) {
        var c = all[i];
        if (seen.has(c) || SKIP[c.localName]) continue;
        if (INTERACTIVE[roleOf(c)]) walk(c, kids);
      }
    }

    var out = [];
    walkChildren(document.body || document.documentElement, out);

    return {
      url: location.href,
      title: document.title || '',
      nodes: out,
      truncated: truncated,
      scrollY: Math.round(window.scrollY || 0),
      scrollHeight: Math.round((document.documentElement || {}).scrollHeight || 0),
      viewportHeight: vh,
      frames: frames,
      nextIndex: n
    };
  };

  // ---------------------------------------------------------------- geometry & actionability

  function frameOffset(el) {
    var x = 0, y = 0, win = el.ownerDocument.defaultView;
    try {
      while (win && win.frameElement) {
        var fr = win.frameElement.getBoundingClientRect();
        x += fr.left; y += fr.top;
        win = win.parent;
      }
    } catch (e) { /* cross-origin parent */ }
    return {x: x, y: y};
  }

  function isDisabled(el) {
    return !!(el.disabled || el.getAttribute('aria-disabled') === 'true' || (el.closest && el.closest('fieldset[disabled]') && el.localName !== 'legend'));
  }

  /** Scrolls the element to the middle of the viewport and reports where to tap. */
  API.locate = function (ref, opts) {
    opts = opts || {};
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    var el = f.el;
    if (isDisabled(el)) return fail('NOT_ACTIONABLE', 'element is disabled');
    if (opts.scroll !== false) {
      try { el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'}); } catch (e) { el.scrollIntoView(); }
    }
    var r = el.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return fail('NOT_ACTIONABLE', 'element has no visible size');
    var lx = r.left + r.width / 2, ly = r.top + r.height / 2;
    var off = frameOffset(el);
    var res = {ok: true, x: lx + off.x, y: ly + off.y, w: r.width, h: r.height, dpr: window.devicePixelRatio || 1, covered: false};
    var top = el.ownerDocument.elementFromPoint(lx, ly);
    if (top && top !== el && !el.contains(top) && !top.contains(el)) {
      res.covered = true;
      res.coveredBy = (top.localName || '') + (top.id ? '#' + top.id : '');
    }
    return res;
  };

  /** Fallback click (untrusted) when native touch is unavailable. */
  API.click = function (ref) {
    var l = API.locate(ref);
    if (!l.ok) return l;
    if (l.covered) return fail('NOT_ACTIONABLE', 'element is covered by <' + l.coveredBy + '>');
    var el = find(ref).el;
    try { el.focus({preventScroll: true}); } catch (e) { /* ignore */ }
    el.click();
    return {ok: true};
  };

  function isEditableText(el) {
    var tag = el.localName;
    if (tag === 'textarea') return true;
    if (tag === 'input') {
      var t = (el.getAttribute('type') || 'text').toLowerCase();
      return ['text', 'search', 'email', 'url', 'tel', 'password', 'number', 'date', 'time', 'datetime-local', 'month', 'week', 'color'].indexOf(t) >= 0 || !t;
    }
    return !!el.isContentEditable;
  }

  API.fill = function (ref, text) {
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    var el = f.el;
    if (isDisabled(el) || el.readOnly) return fail('NOT_ACTIONABLE', 'field is disabled or read-only');
    if (!isEditableText(el)) return fail('NOT_ACTIONABLE', 'element is not a text field (role ' + roleOf(el) + ')');
    try { el.scrollIntoView({block: 'center', behavior: 'instant'}); } catch (e) { /* ignore */ }
    el.focus();
    var win = el.ownerDocument.defaultView || window;
    if (el.localName === 'input' || el.localName === 'textarea') {
      // Use the native setter so React/Vue-controlled inputs see the change.
      var proto = Object.getPrototypeOf(el);
      var desc = null;
      while (proto && !desc) { desc = Object.getOwnPropertyDescriptor(proto, 'value'); proto = Object.getPrototypeOf(proto); }
      if (desc && desc.set) desc.set.call(el, text); else el.value = text;
      try { el.setSelectionRange(text.length, text.length); } catch (e) { /* not selectable */ }
      el.dispatchEvent(new win.InputEvent('input', {bubbles: true, inputType: 'insertText', data: text}));
      el.dispatchEvent(new win.Event('change', {bubbles: true}));
    } else {
      el.ownerDocument.execCommand('selectAll', false, null);
      if (!el.ownerDocument.execCommand('insertText', false, text)) { el.textContent = text; }
      el.dispatchEvent(new win.InputEvent('input', {bubbles: true, inputType: 'insertText', data: text}));
    }
    return {ok: true};
  };

  API.select = function (ref, value) {
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    var el = f.el;
    if (el.localName !== 'select') return fail('NOT_ACTIONABLE', 'element is not a <select>');
    if (isDisabled(el)) return fail('NOT_ACTIONABLE', 'element is disabled');
    var want = String(value).trim().toLowerCase();
    var idx = -1;
    for (var i = 0; i < el.options.length; i++) {
      var o = el.options[i];
      if (o.value.toLowerCase() === want || o.text.trim().toLowerCase() === want) { idx = i; break; }
    }
    if (idx < 0) {
      var names = [];
      for (var j = 0; j < el.options.length && j < 15; j++) names.push(clip(el.options[j].text, 40));
      return fail('NOT_ACTIONABLE', 'no option "' + value + '"; options: ' + names.join(' | '));
    }
    el.selectedIndex = idx;
    var win = el.ownerDocument.defaultView || window;
    el.dispatchEvent(new win.Event('input', {bubbles: true}));
    el.dispatchEvent(new win.Event('change', {bubbles: true}));
    return {ok: true};
  };

  API.check = function (ref, wanted) {
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    var el = f.el;
    if (isDisabled(el)) return fail('NOT_ACTIONABLE', 'element is disabled');
    var cur = typeof el.checked === 'boolean' ? el.checked : el.getAttribute('aria-checked') === 'true';
    if (cur === wanted) return {ok: true, changed: false};
    if (el.localName === 'input' && el.type === 'radio' && !wanted) return fail('NOT_ACTIONABLE', 'a radio button cannot be unchecked directly');
    el.click();
    var now = typeof el.checked === 'boolean' ? el.checked : el.getAttribute('aria-checked') === 'true';
    if (now !== wanted) return fail('NOT_ACTIONABLE', 'the page did not accept the change');
    return {ok: true, changed: true};
  };

  API.scroll = function (dir, amount) {
    var sign = dir === 'up' ? -1 : 1;
    var dy = sign * Math.max(1, (window.innerHeight || 600) * (amount || 0.8));
    var before = window.scrollY;
    window.scrollBy(0, dy);
    var moved = window.scrollY !== before;
    if (!moved) {
      var best = null, bestArea = 0;
      var all = document.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        var e = all[i];
        if (e.scrollHeight <= e.clientHeight + 4) continue;
        var oy = window.getComputedStyle(e).overflowY;
        if (oy !== 'auto' && oy !== 'scroll') continue;
        var area = e.clientWidth * e.clientHeight;
        if (area > bestArea) { best = e; bestArea = area; }
      }
      if (best) { var b = best.scrollTop; best.scrollBy(0, dy); moved = best.scrollTop !== b; }
    }
    return {ok: true, moved: moved, scrollY: Math.round(window.scrollY), scrollHeight: Math.round(document.documentElement.scrollHeight)};
  };

  API.scrollTo = function (ref) {
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    try { f.el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'}); } catch (e) { f.el.scrollIntoView(); }
    return {ok: true};
  };

  /** Fallback for keys the native path cannot send (letters with modifiers, etc). */
  API.press = function (key, mods) {
    mods = mods || [];
    var t = document.activeElement || document.body;
    var win = t.ownerDocument.defaultView || window;
    var init = {key: key, bubbles: true, cancelable: true,
      ctrlKey: mods.indexOf('Control') >= 0, shiftKey: mods.indexOf('Shift') >= 0,
      altKey: mods.indexOf('Alt') >= 0, metaKey: mods.indexOf('Meta') >= 0};
    var plain = !init.ctrlKey && !init.altKey && !init.metaKey;
    var allowed = t.dispatchEvent(new win.KeyboardEvent('keydown', init));
    if (allowed && plain && key.length === 1 && isEditableText(t)) {
      t.ownerDocument.execCommand('insertText', false, key);
    }
    t.dispatchEvent(new win.KeyboardEvent('keyup', init));
    return {ok: true};
  };

  API.focus = function (ref) {
    var f = find(ref);
    if (!f.el) return lookupFail(f);
    try { f.el.focus(); } catch (e) { /* ignore */ }
    return {ok: true};
  };

  // ---------------------------------------------------------------- activity monitor & probe

  API.ensureMonitor = function () {
    var w = window;
    if (w.__awm) return w.__awm;
    var m = {pending: 0, last: Date.now()};
    try { Object.defineProperty(w, '__awm', {value: m, enumerable: false, configurable: true}); } catch (e) { w.__awm = m; }
    try {
      new MutationObserver(function () { m.last = Date.now(); })
        .observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
    } catch (e) { /* no document yet */ }
    var of = w.fetch;
    if (typeof of === 'function') {
      w.fetch = function () {
        m.pending++; m.last = Date.now();
        var p;
        try { p = of.apply(this, arguments); } catch (e) { m.pending = Math.max(0, m.pending - 1); throw e; }
        var done = function () { m.pending = Math.max(0, m.pending - 1); m.last = Date.now(); };
        p.then(done, done);
        return p;
      };
    }
    var proto = w.XMLHttpRequest && w.XMLHttpRequest.prototype;
    var xs = proto && proto.send;
    if (xs) {
      proto.send = function () {
        m.pending++; m.last = Date.now();
        this.addEventListener('loadend', function () { m.pending = Math.max(0, m.pending - 1); m.last = Date.now(); });
        return xs.apply(this, arguments);
      };
    }
    return m;
  };

  API.sample = function () {
    var fresh = !window.__awm;
    var m = API.ensureMonitor();
    return {
      readyState: document.readyState,
      pending: m.pending,
      // First sample on this page: we only just started watching, so assume activity just happened.
      sinceMutation: fresh ? 0 : Date.now() - m.last,
      url: location.href
    };
  };

  API.probe = function (selectors) {
    var present = [];
    (selectors || []).forEach(function (s) {
      try { if (deepAll(s).length) present.push(s); } catch (e) { /* invalid selector */ }
    });
    return {url: location.href, present: present};
  };

  return API;
}
