/* =========================================================
   书阁 · 阅读模块主逻辑
   ========================================================= */
(function () {
  'use strict';

  /* ---------------- 基础工具 ---------------- */
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };

  function toast(msg, ms) {
    var t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(t._timer);
    t._timer = setTimeout(function () { t.classList.remove('show'); }, ms || 1900);
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function hash(str) {
    var h = 5381, i = str.length;
    while (i) { h = (h * 33) ^ str.charCodeAt(--i); }
    return (h >>> 0).toString(36);
  }

  var Store = {
    get: function (k, d) {
      try { var v = localStorage.getItem(k); return v ? JSON.parse(v) : d; }
      catch (e) { return d; }
    },
    set: function (k, v) {
      try { localStorage.setItem(k, JSON.stringify(v)); return true; }
      catch (e) { return false; }
    },
    del: function (k) { try { localStorage.removeItem(k); } catch (e) {} }
  };

  /* ---------------- 网络层 ---------------- */
  // 页面跑在 App 内置 WebView 里，已放开跨域访问，直接请求书源站
  function request(url, opt) {
    opt = opt || {};
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      var method = (opt.method || 'GET').toUpperCase();
      try { xhr.open(method, url, true); } catch (e) { return reject(e); }
      xhr.responseType = 'arraybuffer';
      xhr.timeout = opt.timeout || 20000;
      var headers = opt.headers || {};
      headers['Accept'] = headers['Accept'] || 'text/html,application/xhtml+xml,*/*;q=0.8';
      headers['Accept-Language'] = headers['Accept-Language'] || 'zh-CN,zh;q=0.9';
      Object.keys(headers).forEach(function (k) {
        try { xhr.setRequestHeader(k, headers[k]); } catch (e) {}
      });
      if (method === 'POST' && !headers['Content-Type']) {
        try { xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8'); } catch (e) {}
      }
      xhr.onload = function () {
        var ct = '';
        try { ct = xhr.getResponseHeader('content-type') || ''; } catch (e) {}
        resolve({ status: xhr.status, buf: xhr.response, contentType: ct });
      };
      xhr.onerror = function () { reject(new Error('网络请求失败')); };
      xhr.ontimeout = function () { reject(new Error('请求超时')); };
      try { xhr.send(opt.body || null); } catch (e) { reject(e); }
    });
  }

  function detectCharset(buf, contentType, fallback) {
    var m = /charset\s*=\s*["']?([\w\-]+)/i.exec(contentType || '');
    if (m) return normCharset(m[1]);
    // 从前 4KB 里嗅探 meta charset
    try {
      var head = new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(buf).subarray(0, 4096));
      var m2 = /charset\s*=\s*["']?([\w\-]+)/i.exec(head);
      if (m2) return normCharset(m2[1]);
    } catch (e) {}
    return normCharset(fallback || 'utf-8');
  }

  function normCharset(cs) {
    cs = String(cs || '').toLowerCase();
    if (cs === 'gb2312' || cs === 'gbk' || cs === 'gb18030') return 'gbk';
    if (cs === 'big5') return 'big5';
    return cs || 'utf-8';
  }

  function decodeBuf(buf, contentType, fallback) {
    var cs = detectCharset(buf, contentType, fallback);
    try { return new TextDecoder(cs, { fatal: false }).decode(new Uint8Array(buf)); }
    catch (e) {
      try { return new TextDecoder('utf-8', { fatal: false }).decode(new Uint8Array(buf)); }
      catch (e2) { return ''; }
    }
  }

  function fetchText(url, opt) {
    opt = opt || {};
    return request(url, opt).then(function (res) {
      if (res.status && (res.status < 200 || res.status >= 400)) {
        throw new Error('HTTP ' + res.status);
      }
      return decodeBuf(res.buf, res.contentType, opt.charset);
    });
  }

  /* ---------------- 规则引擎 ---------------- */
  function splitTop(str, sep) {
    var out = [], buf = '', depth = 0;
    for (var i = 0; i < str.length; i++) {
      var c = str[i];
      if (c === '(' || c === '[') depth++;
      if (c === ')' || c === ']') depth--;
      if (depth === 0 && str.substr(i, sep.length) === sep) {
        out.push(buf); buf = ''; i += sep.length - 1;
      } else buf += c;
    }
    out.push(buf);
    return out;
  }

  // 拆出选择器与 ## 清洗段
  function splitTransforms(rule) {
    var idx = rule.indexOf('##');
    if (idx < 0) return { sel: rule, transforms: [] };
    var head = rule.slice(0, idx);
    var rest = rule.slice(idx + 2);
    var parts = rest.split('##');
    var transforms = [];
    for (var i = 0; i + 1 < parts.length; i += 2) {
      transforms.push({ re: parts[i], rep: parts[i + 1] });
    }
    return { sel: head, transforms: transforms };
  }

  function queryAll(scope, selector) {
    var nodes;
    try {
      if (scope.nodeType === 9) nodes = scope.querySelectorAll(selector);
      else if (scope.nodeType === 1) nodes = scope.querySelectorAll(selector);
      else nodes = [];
    } catch (e) { nodes = []; }
    return Array.prototype.slice.call(nodes);
  }

  // 选择器求值：逐段求值，支持 :eq(n) 与 > 子元素组合
  function selectNodes(scope, selector) {
    selector = (selector || '').trim();
    if (!selector || selector === '.') return (scope.nodeType === 1 || scope.nodeType === 9) ? [scope] : [];
    var tokens = selector.replace(/>/g, ' > ').split(/\s+/).filter(Boolean);
    var ctxs = [scope];
    var child = false;
    for (var i = 0; i < tokens.length; i++) {
      var tk = tokens[i];
      if (tk === '>') { child = true; continue; }
      var m = /:eq\((\d+)\)/.exec(tk);
      var sel = m ? tk.replace(/:eq\(\d+\)/g, '') : tk;
      var next = [];
      for (var c = 0; c < ctxs.length; c++) {
        var ctx = ctxs[c];
        var prefix = (child && ctx.nodeType === 1) ? ':scope > ' : '';
        var nodes = [];
        try { nodes = Array.prototype.slice.call(ctx.querySelectorAll(prefix + (sel || '*'))) }
        catch (e) { nodes = []; }
        if (m) {
          var idx = parseInt(m[1], 10);
          if (nodes[idx]) next.push(nodes[idx]);
        } else {
          next = next.concat(nodes);
        }
      }
      ctxs = next; child = false;
      if (!ctxs.length) return [];
    }
    return ctxs;
  }

  function resolveUrl(u, base) {
    if (!u) return '';
    u = String(u).trim();
    if (/^(https?:)?\/\//i.test(u)) return u.indexOf('//') === 0 ? 'https:' + u : u;
    if (/^data:/i.test(u)) return u;
    try { return new URL(u, base).href; } catch (e) { return u; }
  }

  function nodeValue(node, attr, base) {
    if (!node) return '';
    if (node.nodeType === 9) node = node.body || node.documentElement;
    var v = '';
    switch (attr) {
      case 'text':
        v = node.textContent || '';
        break;
      case 'ownText':
        v = Array.prototype.filter.call(node.childNodes, function (n) { return n.nodeType === 3; })
          .map(function (n) { return n.textContent; }).join('');
        break;
      case 'html':
        v = node.innerHTML || '';
        break;
      case 'href':
      case 'src':
        v = node.getAttribute ? (node.getAttribute(attr) || '') : '';
        v = resolveUrl(v, base);
        break;
      default:
        v = node.getAttribute ? (node.getAttribute(attr) || '') : '';
        break;
    }
    return v;
  }

  function applyTransforms(v, transforms) {
    for (var i = 0; i < transforms.length; i++) {
      try { v = v.replace(new RegExp(transforms[i].re, 'g'), transforms[i].rep); }
      catch (e) {}
    }
    return v;
  }

  function evalRule(scope, rule, base) {
    if (!rule) return '';
    var alts = splitTop(String(rule), '||');
    for (var i = 0; i < alts.length; i++) {
      var v = evalOne(scope, alts[i].trim(), base);
      if (v) return v;
    }
    return '';
  }

  function evalOne(scope, rule, base) {
    if (!rule) return '';
    var sp = splitTransforms(rule);
    var sel = sp.sel.trim();
    var attr = 'text';
    if (sel.indexOf('@css:') === 0) {
      sel = sel.slice(5);
      var at = sel.lastIndexOf('@');
      if (at >= 0) { attr = sel.slice(at + 1).trim(); sel = sel.slice(0, at); }
    } else if (sel.indexOf('@json:') === 0) {
      return ''; // 本模块只处理 HTML 源
    } else {
      var at2 = sel.lastIndexOf('@');
      if (at2 >= 0) { attr = sel.slice(at2 + 1).trim(); sel = sel.slice(0, at2); }
    }
    var nodes = selectNodes(scope, sel);
    var out = '';
    for (var i = 0; i < nodes.length; i++) {
      var v = nodeValue(nodes[i], attr, base);
      if (attr !== 'html') v = v.replace(/\s+/g, ' ').trim();
      v = applyTransforms(v, sp.transforms).trim();
      if (v) { out = v; break; }
    }
    return out;
  }

  /* ---------------- HTML 转文本 ---------------- */
  function htmlToText(html, removeSel) {
    if (!html) return '';
    var clean = String(html)
      .replace(/<script[\s\S]*?<\/script>/gi, '')
      .replace(/<style[\s\S]*?<\/style>/gi, '')
      .replace(/<!--[\s\S]*?-->/g, '');
    var doc;
    try { doc = new DOMParser().parseFromString('<div id="__root">' + clean + '</div>', 'text/html'); }
    catch (e) { return clean.replace(/<[^>]+>/g, ''); }
    var root = doc.getElementById('__root');
    if (!root) return '';
    if (removeSel) {
      removeSel.split(',').forEach(function (s) {
        s = s.trim();
        if (!s) return;
        try { $$(s, root).forEach(function (n) { n.parentNode.removeChild(n); }); } catch (e) {}
      });
    }
    root.querySelectorAll('br').forEach(function (br) { br.replaceWith('\n'); });
    var blocks = 'p,div,section,article,h1,h2,h3,h4,li,tr';
    root.querySelectorAll(blocks).forEach(function (n) {
      n.insertAdjacentText('afterend', '\n');
    });
    var text = root.textContent || '';
    text = text.replace(/\r/g, '')
      .replace(/[ \t\u00a0]+/g, ' ')
      .split('\n')
      .map(function (l) { return l.trim(); })
      .filter(function (l, i, arr) { return l || (arr[i - 1] && arr[i - 1] !== ''); })
      .join('\n')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
    return filterAdLines(text);
  }

  // 去掉盗版站常见的广告/导航行
  var AD_PATTERNS = [
    /^一秒记住/,
    /^请记住(本书|本站)/,
    /^天才一秒记住/,
    /^手机(用户|版)?请(浏览|访问|阅读)/,
    /^本站(域名|网址|地址)/,
    /^最新(网址|域名)/,
    /^(加入书签|投推荐票|本章未完|点击下一页继续)/,
    /^笔趣阁/,
    /^[\s\S]{0,12}(https?:\/\/|www\.)[\w.\-]+\.[a-z]{2,}/i,
    /^[\w.\-]+\.(com|net|cn|cc|la|info|org)$/i
  ];

  function filterAdLines(text) {
    return text.split('\n').filter(function (line) {
      var t = line.trim();
      if (!t) return true;
      if (t.length > 60) return true;
      for (var i = 0; i < AD_PATTERNS.length; i++) {
        if (AD_PATTERNS[i].test(t)) return false;
      }
      return true;
    }).join('\n').replace(/\n{3,}/g, '\n\n').trim();
  }

  function cleanIntro(v, bookName) {
    if (!v) return '';
    v = v.replace(/^\s*简介[:：]\s*/, '');
    if (bookName) {
      var tail = new RegExp('[\\-—]{2,}\\s*' + bookName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*$');
      v = v.replace(tail, '');
    }
    return v.trim();
  }

  /* ---------------- 书源管理 ---------------- */
  var SRC_KEY = 'zk_sources_v1';
  var sources = [];

  function loadSources() {
    var saved = Store.get(SRC_KEY, null);
    if (!saved) {
      sources = JSON.parse(JSON.stringify(BUILTIN_SOURCES));
      Store.set(SRC_KEY, sources);
      return;
    }
    // 内置源按 key 合并（保留用户开关状态，规则以内置最新为准）
    var map = {};
    saved.forEach(function (s) { map[s.key] = s; });
    BUILTIN_SOURCES.forEach(function (b) {
      if (map[b.key]) {
        var keep = map[b.key];
        var merged = JSON.parse(JSON.stringify(b));
        merged.enabled = keep.enabled !== false;
        merged.custom = !!keep.custom;
        if (keep.custom) merged = keep; // 用户手改过的完全以用户为准
        map[b.key] = merged;
      } else {
        map[b.key] = JSON.parse(JSON.stringify(b));
      }
    });
    sources = Object.keys(map).map(function (k) { return map[k]; });
    Store.set(SRC_KEY, sources);
  }

  function saveSources() { Store.set(SRC_KEY, sources); }

  function enabledSources() {
    return sources.filter(function (s) { return s.enabled; });
  }

  function sourceByKey(k) {
    for (var i = 0; i < sources.length; i++) if (sources[i].key === k) return sources[i];
    return null;
  }

  /* ---------------- 书源动作 ---------------- */
  function srcBase(s) { return (s.url || '').replace(/\/+$/, ''); }

  function srcHeaders(s) {
    return {};
  }

  function doSearch(s, key, page) {
    page = page || 1;
    var tpl = (s.search && s.search.url) || '';
    if (!tpl) return Promise.resolve([]);
    var url = tpl
      .replace(/\{\{\s*key\s*\}\}/g, encodeURIComponent(key))
      .replace(/\{\{\s*page\s*\}\}/g, page);
    url = resolveUrl(url, srcBase(s));
    var method = (s.search.method || 'GET').toUpperCase();
    var body = method === 'POST'
      ? String(s.search.body || '').replace(/\{\{\s*key\s*\}\}/g, encodeURIComponent(key))
        .replace(/\{\{\s*page\s*\}\}/g, page)
      : null;

    return fetchText(url, { method: method, body: body, headers: srcHeaders(s), charset: s.charset })
      .then(function (html) {
        if (/搜索间隔|请稍后重新搜索|频繁|too many requests/i.test(html.slice(0, 600))) {
          throw new Error('搜索太频繁，等 15 秒再搜');
        }
        var doc = new DOMParser().parseFromString(html, 'text/html');
        var listSel = s.search.list || '';
        var items = selectNodes(doc, stripCss(listSel));
        var ex = s.search.exclude;
        var out = [];
        items.forEach(function (node) {
          if (ex) { try { if (node.matches && node.matches(ex)) return; } catch (e) {} }
          var r = s.searchRules || {};
          var name = evalRule(node, r.name, url);
          var bookUrl = evalRule(node, r.bookUrl, url);
          if (!name || !bookUrl) return;
          if (bookUrl.indexOf('javascript') === 0) return;
          out.push({
            source: s.key, sourceName: s.name,
            name: name.replace(/[《》]/g, ''),
            author: evalRule(node, r.author, url).replace(/^作者[:：]/, ''),
            cover: evalRule(node, r.cover, url),
            kind: evalRule(node, r.kind, url).replace(/^(分类|类型|类别)[:：]\s*/, '').replace(/^[\[【]|[\]】]$/g, ''),
            lastChapter: evalRule(node, r.lastChapter, url),
            bookUrl: bookUrl
          });
        });
        return out;
      });
  }

  function stripCss(rule) {
    if (!rule) return '';
    var r = String(rule).trim();
    if (r.indexOf('@css:') === 0) {
      r = r.slice(5);
      var at = r.lastIndexOf('@');
      if (at >= 0) r = r.slice(0, at);
    }
    var sp = splitTransforms(r);
    return sp.sel.trim();
  }

  function getBookInfo(s, bookUrl) {
    var base = srcBase(s);
    return fetchText(bookUrl, { headers: srcHeaders(s), charset: s.charset }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var r = s.detailRules || {};
      var info = {
        name: evalRule(doc, r.name, bookUrl) || '',
        author: evalRule(doc, r.author, bookUrl).replace(/^作者[:：]/, ''),
        cover: evalRule(doc, r.cover, bookUrl),
        intro: '',
        tocUrl: ''
      };
      info.intro = cleanIntro(evalRule(doc, r.intro, bookUrl), info.name);
      var toc = evalRule(doc, r.tocUrl, bookUrl);
      info.tocUrl = toc ? resolveUrl(toc, bookUrl) : '';
      return { info: info, doc: doc, url: bookUrl };
    });
  }

  function getToc(s, detail) {
    var base = srcBase(s);
    var target = detail.info.tocUrl || detail.url;
    var pr = detail.info.tocUrl
      ? fetchText(target, { headers: srcHeaders(s), charset: s.charset }).then(function (h) {
          return new DOMParser().parseFromString(h, 'text/html');
        })
      : Promise.resolve(detail.doc);

    return pr.then(function (doc) {
      var r = s.tocRules || {};
      var nodes = selectNodes(doc, stripCss(r.list));
      var chapters = [];
      var seen = {};
      nodes.forEach(function (node) {
        var name = evalRule(node, r.name, target);
        var url = evalRule(node, r.url, target);
        if (!name || !url) return;
        if (seen[url]) return;
        seen[url] = 1;
        chapters.push({ name: name, url: url });
      });
      return chapters;
    });
  }

  function getContent(s, chapterUrl) {
    return fetchText(chapterUrl, { headers: srcHeaders(s), charset: s.charset }).then(function (html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      var r = s.contentRules || {};
      var raw = evalRule(doc, r.content, chapterUrl);
      var text = htmlToText(raw, r.remove);
      if (text.replace(/\s/g, '').length < 30) {
        // 兜底：整页正文提取
        text = htmlToText(doc.body ? doc.body.innerHTML : '', r.remove);
      }
      return text;
    });
  }

  /* ---------------- 阅读进度 / 缓存 ---------------- */
  var CH_KEY = 'zk_chapters_v1';
  var chapterCache = Store.get(CH_KEY, {});
  var cacheKeys = Store.get('zk_chapters_keys_v1', []);

  function cacheGet(k) { return chapterCache[k]; }

  function cacheSet(k, v) {
    chapterCache[k] = v;
    if (cacheKeys.indexOf(k) < 0) cacheKeys.push(k);
    while (cacheKeys.length > 40) {
      var old = cacheKeys.shift();
      delete chapterCache[old];
    }
    if (!Store.set(CH_KEY, chapterCache)) {
      // 存不下就减半再试
      while (cacheKeys.length > 12) { delete chapterCache[cacheKeys.shift()]; }
      Store.set(CH_KEY, chapterCache);
    }
    Store.set('zk_chapters_keys_v1', cacheKeys);
  }

  /* ---------------- 视图切换 ---------------- */
  var viewStack = ['shelf'];

  function showView(name, push) {
    $$('.view').forEach(function (v) { v.classList.remove('active'); });
    var v = $('#view-' + name);
    if (v) v.classList.add('active');
    var tabs = ['shelf', 'search', 'sources'];
    $('#tabbar').style.display = tabs.indexOf(name) >= 0 ? 'flex' : 'none';
    $$('#tabbar button').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-goto') === name);
    });
    if (push !== false) {
      if (viewStack[viewStack.length - 1] !== name) viewStack.push(name);
    }
  }

  function goBack() {
    if (viewStack.length > 1) viewStack.pop();
    var prev = viewStack[viewStack.length - 1] || 'shelf';
    if (prev === 'book' && viewStack.length <= 1) prev = 'shelf';
    showView(prev, false);
  }

  /* ---------------- 书架 ---------------- */
  var SHELF_KEY = 'zk_shelf_v1';
  var shelf = Store.get(SHELF_KEY, []);
  var current = null; // {sourceKey, bookUrl, name, author, cover, chapters, index}

  function bookKeyOf(b) { return b.source + '|' + b.bookUrl; }

  function saveShelf() { Store.set(SHELF_KEY, shelf); }

  function inShelf(b) {
    var k = bookKeyOf(b);
    return shelf.some(function (x) { return bookKeyOf(x) === k; });
  }

  function addToShelf(b) {
    if (inShelf(b)) return false;
    shelf.unshift({
      source: b.source, sourceName: b.sourceName || '', bookUrl: b.bookUrl,
      name: b.name, author: b.author || '', cover: b.cover || '',
      lastChapter: b.lastChapter || '', addedAt: Date.now(),
      progress: null
    });
    saveShelf();
    return true;
  }

  function renderShelf() {
    var wrap = $('#shelf-list');
    var empty = $('#shelf-empty');
    if (!shelf.length) {
      wrap.innerHTML = '';
      empty.style.display = 'flex';
      return;
    }
    empty.style.display = 'none';
    wrap.innerHTML = shelf.map(function (b, i) {
      var p = b.progress;
      var pct = p && p.percent ? Math.min(100, Math.round(p.percent * 100)) : 0;
      var sub = p && p.chapterName ? p.chapterName : (b.lastChapter || b.author || '未开始');
      return '<div class="shelf-item" data-idx="' + i + '">' +
        coverImg(b.cover, b.name) +
        '<div class="shelf-meta">' +
        '<b>' + esc(b.name) + '</b>' +
        '<div class="sub">' + esc(sub) + '</div>' +
        '<div class="progress-wrap"><div class="progress-bar" style="width:' + pct + '%"></div></div>' +
        '</div></div>';
    }).join('');
  }

  function coverImg(url, name) {
    if (url) {
      return '<img class="cov" src="' + esc(url) + '" onerror="this.style.display=\'none\'" alt="">';
    }
    return '<div class="cov" style="display:flex;align-items:center;justify-content:center;font-size:22px">📕</div>';
  }

  /* ---------------- 搜索 ---------------- */
  var searchSeq = 0;

  function runSearch() {
    var key = ($('#search-input').value || '').trim();
    if (!key) { toast('先输个书名或作者'); return; }
    var seq = ++searchSeq;
    var list = enabledSources();
    if (!list.length) { toast('没有可用的书源，去「书源」里打开一个'); return; }
    $('#search-result').innerHTML = '';
    $('#search-status').textContent = '正在 ' + list.length + ' 个书源里找「' + key + '」…';

    var done = 0, total = 0;
    list.forEach(function (s) {
      doSearch(s, key, 1).then(function (items) {
        if (seq !== searchSeq) return;
        if (items.length) { renderGroup(s, items); total += items.length; }
        done++;
        $('#search-status').textContent = '找到 ' + total + ' 本（' + done + '/' + list.length + ' 个书源已回）';
      }).catch(function (err) {
        if (seq !== searchSeq) return;
        done++;
        $('#search-status').textContent = '部分书源没回话（' + done + '/' + list.length + '）：' +
          (err && err.message ? err.message : '未知错误');
      });
    });
  }

  function renderGroup(s, items) {
    var div = document.createElement('div');
    div.className = 'src-group';
    div.innerHTML = '<div class="src-group-title">' + esc(s.name) + ' · ' + items.length + ' 条</div>' +
      items.map(function (it) {
        return '<div class="result-item" data-source="' + esc(it.source) + '" data-url="' + esc(it.bookUrl) + '">' +
          coverImg(it.cover, it.name) +
          '<div class="meta"><b>' + esc(it.name) + '</b>' +
          '<div class="sub">' + esc(it.author || '佚名') + (it.kind ? ' · ' + esc(it.kind) : '') + '</div>' +
          '<div class="sub">' + esc(it.lastChapter || '') + '</div>' +
          '</div></div>';
      }).join('');
    $('#search-result').appendChild(div);
  }

  /* ---------------- 打开书籍 ---------------- */
  var tocCache = {}; // bookKey -> chapters

  function openBook(sourceKey, bookUrl) {
    var s = sourceByKey(sourceKey);
    if (!s) { toast('书源不存在'); return; }
    showView('book');
    $('#book-title').textContent = '加载中…';
    $('#book-body').innerHTML = '<div class="read-content"><div class="loading">正在打开…</div></div>';

    getBookInfo(s, bookUrl).then(function (detail) {
      var info = detail.info;
      var shelfBook = shelf.find(function (x) { return bookKeyOf(x) === (sourceKey + '|' + bookUrl); });
      current = {
        sourceKey: sourceKey, bookUrl: bookUrl,
        name: info.name || (shelfBook && shelfBook.name) || '未命名',
        author: info.author || (shelfBook && shelfBook.author) || '',
        cover: info.cover || (shelfBook && shelfBook.cover) || '',
        intro: info.intro || '',
        chapters: null, index: 0
      };
      $('#book-title').textContent = current.name;
      renderBookHead();
      return getToc(s, detail).then(function (chapters) {
        current.chapters = chapters;
        tocCache[sourceKey + '|' + bookUrl] = chapters;
        renderChapterList();
      }).catch(function (e) {
        $('#book-body').innerHTML += '<p class="dim">目录没取到：' + esc(e.message) + '</p>';
      });
    }).catch(function (e) {
      $('#book-title').textContent = '打开失败';
      $('#book-body').innerHTML = '<div class="read-content"><div class="loading">没打开：' + esc(e.message) +
        '<br><br>换个书源试试，或者点右上角⋯用浏览器打开原页</div></div>';
    });
  }

  function renderBookHead() {
    var b = current;
    var added = inShelf({ source: b.sourceKey, bookUrl: b.bookUrl });
    var prog = (shelf.find(function (x) { return bookKeyOf(x) === (b.sourceKey + '|' + b.bookUrl); }) || {}).progress;
    var body = $('#book-body');
    body.innerHTML =
      '<div class="book-head">' + coverImg(b.cover, b.name) +
      '<div class="info"><h2>' + esc(b.name) + '</h2>' +
      '<div class="sub">' + esc(b.author || '佚名') + '</div>' +
      '<div class="sub" id="chap-count">章节加载中…</div></div></div>' +
      '<div class="book-actions">' +
      '<button class="btn primary" id="btn-continue">' + (prog ? '继续阅读' : '开始阅读') + '</button>' +
      '<button class="btn" id="btn-shelf-toggle">' + (added ? '已在书架' : '加入书架') + '</button>' +
      '</div>' +
      (b.intro ? '<div class="book-intro" id="book-intro">' + esc(b.intro) + '</div>' +
        '<div class="intro-toggle" id="intro-toggle">展开简介</div>' : '') +
      '<div class="chap-head"><h3>目录</h3><span class="dim" id="chap-filter-hint"></span></div>' +
      '<div id="chap-list" class="chap-list"></div>';

    var intro = $('#book-intro');
    if (intro) {
      $('#intro-toggle').addEventListener('click', function () {
        intro.classList.toggle('open');
        this.textContent = intro.classList.contains('open') ? '收起简介' : '展开简介';
      });
    }
    $('#btn-shelf-toggle').addEventListener('click', function () {
      var added = addToShelf({
        source: b.sourceKey, sourceName: (sourceByKey(b.sourceKey) || {}).name || '',
        bookUrl: b.bookUrl, name: b.name, author: b.author, cover: b.cover
      });
      toast(added ? '已加入书架' : '已经在书架里了');
      this.textContent = '已在书架';
    });
    $('#btn-continue').addEventListener('click', function () {
      if (!current.chapters || !current.chapters.length) { toast('目录还没加载好'); return; }
      var p = (shelf.find(function (x) { return bookKeyOf(x) === (b.sourceKey + '|' + b.bookUrl); }) || {}).progress;
      openChapter(p && p.index != null ? p.index : 0, p && p.percent);
    });
  }

  function renderChapterList() {
    var list = $('#chap-list');
    if (!list) return;
    var chapters = current.chapters || [];
    $('#chap-count').textContent = chapters.length ? '共 ' + chapters.length + ' 章' : '暂无章节';
    var prog = (shelf.find(function (x) { return bookKeyOf(x) === (current.sourceKey + '|' + current.bookUrl); }) || {}).progress;
    var curIdx = prog ? prog.index : -1;

    var shown = 0, CHUNK = 120;
    function renderMore() {
      var end = Math.min(chapters.length, shown + CHUNK);
      var html = '';
      for (var i = shown; i < end; i++) {
        html += '<div class="chap-item' + (i === curIdx ? ' current' : '') + '" data-idx="' + i + '">' +
          '<span class="idx">' + (i + 1) + '</span><span class="t">' + esc(chapters[i].name) + '</span></div>';
      }
      list.insertAdjacentHTML('beforeend', html);
      shown = end;
      if (shown < chapters.length) {
        var more = document.createElement('div');
        more.className = 'chap-item';
        more.innerHTML = '<span class="t" style="text-align:center;color:var(--primary)">显示更多（还有 ' +
          (chapters.length - shown) + ' 章）</span>';
        more.addEventListener('click', function () { more.remove(); renderMore(); });
        list.appendChild(more);
      }
    }
    list.innerHTML = '';
    renderMore();
  }

  /* ---------------- 阅读 ---------------- */
  var readerState = { chapterIdx: 0, scrollTop: 0 };

  function openChapter(idx, percent) {
    var b = current;
    if (!b || !b.chapters || !b.chapters[idx]) { toast('章节不存在'); return; }
    var s = sourceByKey(b.sourceKey);
    var ch = b.chapters[idx];
    showView('read');
    readerState.chapterIdx = idx;
    $('#read-chapter').textContent = ch.name;
    var box = $('#read-content');
    box.innerHTML = '<div class="loading">正在加载正文…</div>';
    $('#read-scroll').scrollTop = 0;

    var cacheKey = hash(b.sourceKey + '|' + ch.url);
    var cached = cacheGet(cacheKey);
    var task = cached ? Promise.resolve(cached) : getContent(s, ch.url).then(function (text) {
      cacheSet(cacheKey, text);
      return text;
    });

    task.then(function (text) {
      if (readerState.chapterIdx !== idx) return;
      var paras = String(text || '').split('\n').filter(function (l) { return l.trim(); });
      box.innerHTML = '<h2>' + esc(ch.name) + '</h2>' +
        paras.map(function (p) { return '<p>' + esc(p) + '</p>'; }).join('');
      if (percent && percent > 0.01) {
        var max = $('#read-scroll').scrollHeight - $('#read-scroll').clientHeight;
        $('#read-scroll').scrollTop = Math.round(max * Math.min(percent, 0.98));
      }
      prevChapterText = null;
      saveProgress(idx, ch.name);
    }).catch(function (e) {
      box.innerHTML = '<div class="loading">正文没取到：' + esc(e.message) +
        '<br><br>点下面「下一章」再试，或返回换书源</div>';
    });
  }

  var prevChapterText = null;

  function saveProgress(idx, chapterName) {
    if (!current) return;
    var k = current.sourceKey + '|' + current.bookUrl;
    var item = shelf.find(function (x) { return bookKeyOf(x) === k; });
    if (!item) return;
    var el = $('#read-scroll');
    var percent = 0;
    if (el && el.scrollHeight > el.clientHeight) {
      percent = el.scrollTop / (el.scrollHeight - el.clientHeight);
    }
    item.progress = { index: idx, chapterName: chapterName, percent: percent, at: Date.now() };
    saveShelf();
    renderShelf();
  }

  function siblingChapter(delta) {
    var b = current;
    if (!b || !b.chapters) return;
    var n = readerState.chapterIdx + delta;
    if (n < 0) { toast('已经是第一章'); return; }
    if (n >= b.chapters.length) { toast('已经是最后一章'); return; }
    openChapter(n);
  }

  /* ---------------- 书源面板 ---------------- */
  function renderSources() {
    var wrap = $('#source-list');
    wrap.innerHTML = sources.map(function (s, i) {
      var cls = !s.enabled ? 'off' : (s.bad ? 'bad' : '');
      return '<div class="source-item" data-idx="' + i + '">' +
        '<span class="dot ' + cls + '"></span>' +
        '<div class="nm"><b>' + esc(s.name) + '</b><span>' + esc(s.url) + '</span></div>' +
        '<div class="sw ' + (s.enabled ? 'on' : '') + '" data-toggle="' + i + '"></div>' +
        '</div>';
    }).join('') + '<div style="height:20px"></div>';
  }

  /* ---------------- 书源导入 ---------------- */
  function openImport() {
    var sheet = $('#sheet');
    $('#sheet-title').textContent = '导入书源';
    $('#sheet-body').innerHTML =
      '<p class="dim" style="margin:0 0 8px">把「阅读」App 的书源 JSON 粘进来（支持单个对象或数组）。只识别 CSS 规则的书源。</p>' +
      '<textarea id="import-text" placeholder=\'{"bookSourceName":"站点名","bookSourceUrl":"https://...","searchUrl":"/search?q={{key}}","ruleSearch":{"bookList":"@css:.item","name":"@css:a@text","author":"@css:.author@text","bookUrl":"@css:a@href"},"ruleToc":{...},"ruleContent":{...}}\'></textarea>';
    sheet.classList.add('open');
    sheet._onOk = function () {
      var raw = ($('#import-text').value || '').trim();
      if (!raw) { toast('先粘点东西'); return false; }
      var data;
      try { data = JSON.parse(raw); } catch (e) { toast('JSON 格式不对'); return false; }
      var arr = Array.isArray(data) ? data : [data];
      var added = 0;
      arr.forEach(function (src) {
        var conv = convertSource(src);
        if (!conv) return;
        var exist = sources.findIndex(function (x) { return x.key === conv.key; });
        if (exist >= 0) sources[exist] = conv; else sources.push(conv);
        added++;
      });
      if (!added) { toast('没识别出可用书源'); return false; }
      saveSources();
      renderSources();
      toast('导入 ' + added + ' 个书源');
      return true;
    };
  }

  // 「阅读」书源 → 本模块格式
  function convertSource(src) {
    try {
      var name = src.bookSourceName || src.name || '未命名书源';
      var url = (src.bookSourceUrl || src.url || '').replace(/\/+$/, '');
      if (!url) return null;
      var searchUrl = src.searchUrl || '';
      if (typeof searchUrl === 'object' && searchUrl) searchUrl = searchUrl.url || '';
      var rs = src.ruleSearch || {};
      var ri = src.ruleBookInfo || {};
      var rt = src.ruleToc || {};
      var rc = src.ruleContent || {};

      function css(v) {
        if (!v) return '';
        v = String(v).trim();
        if (/^(class|tag|id|text)\./.test(v)) {
          // 阅读的 class.xx.0 语法 → CSS
          var parts = v.split('.');
          var kind = parts[0], target = parts[1], idx = parts[2];
          var sel = kind === 'class' ? '.' + target : (kind === 'tag' ? target : '#' + target);
          if (idx === '0') sel += ':eq(0)';
          return '@css:' + sel + '@text';
        }
        if (/^@css:/.test(v)) return v;
        if (/^@XPath:|^\/\/|^id\.|^tag\.|^class\./.test(v)) return '';
        if (v.indexOf('@') < 0) v = '@css:' + v + '@text';
        else if (v.indexOf('@css:') !== 0) v = '@css:' + v;
        return v;
      }

      var out = {
        key: 'u_' + hash(url).slice(0, 8),
        name: name,
        url: url,
        group: src.bookSourceGroup || '导入',
        charset: /gbk|gb2312/i.test(src.bookSourceCharset || '') ? 'gbk' : 'utf-8',
        enabled: true,
        custom: true,
        search: {
          method: /post/i.test(src.searchMethod || '') ? 'POST' : 'GET',
          url: searchUrl,
          body: src.searchBody || '',
          list: css(rs.bookList),
          exclude: ''
        },
        searchRules: {
          name: css(rs.name),
          author: css(rs.author),
          bookUrl: css(rs.bookUrl),
          cover: css(rs.coverUrl),
          kind: css(rs.kind),
          lastChapter: css(rs.lastChapter)
        },
        detailRules: {
          name: css(ri.name),
          author: css(ri.author),
          cover: css(ri.coverUrl),
          intro: css(ri.intro),
          tocUrl: css(ri.tocUrl)
        },
        tocRules: {
          list: css(rt.chapterList),
          name: css(rt.chapterName),
          url: css(rt.chapterUrl)
        },
        contentRules: {
          content: css(rc.content).replace(/@text$/, '@html'),
          title: '',
          remove: rc.removeHtml || ''
        }
      };
      if (!out.search.list || !out.tocRules.list) out.bad = true;
      return out;
    } catch (e) { return null; }
  }

  /* ---------------- 设置 ---------------- */
  var SET_KEY = 'zk_settings_v1';
  var settings = Store.get(SET_KEY, { theme: 'day', fontSize: 18, lineHeight: 1.8 });

  function applySettings() {
    document.body.classList.remove('theme-day', 'theme-night', 'theme-sepia');
    document.body.classList.add('theme-' + (settings.theme || 'day'));
    document.documentElement.style.setProperty('--reader-font', settings.fontSize + 'px');
    document.documentElement.style.setProperty('--reader-line', settings.lineHeight);
    $('#font-size-label').textContent = settings.fontSize;
    $('#line-label').textContent = settings.lineHeight;
    $$('.theme-picker button').forEach(function (b) {
      b.classList.toggle('on', b.getAttribute('data-theme') === settings.theme);
    });
    Store.set(SET_KEY, settings);
  }

  /* ---------------- 事件绑定 ---------------- */
  function bind() {
    $$('[data-goto]').forEach(function (b) {
      b.addEventListener('click', function () {
        var t = b.getAttribute('data-goto');
        viewStack = [t];
        showView(t);
        if (t === 'shelf') renderShelf();
        if (t === 'sources') renderSources();
      });
    });

    $('#btn-search').addEventListener('click', runSearch);
    $('#search-input').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') runSearch();
    });

    $('#shelf-list').addEventListener('click', function (e) {
      var item = e.target.closest('.shelf-item');
      if (!item) return;
      var b = shelf[+item.getAttribute('data-idx')];
      if (b) openBook(b.source, b.bookUrl);
    });
    $('#shelf-empty').addEventListener('click', function (e) {
      if (e.target.closest('[data-goto]')) {
        viewStack = ['search'];
        showView('search');
      }
    });
    $('#btn-shelf-refresh').addEventListener('click', refreshShelf);

    $('#search-result').addEventListener('click', function (e) {
      var it = e.target.closest('.result-item');
      if (!it) return;
      openBook(it.getAttribute('data-source'), it.getAttribute('data-url'));
    });

    $('#book-body').addEventListener('click', function (e) {
      var ch = e.target.closest('.chap-item[data-idx]');
      if (ch) { openChapter(+ch.getAttribute('data-idx')); }
    });

    $$('[data-back]').forEach(function (b) {
      b.addEventListener('click', goBack);
    });

    $('#btn-read-back').addEventListener('click', function () {
      while (viewStack.length && (viewStack[viewStack.length - 1] === 'read' || viewStack[viewStack.length - 1] === 'toc')) {
        viewStack.pop();
      }
      var b = current;
      var item = b ? shelf.find(function (x) { return bookKeyOf(x) === (b.sourceKey + '|' + b.bookUrl); }) : null;
      if (item) {
        if (viewStack[viewStack.length - 1] !== 'book') viewStack.push('book');
        showView('book', false);
      } else {
        viewStack = ['shelf'];
        showView('shelf', false);
      }
    });

    $('#btn-read-menu').addEventListener('click', function () {
      $('#read-panel').classList.toggle('open');
    });
    $('#btn-prev-chapter').addEventListener('click', function () { siblingChapter(-1); });
    $('#btn-next-chapter').addEventListener('click', function () { siblingChapter(1); });
    $('#btn-toc').addEventListener('click', function () {
      $('#read-panel').classList.remove('open');
      renderTocView();
      showView('toc');
    });
    $('#btn-toc-jump').addEventListener('click', function () {
      var b = current;
      if (b) toast('当前第 ' + (readerState.chapterIdx + 1) + ' 章 / 共 ' + (b.chapters ? b.chapters.length : 0) + ' 章');
    });

    $('#read-scroll').addEventListener('scroll', function () {
      if (!current) return;
      clearTimeout(window.__saveTimer);
      window.__saveTimer = setTimeout(function () {
        saveProgress(readerState.chapterIdx, (current.chapters[readerState.chapterIdx] || {}).name || '');
      }, 700);
    });

    // 点击正文两侧翻页
    $('#read-content').addEventListener('click', function (e) {
      var w = window.innerWidth;
      var x = e.clientX;
      if (x < w * 0.28) siblingChapter(-1);
      else if (x > w * 0.72) siblingChapter(1);
      else {
        $('#reader-bar').classList.toggle('hidden');
        $('#read-panel').classList.remove('open');
      }
    });

    $$('.theme-picker button').forEach(function (b) {
      b.addEventListener('click', function () {
        settings.theme = b.getAttribute('data-theme');
        applySettings();
      });
    });
    $('#font-plus').addEventListener('click', function () {
      settings.fontSize = Math.min(30, settings.fontSize + 1); applySettings();
    });
    $('#font-minus').addEventListener('click', function () {
      settings.fontSize = Math.max(13, settings.fontSize - 1); applySettings();
    });
    $('#line-plus').addEventListener('click', function () {
      settings.lineHeight = Math.min(2.6, +(settings.lineHeight + 0.1).toFixed(1)); applySettings();
    });
    $('#line-minus').addEventListener('click', function () {
      settings.lineHeight = Math.max(1.3, +(settings.lineHeight - 0.1).toFixed(1)); applySettings();
    });

    // 书源
    $('#source-list').addEventListener('click', function (e) {
      var sw = e.target.closest('[data-toggle]');
      var item = e.target.closest('.source-item');
      if (sw) {
        var i = +sw.getAttribute('data-toggle');
        sources[i].enabled = !sources[i].enabled;
        saveSources();
        renderSources();
        return;
      }
      if (item) {
        var j = +item.getAttribute('data-idx');
        showSourceDetail(j);
      }
    });
    $('#btn-add-source').addEventListener('click', openImport);

    // 弹层
    $('#sheet-cancel').addEventListener('click', function () { $('#sheet').classList.remove('open'); });
    $('#sheet-ok').addEventListener('click', function () {
      var sheet = $('#sheet');
      if (typeof sheet._onOk === 'function') {
        if (sheet._onOk() !== false) sheet.classList.remove('open');
      } else sheet.classList.remove('open');
    });

    $('#btn-book-more').addEventListener('click', function () {
      if (!current) return;
      openExt('');
    });
  }

  function openExt(html) {
    $('#sheet-title').textContent = current ? current.name : '操作';
    $('#sheet-body').innerHTML = '<p class="dim">源页面：</p><p style="word-break:break-all;font-size:12.5px">' +
      esc(current.bookUrl) + '</p>' +
      '<div class="sheet-actions" style="margin-top:12px">' +
      '<button class="btn" id="btn-open-ext">用浏览器打开</button>' +
      '<button class="btn" id="btn-del-shelf">从书架删除</button></div>';
    $('#sheet').classList.add('open');
    $('#sheet-ok').style.display = 'none';
    $('#btn-open-ext').addEventListener('click', function () {
      try { window.open(current.bookUrl, '_blank'); } catch (e) {}
    });
    $('#btn-del-shelf').addEventListener('click', function () {
      var k = current.sourceKey + '|' + current.bookUrl;
      shelf = shelf.filter(function (x) { return bookKeyOf(x) !== k; });
      saveShelf(); renderShelf();
      $('#sheet').classList.remove('open');
      toast('已从书架删除');
    });
  }

  function showSourceDetail(i) {
    var s = sources[i];
    $('#sheet-title').textContent = s.name;
    $('#sheet-body').innerHTML = '<p class="dim" style="word-break:break-all">' + esc(s.url) + '</p>' +
      '<p class="dim">搜索：' + esc((s.search && s.search.url) || '—') + '</p>' +
      '<div class="sheet-actions" style="margin-top:12px">' +
      '<button class="btn" id="btn-del-src">删除书源</button></div>';
    $('#sheet').classList.add('open');
    $('#sheet-ok').style.display = 'none';
    $('#btn-del-src').addEventListener('click', function () {
      sources.splice(i, 1);
      saveSources(); renderSources();
      $('#sheet').classList.remove('open');
    });
  }

  function renderTocView() {
    var b = current;
    if (!b || !b.chapters) { toast('还没有目录'); return; }
    var wrap = $('#toc-list');
    wrap.innerHTML = b.chapters.map(function (c, i) {
      return '<div class="chap-item' + (i === readerState.chapterIdx ? ' current' : '') +
        '" data-idx="' + i + '"><span class="idx">' + (i + 1) + '</span><span class="t">' +
        esc(c.name) + '</span></div>';
    }).join('');
    wrap.onclick = function (e) {
      var it = e.target.closest('.chap-item[data-idx]');
      if (!it) return;
      openChapter(+it.getAttribute('data-idx'));
    };
    setTimeout(function () {
      var cur = wrap.querySelector('.chap-item.current');
      if (cur) cur.scrollIntoView({ block: 'center' });
    }, 30);
  }

  function refreshShelf() {
    if (!shelf.length) { toast('书架是空的'); return; }
    toast('正在检查更新…');
    var i = 0;
    function next() {
      if (i >= shelf.length) { renderShelf(); toast('书架已更新'); return; }
      var b = shelf[i++];
      var s = sourceByKey(b.source);
      if (!s) return next();
      getBookInfo(s, b.bookUrl).then(function (d) {
        if (d.info.name) b.name = d.info.name;
        if (d.info.cover) b.cover = d.info.cover;
        if (d.info.author) b.author = d.info.author;
        saveShelf();
      }).catch(function () {}).then(next);
    }
    next();
  }

  /* ---------------- 启动 ---------------- */
  function boot() {
    loadSources();
    applySettings();
    bind();
    renderShelf();
    renderSources();
    showView('shelf', false);

    // 恢复上次阅读（有进度就提示）
    if (!sources.length) {
      toast('书源没加载出来');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else boot();

  /* TVBox 小说源接线：Java 把 novel:// 的标题正文直接注入，不走书源抓取。
     段落渲染复用 openChapter 的写法；这种模式下 current 为空，上下章按钮点按无动作。 */
  function showNovel(title, content) {
    showView('read');
    readerState.chapterIdx = 0;
    $('#read-chapter').textContent = title || '章节正文';
    var box = $('#read-content');
    var paras = String(content || '').split('\n').filter(function (l) { return l.trim(); });
    box.innerHTML = '<h2>' + esc(title || '') + '</h2>' +
      paras.map(function (p) { return '<p>' + esc(p) + '</p>'; }).join('');
    var sc = $('#read-scroll');
    if (sc) sc.scrollTop = 0;
  }
  window.showNovel = showNovel;

  // 调试/自检入口（不影响正常使用）
  window.__ZK__ = {
    sources: function () { return sources; },
    search: doSearch,
    info: getBookInfo,
    toc: getToc,
    content: getContent,
    openBook: openBook,
    fetchText: fetchText,
    htmlToText: htmlToText,
    evalRule: evalRule,
    selectNodes: selectNodes
  };
})();
