package com.blaze.assistant.movies

/**
 * 0.46.2 the JavaScript a provider module runs on top of (written for Blaze, not copied): small axios-, fetch- and
 * cheerio-like shims over host functions (__h_fetch = [SandboxHttp]; __c_* = Jsoup on the Kotlin side), URL/URLSearchParams,
 * atob/btoa, timers, and a limited providerContext (axios, cheerio, commonHeaders, getBaseUrl, kvStore; openWebView refuses).
 * There is no file system, no require of real modules, no Blaze data. Kept as a string so the engine reads it once per call.
 */
object SandboxPrelude {
    val JS: String = """
"use strict";
(function(G){
  var logs = [];
  G.__logs = logs;
  var con = { log: function(){ if (logs.length < 40) logs.push(Array.prototype.map.call(arguments, function(a){ try { return typeof a === 'string' ? a : JSON.stringify(a); } catch(e) { return String(a); } }).join(' ').slice(0, 300)); } };
  con.info = con.log; con.warn = con.log; con.error = con.log; con.debug = function(){};
  G.console = con;
  G.setTimeout = function(f, ms){ var a = Array.prototype.slice.call(arguments, 2); __h_sleep(Math.min(Math.max(+ms || 0, 0), 10000)).then(function(){ if (typeof f === 'function') f.apply(null, a); }); return 1; };
  G.clearTimeout = function(){}; G.setInterval = function(){ return 0; }; G.clearInterval = function(){};
  G.queueMicrotask = function(f){ Promise.resolve().then(f); };
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
  G.btoa = function(s){ s = String(s); var o = '', i = 0; while (i < s.length) { var a = s.charCodeAt(i++), b = s.charCodeAt(i++), c = s.charCodeAt(i++); var e1 = a >> 2, e2 = ((a & 3) << 4) | (b >> 4), e3 = isNaN(b) ? 64 : (((b & 15) << 2) | (c >> 6)), e4 = isNaN(c) ? 64 : (c & 63); if (isNaN(b)) e3 = e4 = 64; o += B64.charAt(e1) + B64.charAt(e2) + B64.charAt(e3) + B64.charAt(e4); } return o; };
  G.atob = function(s){ s = String(s).replace(/[^A-Za-z0-9+/]/g, ''); var o = '', i = 0; while (i < s.length) { var e1 = B64.indexOf(s.charAt(i++)), e2 = B64.indexOf(s.charAt(i++)), e3 = B64.indexOf(s.charAt(i++)), e4 = B64.indexOf(s.charAt(i++)); o += String.fromCharCode((e1 << 2) | (e2 >> 4)); if (e3 >= 0 && e3 < 64) o += String.fromCharCode(((e2 & 15) << 4) | (e3 >> 2)); if (e4 >= 0 && e4 < 64) o += String.fromCharCode(((e3 & 3) << 6) | e4); } return o; };

  function USP(init){ this._p = []; if (typeof init === 'string') { init.replace(/^\?/, '').split('&').forEach(function(kv){ if (!kv) return; var i = kv.indexOf('='); var k = i < 0 ? kv : kv.slice(0, i), v = i < 0 ? '' : kv.slice(i + 1); try { this._p.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]); } catch(e) { this._p.push([k, v]); } }, this); } else if (init && typeof init === 'object') { Object.keys(init).forEach(function(k){ this._p.push([k, String(init[k])]); }, this); } }
  USP.prototype.append = function(k, v){ this._p.push([String(k), String(v)]); };
  USP.prototype.set = function(k, v){ this.delete(k); this.append(k, v); };
  USP.prototype.get = function(k){ for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) return this._p[i][1]; return null; };
  USP.prototype.getAll = function(k){ return this._p.filter(function(p){ return p[0] === k; }).map(function(p){ return p[1]; }); };
  USP.prototype.has = function(k){ return this.get(k) !== null; };
  USP.prototype.delete = function(k){ this._p = this._p.filter(function(p){ return p[0] !== k; }); };
  USP.prototype.forEach = function(f){ this._p.forEach(function(p){ f(p[1], p[0]); }); };
  USP.prototype.toString = function(){ return this._p.map(function(p){ return encodeURIComponent(p[0]) + '=' + encodeURIComponent(p[1]); }).join('&'); };
  G.URLSearchParams = USP;
  function U(u, base){ var r = JSON.parse(__u_parse(String(u), base === undefined ? '' : String(base))); if (r.error) throw new TypeError('Invalid URL: ' + u); for (var k in r) this[k] = r[k]; this.searchParams = new USP(this.search); }
  U.prototype.toString = function(){ var q = this.searchParams.toString(); return this.protocol + '//' + (this.host) + this.pathname + (q ? '?' + q : '') + this.hash; };
  Object.defineProperty(U.prototype, 'href', { get: function(){ return this.toString(); } });
  U.prototype.toJSON = function(){ return this.toString(); };
  G.URL = U;

  function lowerHeaders(h){ var o = {}; if (h) Object.keys(h).forEach(function(k){ if (h[k] !== undefined && h[k] !== null) o[k] = String(h[k]); }); return o; }
  function send(url, method, headers, body){
    var b = body; var h = lowerHeaders(headers);
    if (b !== undefined && b !== null && typeof b !== 'string') { if (b instanceof USP) { b = b.toString(); if (!h['Content-Type'] && !h['content-type']) h['Content-Type'] = 'application/x-www-form-urlencoded'; } else { b = JSON.stringify(b); if (!h['Content-Type'] && !h['content-type']) h['Content-Type'] = 'application/json'; } }
    return __h_fetch(JSON.stringify({ url: String(url), method: method || 'GET', headers: h, body: b === undefined ? null : b })).then(function(t){ var r = JSON.parse(t); if (r.error) throw new Error(r.error); return r; });
  }
  function Hdr(h){ this._h = h || {}; }
  Hdr.prototype.get = function(k){ var v = this._h[String(k).toLowerCase()]; return v === undefined ? null : v; };
  Hdr.prototype.has = function(k){ return this.get(k) !== null; };
  Hdr.prototype.forEach = function(f){ var h = this._h; Object.keys(h).forEach(function(k){ f(h[k], k); }); };
  G.fetch = function(url, init){ init = init || {}; return send(typeof url === 'object' && url.url ? url.url : url, (init.method || 'GET').toUpperCase(), init.headers, init.body).then(function(r){
    return { ok: r.status >= 200 && r.status < 300, status: r.status, statusText: r.statusText, url: r.url, redirected: false, headers: new Hdr(r.headers),
      text: function(){ return Promise.resolve(r.body); }, json: function(){ return Promise.resolve(JSON.parse(r.body)); } };
  }); };

  function mkAxios(defs){
    defs = defs || {};
    function request(cfg){
      cfg = Object.assign({}, defs, cfg || {}); cfg.headers = Object.assign({}, defs.headers || {}, (cfg && cfg.headers) || {});
      var url = cfg.url || '';
      if (cfg.baseURL && !/^https?:/i.test(url)) url = cfg.baseURL.replace(/\/+$/, '') + '/' + url.replace(/^\/+/, '');
      if (cfg.params) { var q = new USP(cfg.params).toString(); if (q) url += (url.indexOf('?') >= 0 ? '&' : '?') + q; }
      return send(url, (cfg.method || 'get').toUpperCase(), cfg.headers, cfg.data).then(function(r){
        var data = r.body;
        if (cfg.responseType !== 'text') { try { data = r.body ? JSON.parse(r.body) : ''; } catch(e) { data = r.body; } }
        var res = { data: data, status: r.status, statusText: r.statusText, headers: r.headers, config: cfg, request: { responseURL: r.url, res: { responseUrl: r.url } } };
        var ok = cfg.validateStatus ? cfg.validateStatus(r.status) : (r.status >= 200 && r.status < 300);
        if (!ok) { var e = new Error('Request failed with status code ' + r.status); e.response = res; e.config = cfg; e.isAxiosError = true; throw e; }
        return res;
      });
    }
    var ax = function(a, b){ return typeof a === 'string' ? request(Object.assign({}, b || {}, { url: a })) : request(a); };
    ax.request = request;
    ['get', 'delete', 'head', 'options'].forEach(function(m){ ax[m] = function(url, cfg){ return request(Object.assign({}, cfg || {}, { url: url, method: m })); }; });
    ['post', 'put', 'patch'].forEach(function(m){ ax[m] = function(url, data, cfg){ return request(Object.assign({}, cfg || {}, { url: url, method: m, data: data })); }; });
    ax.create = function(d){ return mkAxios(Object.assign({}, defs, d || {})); };
    ax.defaults = defs; ax.interceptors = { request: { use: function(){ return 0; } }, response: { use: function(){ return 0; } } };
    ax.isAxiosError = function(e){ return !!(e && e.isAxiosError); };
    return ax;
  }
  var axios = mkAxios({}); axios.default = axios;

  // cheerio-like: a selection is a list of host node ids; the host keeps the Jsoup nodes for this call only.
  function El(id){ this.__id = id; this.type = 'tag'; }
  Object.defineProperty(El.prototype, 'attribs', { get: function(){ return JSON.parse(__c_attrs(this.__id)); } });
  Object.defineProperty(El.prototype, 'name', { get: function(){ return __c_tag(this.__id); } });
  Object.defineProperty(El.prototype, 'tagName', { get: function(){ return __c_tag(this.__id); } });
  Object.defineProperty(El.prototype, 'children', { get: function(){ return JSON.parse(__c_rel(JSON.stringify([this.__id]), 'children', '')).map(function(i){ return new El(i); }); } });
  Object.defineProperty(El.prototype, 'data', { get: function(){ return __c_text(JSON.stringify([this.__id])); } });
  function Sel(ids, root){ this._ids = ids; this._root = root; this.length = ids.length; for (var i = 0; i < ids.length; i++) this[i] = new El(ids[i]); }
  function wrap(ids, root){ return new Sel(ids, root); }
  function J(a){ return JSON.stringify(a); }
  Sel.prototype.find = function(s){ return wrap(JSON.parse(__c_select(J(this._ids), String(s))), this._root); };
  Sel.prototype.each = function(f){ for (var i = 0; i < this._ids.length; i++) { if (f.call(this[i], i, this[i]) === false) break; } return this; };
  Sel.prototype.map = function(f){ var out = []; for (var i = 0; i < this._ids.length; i++) { var v = f.call(this[i], i, this[i]); if (v !== null && v !== undefined) { if (Array.isArray(v)) out.push.apply(out, v); else out.push(v); } } var r = out.slice(); r.get = function(i){ return i === undefined ? out.slice() : out[i < 0 ? out.length + i : i]; }; r.toArray = function(){ return out.slice(); }; return r; };
  Sel.prototype.get = function(i){ var a = []; for (var k = 0; k < this.length; k++) a.push(this[k]); return i === undefined ? a : a[i < 0 ? a.length + i : i]; };
  Sel.prototype.toArray = function(){ return this.get(); };
  Sel.prototype.first = function(){ return wrap(this._ids.slice(0, 1), this._root); };
  Sel.prototype.last = function(){ return wrap(this._ids.slice(-1), this._root); };
  Sel.prototype.eq = function(i){ return wrap(this._ids.slice(i < 0 ? this._ids.length + i : i).slice(0, 1), this._root); };
  Sel.prototype.slice = function(a, b){ return wrap(this._ids.slice(a, b), this._root); };
  Sel.prototype.text = function(){ return __c_text(J(this._ids)); };
  Sel.prototype.html = function(){ return this._ids.length ? __c_html(this._ids[0], false) : null; };
  Sel.prototype.toString = function(){ return this._ids.map(function(i){ return __c_html(i, true); }).join(''); };
  Sel.prototype.attr = function(n){ if (!this._ids.length) return undefined; if (n === undefined) return JSON.parse(__c_attrs(this._ids[0])); var v = __c_attr(this._ids[0], String(n)); return v === null ? undefined : v; };
  Sel.prototype.prop = function(n){ if (n === 'outerHTML') return this.toString(); if (n === 'innerHTML') return this.html(); if (n === 'textContent' || n === 'innerText') return this.text(); if (n === 'tagName') return this._ids.length ? __c_tag(this._ids[0]).toUpperCase() : undefined; return this.attr(n); };
  Sel.prototype.val = function(){ return this.attr('value'); };
  Sel.prototype.data = function(n){ if (n === undefined) { var a = this.attr() || {}, o = {}; Object.keys(a).forEach(function(k){ if (k.indexOf('data-') === 0) o[k.slice(5)] = a[k]; }); return o; } return this.attr('data-' + n); };
  Sel.prototype.hasClass = function(c){ return (' ' + (this.attr('class') || '') + ' ').indexOf(' ' + c + ' ') >= 0; };
  ['children', 'parent', 'parents', 'next', 'prev', 'siblings', 'closest', 'nextAll', 'prevAll', 'contents'].forEach(function(k){ Sel.prototype[k] = function(s){ return wrap(JSON.parse(__c_rel(J(this._ids), k, s === undefined ? '' : String(s))), this._root); }; });
  Sel.prototype.filter = function(s){ if (typeof s === 'function') { var keep = []; for (var i = 0; i < this._ids.length; i++) if (s.call(this[i], i, this[i])) keep.push(this._ids[i]); return wrap(keep, this._root); } return wrap(JSON.parse(__c_filter(J(this._ids), String(s), false)), this._root); };
  Sel.prototype.not = function(s){ return wrap(JSON.parse(__c_filter(J(this._ids), String(s), true)), this._root); };
  Sel.prototype.is = function(s){ return JSON.parse(__c_filter(J(this._ids), String(s), false)).length > 0; };
  Sel.prototype.has = function(s){ var self = this; return wrap(this._ids.filter(function(i){ return JSON.parse(__c_select(J([i]), String(s))).length > 0; }), this._root); };
  Sel.prototype.add = function(o){ return wrap(this._ids.concat(o && o._ids ? o._ids : []), this._root); };
  Sel.prototype.end = function(){ return this; };
  function load(html){
    var root = __c_load(String(html === undefined || html === null ? '' : html));
    var dollar = function(x, ctx){
      if (x === undefined || x === null) return wrap([], root);
      if (x instanceof Sel) return x;
      if (x instanceof El) return wrap([x.__id], root);
      if (Array.isArray(x)) return wrap(x.filter(function(e){ return e instanceof El; }).map(function(e){ return e.__id; }), root);
      if (ctx !== undefined) return dollar(ctx).find(x);
      return wrap(JSON.parse(__c_select(J([root]), String(x))), root);
    };
    dollar.html = function(s){ return s === undefined ? __c_html(root, true) : dollar(s).toString(); };
    dollar.text = function(s){ return s === undefined ? __c_text(J([root])) : dollar(s).text(); };
    dollar.root = function(){ return wrap([root], root); };
    return dollar;
  }
  var cheerio = { load: load };
  cheerio.default = cheerio;

  var kv = {
    get: function(k){ return __h_kv('get', String(k), '').then(function(t){ return t === null || t === undefined || t === '' ? undefined : JSON.parse(t); }); },
    set: function(k, v){ return __h_kv('set', String(k), JSON.stringify(v === undefined ? null : v)).then(function(){}); },
    delete: function(k){ return __h_kv('delete', String(k), '').then(function(t){ return t === 'true'; }); },
    keys: function(){ return __h_kv('keys', '', '').then(function(t){ return JSON.parse(t || '[]'); }); },
    clear: function(){ return __h_kv('clear', '', '').then(function(){}); }
  };
  G.__ctx = Object.freeze({
    axios: axios, cheerio: cheerio, commonHeaders: {}, kvStore: Object.freeze(kv),
    getBaseUrl: function(v){ return __h_baseurl(String(v || '')); },
    openWebView: function(){ return Promise.reject(new Error('Blaze does not open web views for sources')); },
    Crypto: undefined
  });
  G.__require = function(n){ if (n === 'axios') return axios; if (n === 'cheerio' || n === 'cheerio-without-node-native') return cheerio; return {}; };
  G.__signal = { aborted: false, addEventListener: function(){}, removeEventListener: function(){}, throwIfAborted: function(){} };
})(globalThis);
"""
}
