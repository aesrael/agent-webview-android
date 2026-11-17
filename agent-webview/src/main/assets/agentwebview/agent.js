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

  /** Screen-reader-only content (sr-only / visually-hidden): present for assistive tech, invisible on screen.
   *  Its text ("Posted", "Attribution") is noise for an agent reading what a person sees. */
  function isVisuallyHidden(el) {
    var win = el.ownerDocument.defaultView || window;
    var cs;
    try { cs = win.getComputedStyle(el); } catch (e) { return false; }
    if (cs.clip === 'rect(0px, 0px, 0px, 0px)' || cs.clipPath === 'inset(50%)') return true;
    if (cs.position !== 'absolute' && cs.position !== 'fixed') return false;
    var r = el.getBoundingClientRect();
    return r.width <= 1 && r.height <= 1 && cs.overflow === 'hidden';
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

    // Stable refs: an element keeps the ref it was given until it leaves the page, so a ref from an earlier
    // observe still works after a re-observe. New elements get fresh numbers (never reused); stamps on
    // elements this pass does not list are removed below, so they fail as STALE_REF instead of acting.
    var stamped = deepAll('[' + REF + ']');
    var used = {};

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
        if (hname) {
          count++;
          var hnode = {role: 'heading', name: hname, level: lvl, children: []};
          kids.push(hnode);
          // Headline links (<h3><a href>) are the main way into articles and search results: keep them.
          walkInteractiveOnly(el, hnode.children);
        }
        return;
      }

      if (!interactive && requireLayout && !LANDMARK[tag] && isVisuallyHidden(el)) return;

      if (interactive) {
        var prev = el.getAttribute(REF);
        var ref = (prev && /^@e\d+$/.test(prev) && !used[prev]) ? prev : '@e' + (n++);
        used[ref] = 1;
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
    if (opts.focus) {
      // find: only the marked match blocks. A partial pass must not clear refs elsewhere on the page.
      deepAll('[' + FOCUS + ']').forEach(function (b, i) {
        var mk = [];
        walk(b, mk);
        if (mk.length) { count++; out.push({role: 'group', name: 'match ' + (i + 1), children: mk}); }
        b.removeAttribute(FOCUS);
      });
    } else {
      walkChildren(document.body || document.documentElement, out);
      stamped.forEach(function (e) { var r = e.getAttribute(REF); if (r && !(used[r] && seen.has(e))) e.removeAttribute(REF); });
    }

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

  // ---------------------------------------------------------------- find & extract (read-only)

  var FOCUS = 'data-agent-focus';

  /** The readable unit around a match: a result card, list item or row, never the whole page. */
  function blockOf(el) {
    var best = el, c = el;
    for (var i = 0; i < 6 && c && c !== document.body; i++) {
      if (c.matches && c.matches('li,article,tr,dd,form,dialog,[role=listitem],[role=article],[role=row],[role=dialog]')) return c;
      if ((c.innerText || '').length > 800) break;
      best = c;
      c = c.parentElement;
    }
    return best;
  }

  API.find = function (q, startAt, opts) {
    q = String(q || '').replace(/\s+/g, ' ').trim().toLowerCase();
    if (!q) return fail('BAD_ACTION', 'empty query');
    deepAll('[' + FOCUS + ']').forEach(function (e) { e.removeAttribute(FOCUS); });
    var blocks = [], more = false;
    function consider(el) {
      if (!el || SKIP[el.localName] || isHidden(el, true)) return;
      var b = blockOf(el);
      if (blocks.indexOf(b) >= 0) return;
      for (var k = 0; k < blocks.length; k++) if (blocks[k].contains(b)) return;
      if (blocks.length >= 10) { more = true; return; }
      blocks.push(b);
    }
    var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var t;
    while ((t = tw.nextNode())) {
      if (t.data.replace(/\s+/g, ' ').toLowerCase().indexOf(q) >= 0) consider(t.parentElement);
      if (more) break;
    }
    // Text split across tags ("Add to <b>basket</b>") and labelled controls ("aria-label", placeholder).
    if (!more) {
      var els = document.body.querySelectorAll('a,button,label,[role=button],[aria-label],input[placeholder],h1,h2,h3,h4,li,td');
      for (var i = 0; i < els.length && !more; i++) {
        var e = els[i];
        var hay = ((e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('placeholder') || '') + ' ' + (e.innerText || '')).replace(/\s+/g, ' ').toLowerCase();
        if (hay.indexOf(q) >= 0) {
          var inner = false;
          for (var j = 0; j < blocks.length; j++) if (blocks[j].contains(e)) { inner = true; break; }
          if (!inner) consider(e);
        }
      }
    }
    blocks.forEach(function (b, i) { b.setAttribute(FOCUS, String(i)); });
    if (blocks[0]) { try { blocks[0].scrollIntoView({block: 'center', behavior: 'instant'}); } catch (e) { blocks[0].scrollIntoView(); } }
    var snippets = blocks.map(function (b) { return clip(b.innerText || b.textContent, 300); });
    var o = {};
    for (var key in (opts || {})) o[key] = opts[key];
    o.focus = true;
    return {ok: true, count: blocks.length, more: more, snippets: snippets, snapshot: API.snapshot(startAt, o)};
  };

  API.extract = function (what, maxChars) {
    maxChars = maxChars || 20000;
    var out = [];
    var used = 0;
    function add(line) {
      if (used >= maxChars) return false;
      line = line.slice(0, maxChars - used);
      out.push(line); used += line.length + 1;
      return true;
    }
    if (what === 'tables') {
      var tables = deepAll('table,[role=table],[role=grid]');
      tables.forEach(function (tb, ti) {
        if (isHidden(tb, true)) return;
        var cap = tb.querySelector('caption');
        add('## table ' + (ti + 1) + (cap ? ': ' + clip(cap.innerText, 120) : ''));
        var rows = tb.querySelectorAll('tr,[role=row]');
        for (var r = 0; r < rows.length; r++) {
          var cells = rows[r].querySelectorAll('th,td,[role=cell],[role=columnheader],[role=rowheader],[role=gridcell]');
          var vals = [];
          for (var c = 0; c < cells.length; c++) vals.push(clip(cells[c].innerText || cells[c].textContent, 200));
          if (vals.join('').trim() && !add(vals.join(' | '))) break;
        }
      });
      if (!out.length) add('(no tables on this page)');
    } else if (what === 'links') {
      var seenHref = {};
      var links = deepAll('a[href]');
      for (var i = 0; i < links.length; i++) {
        var a = links[i];
        if (isHidden(a, true) || /^javascript:/i.test(a.getAttribute('href') || '')) continue;
        var href = a.href;
        if (seenHref[href]) continue;
        seenHref[href] = 1;
        var name = clip(labelText(a) || a.innerText || a.getAttribute('title') || '', 120);
        if (!add((name || '(no text)') + ' — ' + clip(href, 200))) break;
      }
    } else {
      add(((document.body || document.documentElement).innerText || '').replace(/\n{3,}/g, '\n\n'));
    }
    var text = out.join('\n');
    if (used >= maxChars) text += '\n… cut at ' + maxChars + ' characters';
    return {ok: true, text: text};
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
      // A dialog or a big overlay (cookie wall, modal backdrop) must be dealt with first; a small
      // coverer (floating chat bubble, badge, a card's stretched link) is safe to click past.
      var tr = top.getBoundingClientRect();
      var vw = window.innerWidth || 1, vh2 = window.innerHeight || 1;
      var big = (tr.width * tr.height) / (vw * vh2) > 0.4;
      var modal = !!(top.closest && top.closest('dialog,[role=dialog],[role=alertdialog],[aria-modal=true]'));
      res.coverBlocking = big || modal;
    }
    return res;
  };

  /** Effect check around a native tap: any DOM change, request, navigation, focus or scroll since the mark. */
  API.effectMark = function () {
    var m = API.ensureMonitor();
    return {any: m.any || 0, n: m.n, url: location.href, sy: Math.round(window.scrollY), focus: document.activeElement ? document.activeElement.outerHTML.length : 0};
  };
  API.effectSince = function (k) {
    var m = window.__awm;
    if (!m || !k) return true;
    var f = document.activeElement ? document.activeElement.outerHTML.length : 0;
    return (m.any || 0) !== k.any || m.n !== k.n || location.href !== k.url || Math.round(window.scrollY) !== k.sy || f !== k.focus;
  };

  /** Fallback click (untrusted) when native touch is unavailable. */
  API.click = function (ref) {
    var l = API.locate(ref);
    if (!l.ok) return l;
    if (l.covered && l.coverBlocking) return fail('NOT_ACTIONABLE', 'element is covered by <' + l.coveredBy + '> (a dialog or overlay: close it first)');
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
    // starts: id -> start time of each request still in flight. Requests older than LONG_MS are
    // treated as long-lived (long-poll, streaming, analytics beacons) and do not hold up settling.
    var m = {pending: 0, last: Date.now(), starts: {}, n: 0};
    try { Object.defineProperty(w, '__awm', {value: m, enumerable: false, configurable: true}); } catch (e) { w.__awm = m; }
    try {
      new MutationObserver(function (recs) {
        m.any = Date.now(); // any change at all, class/style included: did a tap do anything?
        // Spinners and hover effects flip style/class forever; they say nothing about content settling.
        for (var i = 0; i < recs.length; i++) {
          var r = recs[i];
          if (r.type === 'attributes' && (r.attributeName === 'style' || r.attributeName === 'class')) continue;
          m.last = Date.now();
          return;
        }
      }).observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
    } catch (e) { /* no document yet */ }
    function begin() { var id = ++m.n; m.starts[id] = Date.now(); m.pending++; m.last = Date.now(); return id; }
    function end(id) {
      if (m.starts[id] !== undefined) { delete m.starts[id]; m.pending = Math.max(0, m.pending - 1); }
      m.last = Date.now();
    }
    var of = w.fetch;
    if (typeof of === 'function') {
      w.fetch = function () {
        var id = begin();
        var p;
        try { p = of.apply(this, arguments); } catch (e) { end(id); throw e; }
        var done = function () { end(id); };
        p.then(done, done);
        return p;
      };
    }
    var proto = w.XMLHttpRequest && w.XMLHttpRequest.prototype;
    var xs = proto && proto.send;
    if (xs) {
      proto.send = function () {
        var id = begin();
        this.addEventListener('loadend', function () { end(id); });
        return xs.apply(this, arguments);
      };
    }
    return m;
  };

  var LONG_MS = 1500;

  API.sample = function () {
    var fresh = !window.__awm;
    var m = API.ensureMonitor();
    var now = Date.now(), young = 0;
    for (var k in m.starts) { if (now - m.starts[k] < LONG_MS) young++; }
    return {
      readyState: document.readyState,
      pending: young,
      // First sample on this page: we only just started watching, so assume activity just happened.
      sinceMutation: fresh ? 0 : now - m.last,
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
