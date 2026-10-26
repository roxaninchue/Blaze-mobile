package com.blaze.assistant.browser

import org.json.JSONObject

/**
 * 0.45.29 browser tasks, the page scripts of Blaze's background browser ([BrowserEngine]), ported from the Blaze Phone
 * browser engine (browser/phone/PhoneScripts.kt), which did these things right:
 *
 *  - Stable element numbers: an element keeps its `data-bz` number for the page's life (a WeakMap is the truth; a
 *    `data-bz` attribute that isn't the map's, e.g. copied by a page script or left by an older read, is cleared on every
 *    read), and a number is looked up by the map, never by "the first element with that attribute".
 *  - Labels the way people see them: aria-label, `<label for>` / wrapping label, aria-labelledby, then text / placeholder.
 *  - Viewport first: elements and text on screen come first, then what is below (and above), with the scroll position.
 *  - Real input: a click sends pointer and mouse down / up at the element's centre before click(), and says when the
 *    element is covered (a cookie banner), hidden or disabled instead of clicking something else.
 *  - Honest results: typing checks the value landed, a `<select>` is chosen by option, keys and waits report what happened.
 *  - Secret values (passwords, codes, card fields, values Blaze typed from the vault) never leave the page.
 */
object BrowserScripts {
    private const val COMMON = """
      var BZSEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=switch],[role=combobox],[role=textbox],[onclick],[contenteditable=true]';
      function bzVis(e){if(!e||!e.getBoundingClientRect)return false;var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!='hidden'&&s.display!='none'&&s.opacity!='0';}
      function bzMap(){return window.__bzMap||(window.__bzMap=new WeakMap());}
      function bzId(e){var m=bzMap(),id=m.get(e);
        if(id!=null&&e.getAttribute('data-bz')===String(id))return id;
        if(typeof window.__bzN!=='number')window.__bzN=0;id=window.__bzN++;m.set(e,id);e.setAttribute('data-bz',String(id));return id;}
      function bzGet(n){var m=bzMap(),l=document.querySelectorAll('[data-bz="'+n+'"]'),hit=null;
        for(var i=0;i<l.length;i++){var e=l[i];if(m.get(e)===Number(n)){if(bzVis(e))return e;hit=hit||e;}}return hit;}
      function bzOwn(lb,e){var o='';[].forEach.call(lb.childNodes,function(c){if(c===e)return;if(c.nodeType==1&&c.contains(e))o+=' '+bzOwn(c,e);else o+=' '+(c.nodeType==1?(c.innerText||''):(c.textContent||''));});return o;}
      function bzLab(e){var l=e.getAttribute('aria-label')||'';
        if(!l&&e.labels&&e.labels.length)l=bzOwn(e.labels[0],e);
        if(!l&&e.getAttribute('aria-labelledby')){l=e.getAttribute('aria-labelledby').split(/\s+/).map(function(i){var x=document.getElementById(i);return x?(x.innerText||''):'';}).join(' ');}
        var t=e.tagName;if(!l&&!(t=='INPUT'||t=='TEXTAREA'||t=='SELECT'))l=e.innerText||'';
        if(!l)l=e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||(t=='INPUT'&&/submit|button/.test(e.type)?e.value:'')||'';
        if(!l&&t=='A'){var im=e.querySelector('img[alt]');if(im)l=im.getAttribute('alt');}
        if(!l)l=e.getAttribute('name')||e.id||'';
        return (l||'').replace(/\s+/g,' ').trim().slice(0,90);}
      function bzKey(e){return ((e.getAttribute('name')||'')+' '+(e.id||'')+' '+(e.getAttribute('autocomplete')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')).slice(0,160);}
      function bzSens(e){var ty=(e.getAttribute('type')||'').toLowerCase(),ac=(e.getAttribute('autocomplete')||'').toLowerCase();
        return e.hasAttribute('data-bz-secret')||ty=='password'||/^cc-/.test(ac)||/one-time-code|password/.test(ac)||/(pass(word|wd|code)?|pwd|otp|one.?time|cvv|cvc|csc|card.?(number|no)|iban|ssn|security.?code|verification.?code)/i.test(bzKey(e));}
      function bzKind(e){var t=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||'').toLowerCase(),r=e.getAttribute('role');
        if(t=='a')return 'link';if(t=='select')return 'select';if(t=='textarea')return 'field:textarea';
        if(t=='input'){if(ty=='checkbox'||ty=='radio')return ty;if(/submit|button|reset|image/.test(ty))return 'button';return 'field'+(ty?':'+ty:'');}
        if(e.isContentEditable)return 'field';if(r&&r!='button')return r;return 'button';}
      function bzEditable(e){var t=e.tagName,ty=(e.type||'').toLowerCase();return e.isContentEditable||t=='TEXTAREA'||(t=='INPUT'&&!/checkbox|radio|submit|button|reset|image|file|hidden|range|color/.test(ty));}
      function bzOpts(e){return [].slice.call(e.options||[]).slice(0,12).map(function(o){return o.text.replace(/\s+/g,' ').trim().slice(0,40);});}
      function bzNode(e){var id=bzId(e),t=e.tagName.toLowerCase(),k=bzKind(e),v='';
        if(t=='select'){var o=e.options[e.selectedIndex];v=o?o.text:'';}
        else if(bzEditable(e)){v=e.isContentEditable?(e.innerText||''):(e.value||'');}
        if(bzSens(e)&&v)v='(filled, hidden)';
        var n={id:id,kind:k,label:bzLab(e),value:String(v).replace(/\s+/g,' ').trim().slice(0,60)};
        if(e.disabled||e.getAttribute('aria-disabled')=='true')n.disabled=true;
        if(/checkbox|radio/.test(k)||/checkbox|radio|switch/.test(e.getAttribute('role')||''))n.checked=!!(e.checked||e.getAttribute('aria-checked')=='true');
        if(t=='a'&&e.href&&e.href.indexOf('javascript')!=0)n.href=e.href.slice(0,100);
        if(t=='select')n.opts=bzOpts(e);
        if(!n.label&&k=='link')return null;
        return n;}
      function bzFind(q,field){q=(q||'').toLowerCase().trim();if(!q)return null;
        var sel=field?'input:not([type=hidden]),textarea,select,[contenteditable=true],[role=textbox],[role=combobox]':BZSEL+',label';
        var best=null,bs=1e9;
        [].slice.call(document.querySelectorAll(sel)).filter(bzVis).forEach(function(e){
          var l=(bzLab(e)+' '+(e.value&&!bzSens(e)&&!field?e.value:'')+' '+bzKey(e)).toLowerCase();var i=l.indexOf(q);if(i<0)return;
          var s=l.length-q.length+i;if(s<bs){bs=s;best=e;}});
        if(best&&best.tagName=='LABEL'&&best.control)best=best.control;
        return best;}
      function bzEl(n,q,field){return (n!=null&&n>=0)?bzGet(n):bzFind(q,field);}
    """

    /**
     * The page as the model needs it, viewport first: interactive elements (on screen, then below, then above), the text
     * on screen and below it (of `main` / `article` when the page has one), headings, an open dialog and the scroll position.
     */
    const val READ = """/*bz:read*/(function(){$COMMON
      var m=bzMap();
      [].forEach.call(document.querySelectorAll('[data-bz]'),function(e){var id=m.get(e);if(id==null||String(id)!==e.getAttribute('data-bz'))e.removeAttribute('data-bz');});
      var H=innerHeight;
      var els=[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis).map(function(e){var r=e.getBoundingClientRect();
        var w=r.bottom<=0?-1:(r.top>=H?1:0);return {e:e,w:w,d:w==0?r.top:(w>0?r.top-H:-r.bottom)};});
      function rank(x){return x.w==0?0:(x.w>0?1:2);}
      els.sort(function(a,b){var ka=rank(a),kb=rank(b);return ka!=kb?ka-kb:a.d-b.d;});
      var items=[],n=0;
      for(var i=0;i<els.length&&items.length<90;i++){var x=bzNode(els[i].e);if(!x)continue;x.w=els[i].w;items.push(x);}
      var root=document.querySelector('main,[role=main],article');
      if(!root||(root.innerText||'').trim().length<200)root=document.body;
      var on=[],below=[],onLen=0,belowLen=0,lastP=null,lastVis=true;
      if(root){var tw=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null),nd;
        while((nd=tw.nextNode())){var tx=nd.nodeValue.replace(/\s+/g,' ').trim();if(!tx)continue;var p=nd.parentElement;if(!p)continue;
          if(/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE)$/.test(p.tagName))continue;
          var vis=(p===lastP)?lastVis:bzVis(p);var r=p.getBoundingClientRect();
          var sep=(p===lastP)?' ':'\n';lastP=p;lastVis=vis;if(!vis)continue;
          if(r.bottom<=0)continue;
          if(r.top<H){if(onLen<6000){on.push(sep+tx);onLen+=tx.length+1;}}
          else if(belowLen<3000){below.push(sep+tx);belowLen+=tx.length+1;}
          if(onLen>=6000&&belowLen>=3000)break;}}
      var heads=[].slice.call(document.querySelectorAll('h1,h2,h3,[role=heading]')).filter(bzVis).slice(0,10).map(function(h){return (h.innerText||'').replace(/\s+/g,' ').trim().slice(0,80);}).filter(function(x){return x;});
      var dlg=[].slice.call(document.querySelectorAll('dialog[open],[role=dialog],[role=alertdialog],[aria-modal=true]')).filter(bzVis)[0];
      var de=document.documentElement;
      return {title:document.title,url:location.href,on:on.join('').trim(),below:below.join('').trim(),items:items,heads:heads,
        dialog:dlg?(dlg.innerText||'').replace(/\s+/g,' ').trim().slice(0,160):'',y:Math.round(scrollY),h:Math.round(Math.max(de.scrollHeight,document.body?document.body.scrollHeight:0)),vh:Math.round(H),
        pw:[].slice.call(document.querySelectorAll('input[type=password]')).some(bzVis)};
    })()"""

    /** Finds [query] in the page's text and element labels: snippets with the nearest element, the first scrolled into view. */
    fun find(query: String): String = """/*bz:find*/(function(q){$COMMON
      q=(q||'').toLowerCase().trim();if(!q)return {hits:[]};
      var hits=[],first=null,H=innerHeight;
      function near(p){var c=p.closest(BZSEL);if(c&&bzVis(c))return c;
        var b=p.closest('li,tr,article,section,form,[role=listitem],div');var x=b?[].slice.call(b.querySelectorAll(BZSEL)).filter(bzVis)[0]:null;return x||null;}
      var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),nd;
      while((nd=tw.nextNode())&&hits.length<8){var raw=nd.nodeValue;var low=raw.toLowerCase();var i=low.indexOf(q);if(i<0)continue;
        var p=nd.parentElement;if(!p||/^(SCRIPT|STYLE|NOSCRIPT)$/.test(p.tagName)||!bzVis(p))continue;
        var s=raw.replace(/\s+/g,' ');var j=s.toLowerCase().indexOf(q);var snip=(j>60?'\u2026':'')+s.slice(Math.max(0,j-60),j+q.length+80).trim();
        var e=near(p),h={text:snip};if(e){h.id=bzId(e);h.kind=bzKind(e);h.label=bzLab(e);}
        var r=p.getBoundingClientRect();h.where=r.bottom<=0?'above':(r.top>=H?'below':'on screen');hits.push(h);if(!first)first=p;}
      if(hits.length<8)[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis).forEach(function(e){if(hits.length>=8)return;
        var l=bzLab(e);if(l.toLowerCase().indexOf(q)<0)return;var id=bzId(e);if(hits.some(function(h){return h.id===id;}))return;
        var r=e.getBoundingClientRect();hits.push({text:l,id:id,kind:bzKind(e),label:l,where:r.bottom<=0?'above':(r.top>=H?'below':'on screen')});if(!first)first=e;});
      if(first){try{first.scrollIntoView({block:'center',behavior:'instant'});}catch(x){first.scrollIntoView();}}
      return {hits:hits,y:Math.round(scrollY)};
    })(${JSONObject.quote(query)})"""

    /** What a click would press (its words), without pressing it; "" when not found. */
    fun label(n: Int?, text: String?): String = """/*bz:label*/(function(n,q){$COMMON var e=bzEl(n,q,false);return e?bzLab(e):'';})(${n ?: -1}, ${JSONObject.quote(text.orEmpty())})"""

    /**
     * A real click on element [n] (or the best match for [text]): scrolled to the centre, then pointer / mouse down and up
     * at its centre and click(). Refuses (and says why) when it is gone, hidden, disabled or covered by something else.
     */
    fun click(n: Int?, text: String?): String = """/*bz:click*/(function(n,q){$COMMON
      var e=bzEl(n,q,false);if(!e)return {r:'missing'};
      if(!bzVis(e))return {r:'hidden',l:bzLab(e)};
      if(e.disabled||e.getAttribute('aria-disabled')=='true')return {r:'disabled',l:bzLab(e)};
      if(e.tagName=='SELECT')return {r:'select',l:bzLab(e),opts:bzOpts(e)};
      try{e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}catch(x){e.scrollIntoView();}
      var r=e.getBoundingClientRect(),cx=r.left+r.width/2,cy=r.top+r.height/2,h=document.elementFromPoint(cx,cy);
      if(h&&!(e===h||e.contains(h)||h.contains(e))&&!(h.tagName=='LABEL'&&h.control===e)){
        var c=h.closest(BZSEL)||h;return {r:'covered',l:bzLab(e),by:(bzLab(c)||(h.innerText||'').replace(/\s+/g,' ').trim()).slice(0,60),byId:bzVis(c)?bzId(c):-1};}
      var o={bubbles:true,cancelable:true,composed:true,view:window,clientX:cx,clientY:cy,button:0,buttons:1,pointerId:1,pointerType:'touch',isPrimary:true};
      try{e.dispatchEvent(new PointerEvent('pointerdown',o));}catch(x){}
      try{e.dispatchEvent(new MouseEvent('mousedown',o));}catch(x){}
      if(e.focus)try{e.focus({preventScroll:true});}catch(x){}
      o.buttons=0;
      try{e.dispatchEvent(new PointerEvent('pointerup',o));}catch(x){}
      try{e.dispatchEvent(new MouseEvent('mouseup',o));}catch(x){}
      e.click();
      return {r:'ok',l:bzLab(e)||e.tagName.toLowerCase(),kind:bzKind(e),href:(e.href||'').slice(0,120)};
    })(${n ?: -1}, ${JSONObject.quote(text.orEmpty())})"""

    /**
     * Types [value] into field [n] (or the field labelled [label], or the focused / first text field): the native value
     * setter plus input / change, then reads the value back. [submit] presses Enter and submits the field's form.
     */
    fun type(n: Int?, label: String?, value: String, submit: Boolean, secret: Boolean): String = """/*bz:type*/(function(n,q,v,submit,secret){$COMMON
      var e=(n!=null&&n>=0)?bzGet(n):(q?bzFind(q,true):null);
      if(!e&&n<0&&!q){e=document.activeElement;if(!e||!bzEditable(e))e=[].slice.call(document.querySelectorAll('input[type=search],input[type=text],input[type=email],input:not([type]),textarea,[contenteditable=true]')).filter(bzVis)[0]||null;}
      if(!e)return {r:'missing'};
      if(e.tagName=='SELECT')return {r:'select',l:bzLab(e),opts:bzOpts(e)};
      if(!bzEditable(e))return {r:'noteditable',l:bzLab(e),kind:bzKind(e)};
      if(e.disabled||e.readOnly)return {r:'readonly',l:bzLab(e)};
      try{e.scrollIntoView({block:'center',behavior:'instant'});}catch(x){e.scrollIntoView();}
      e.focus();
      if(e.isContentEditable){e.innerText=v;}
      else{var proto=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,v);}
      if(secret)e.setAttribute('data-bz-secret','1');
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      var got=e.isContentEditable?(e.innerText||''):(e.value||'');
      if(got.replace(/\s+/g,' ').trim()!==String(v).replace(/\s+/g,' ').trim())return {r:'mismatch',l:bzLab(e),got:bzSens(e)?'(hidden)':got.slice(0,60),type:(e.type||'')};
      var sub=false;
      if(submit){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
        var go=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));
        if(go&&e.form&&e.tagName!='TEXTAREA'){try{if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();sub=true;}catch(x){}}}
      return {r:'ok',l:bzLab(e),len:got.length,submitted:sub};
    })(${n ?: -1}, ${JSONObject.quote(label.orEmpty())}, ${JSONObject.quote(value)}, $submit, $secret)"""

    /** Picks [option] (its text or value) in the `<select>` [n] / labelled [label]. "notselect" for a custom dropdown. */
    fun select(n: Int?, label: String?, option: String): String = """/*bz:select*/(function(n,q,want){$COMMON
      var e=bzEl(n,q,true);if(!e)return {r:'missing'};
      if(e.tagName!='SELECT')return {r:'notselect',l:bzLab(e),kind:bzKind(e)};
      if(e.disabled)return {r:'disabled',l:bzLab(e)};
      var w=want.toLowerCase().trim(),os=[].slice.call(e.options),hit=null;
      os.forEach(function(o){if(!hit&&(o.text.replace(/\s+/g,' ').trim().toLowerCase()==w||o.value.toLowerCase()==w))hit=o;});
      if(!hit)os.forEach(function(o){if(!hit&&o.text.toLowerCase().indexOf(w)>=0)hit=o;});
      if(!hit)return {r:'nooption',l:bzLab(e),opts:bzOpts(e)};
      e.focus();Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(e,hit.value);
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      return e.value===hit.value?{r:'ok',l:bzLab(e),v:hit.text.trim()}:{r:'mismatch',l:bzLab(e)};
    })(${n ?: -1}, ${JSONObject.quote(label.orEmpty())}, ${JSONObject.quote(option)})"""

    /** After a custom dropdown opened: clicks the visible option ([role=option] / menu item / list item) matching [option]. */
    fun pickOption(option: String): String = """/*bz:pick*/(function(want){$COMMON
      var w=want.toLowerCase().trim(),best=null,bs=1e9;
      [].slice.call(document.querySelectorAll('[role=option],[role=menuitem],[role=menuitemradio],[role=treeitem],li,[data-value]')).filter(bzVis).forEach(function(e){
        var l=(e.innerText||e.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().toLowerCase();if(!l)return;var i=l.indexOf(w);if(i<0)return;
        var s=l.length-w.length+i;if(s<bs){bs=s;best=e;}});
      if(!best)return {r:'nooption'};
      try{best.scrollIntoView({block:'center',behavior:'instant'});}catch(x){}
      var r=best.getBoundingClientRect(),o={bubbles:true,cancelable:true,view:window,clientX:r.left+r.width/2,clientY:r.top+r.height/2,button:0,pointerType:'touch',isPrimary:true};
      try{best.dispatchEvent(new PointerEvent('pointerdown',o));}catch(x){}try{best.dispatchEvent(new MouseEvent('mousedown',o));}catch(x){}
      try{best.dispatchEvent(new PointerEvent('pointerup',o));}catch(x){}try{best.dispatchEvent(new MouseEvent('mouseup',o));}catch(x){}
      best.click();return {r:'ok',v:(best.innerText||'').replace(/\s+/g,' ').trim().slice(0,60)};
    })(${JSONObject.quote(option)})"""

    /** Keys [BrowserRead.KEYS] the model may press. */
    fun key(name: String): String = """/*bz:key*/(function(k){$COMMON
      var codes={Enter:13,Escape:27,Tab:9,ArrowDown:40,ArrowUp:38,ArrowLeft:37,ArrowRight:39,Backspace:8,Space:32,PageDown:34,PageUp:33,Home:36,End:35};
      if(codes[k]==null)return {r:'badkey'};
      var e=document.activeElement||document.body;if(e===document.documentElement)e=document.body;
      var o={key:k=='Space'?' ':k,code:k,keyCode:codes[k],which:codes[k],bubbles:true,cancelable:true};
      var go=e.dispatchEvent(new KeyboardEvent('keydown',o));
      if(k=='Enter'||k=='Space')e.dispatchEvent(new KeyboardEvent('keypress',o));
      e.dispatchEvent(new KeyboardEvent('keyup',o));
      var did='',ed=bzEditable(e);
      if(go){
        if(k=='Enter'){if(e.form&&e.tagName=='INPUT'){try{if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();did='submitted the form';}catch(x){}}
          else if(/^(A|BUTTON|SUMMARY)$/.test(e.tagName)||e.getAttribute('role')=='button'){e.click();did='pressed '+bzLab(e);}}
        else if(k=='Tab'){var l=[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis);var i=l.indexOf(e);var nx=l[i+1]||l[0];if(nx){nx.focus();did='focus on ['+bzId(nx)+'] '+bzLab(nx);}}
        else if(k=='Escape'){var d=document.querySelector('dialog[open]');if(d&&d.close){d.close();did='closed a dialog';}}
        else if(k=='Backspace'&&ed&&!e.isContentEditable){var proto=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
          Object.getOwnPropertyDescriptor(proto,'value').set.call(e,(e.value||'').slice(0,-1));e.dispatchEvent(new Event('input',{bubbles:true}));did='deleted a character';}
        else if(!ed&&(k=='PageDown'||k=='Space')){scrollBy(0,innerHeight*0.85);did='scrolled down';}
        else if(!ed&&k=='PageUp'){scrollBy(0,-innerHeight*0.85);did='scrolled up';}
        else if(!ed&&k=='Home'){scrollTo(0,0);did='scrolled to the top';}
        else if(!ed&&k=='End'){scrollTo(0,document.documentElement.scrollHeight);did='scrolled to the bottom';}}
      else did='the page handled it';
      return {r:'ok',did:did,on:bzLab(e)};
    })(${JSONObject.quote(name)})"""

    /** Is the condition met? [selector] visible, [text] on the page ([gone] = the opposite), or just loaded. */
    fun waitCheck(selector: String?, text: String?, gone: Boolean): String = """/*bz:wait*/(function(sel,txt,gone){$COMMON
      var ok=document.readyState=='complete';
      if(sel){var e=null;try{e=document.querySelector(sel);}catch(x){return 'badselector';}ok=!!e&&bzVis(e);}
      if(txt){ok=(document.body?document.body.innerText:'').toLowerCase().indexOf(txt.toLowerCase())>=0;}
      if(gone)ok=!ok;return ok?'yes':'no';
    })(${JSONObject.quote(selector.orEmpty())}, ${JSONObject.quote(text.orEmpty())}, $gone)"""

    /**
     * How long the page has been quiet (ms since its DOM last changed), installing the watcher on first use (a new page
     * gets a new one). Used to wait for a page to settle after an action.
     */
    const val QUIET = """/*bz:quiet*/(function(){if(!window.__bzObs){window.__bzLast=Date.now();try{window.__bzObs=new MutationObserver(function(){window.__bzLast=Date.now();});
      window.__bzObs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,characterData:true});}catch(x){}}
      return String((Date.now()-(window.__bzLast||0))+'|'+document.readyState);})()"""

    /** Scrolls by most of a screen; returns "y|max" after. */
    fun scroll(down: Boolean): String = "/*bz:scroll*/(function(){window.scrollBy(0,${if (down) "" else "-"}window.innerHeight*0.85);var de=document.documentElement;" +
        "return Math.round(scrollY)+'|'+Math.max(0,Math.round(de.scrollHeight-innerHeight));})()"

    /** For a secure-form fill: element [n], the field labelled [label], or the first visible field matching [css]. */
    fun fillSecret(n: Int?, label: String?, css: String, value: String): String = """/*bz:secret*/(function(n,q,css,v){$COMMON
      var e=(n!=null&&n>=0)?bzGet(n):(q?bzFind(q,true):null);
      if(!e)e=[].slice.call(document.querySelectorAll(css)).filter(function(x){return bzVis(x)&&!x.disabled&&!x.readOnly&&!x.hasAttribute('data-bz-secret');})[0]||null;
      if(!e||!bzEditable(e))return 'missing';
      try{e.scrollIntoView({block:'center',behavior:'instant'});}catch(x){}e.focus();
      var proto=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto,'value').set.call(e,v);
      e.setAttribute('data-bz-secret','1');
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
      return (e.value||'')===v?'ok':'mismatch';
    })(${n ?: -1}, ${JSONObject.quote(label.orEmpty())}, ${JSONObject.quote(css)}, ${JSONObject.quote(value)})"""
}
