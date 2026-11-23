package com.nova.app

import org.json.JSONObject

/**
 * HTML & injected JavaScript helpers for NOVA Browser:
 *  - Custom nova://home quick-dial homepage
 *  - Video/HLS sniffer (.mp4, .webm, .m3u8)
 *  - Credential capture & autofill helper
 *  - Night mode CSS fallback
 */
object BrowserScripts {
    const val HOME_URL = "nova://home"

    fun homeHtml(bookmarks: List<Pair<String, String>>): String {
        val bmHtml = StringBuilder()
        for ((title, url) in bookmarks.take(6)) {
            val safeT = title.ifBlank { url }.replace("<", "&lt;").take(18)
            val safeU = url.replace("'", "%27").replace("\"", "&quot;")
            bmHtml.append("<a class='dial' href='$safeU'><div class='dot'>★</div><span>$safeT</span></a>")
        }
        return """
<!DOCTYPE html>
<html>
<head>
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<title>NOVA Home</title>
<style>
  *{box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif}
  body{margin:0;padding:24px 16px;background:radial-gradient(circle at 50% 10%,#17284a 0%,#0b1220 75%);color:#eef4ff;min-height:100vh}
  .logo{text-align:center;font-size:32px;font-weight:800;letter-spacing:8px;color:#00e5ff;margin:18px 0 6px}
  .sub{text-align:center;font-size:12px;color:#8fa3bf;margin-bottom:20px}
  .search{display:flex;background:#141e33;border:1px solid #00e5ff66;border-radius:26px;padding:6px 14px;margin:0 auto 22px;max-width:520px}
  .search input{flex:1;background:transparent;border:none;outline:none;color:#fff;font-size:15px;padding:8px 4px}
  .search button{background:#00e5ff;color:#05101a;border:none;border-radius:20px;padding:8px 16px;font-weight:700;font-size:13px}
  .sec{font-size:12px;color:#8fa3bf;letter-spacing:1.5px;text-transform:uppercase;margin:14px 4px 10px}
  .grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;max-width:540px;margin:0 auto}
  .dial{display:flex;flex-direction:column;align-items:center;text-decoration:none;color:#dce7f7;background:#141e33cc;border:1px solid #263759;border-radius:16px;padding:12px 6px}
  .dot{width:38px;height:38px;border-radius:19px;background:#1e2e4f;color:#00e5ff;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:16px;margin-bottom:6px}
  .dial span{font-size:11.5px;text-align:center;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:100%}
</style>
</head>
<body>
  <div class="logo">NOVA</div>
  <div class="sub">Persistent Sessions • AdBlock • Video Sniffer</div>
  <form class="search" onsubmit="var q=document.getElementById('q').value.trim();if(q){location.href=/^https?:\/\//i.test(q)?q:'https://www.google.com/search?q='+encodeURIComponent(q);}return false;">
    <input id="q" type="text" placeholder="Search Google or enter website..." autocomplete="off">
    <button type="submit">Go</button>
  </form>
  <div class="sec">Quick Dials</div>
  <div class="grid">
    <a class="dial" href="https://www.google.com"><div class="dot">G</div><span>Google</span></a>
    <a class="dial" href="https://chat.deepseek.com/"><div class="dot">D</div><span>DeepSeek</span></a>
    <a class="dial" href="https://chatgpt.com/"><div class="dot">C</div><span>ChatGPT</span></a>
    <a class="dial" href="https://claude.ai/"><div class="dot">A</div><span>Claude</span></a>
    <a class="dial" href="https://gemini.google.com/"><div class="dot">✦</div><span>Gemini</span></a>
    <a class="dial" href="https://github.com/"><div class="dot">⌥</div><span>GitHub</span></a>
    <a class="dial" href="https://m.youtube.com/"><div class="dot">▶</div><span>YouTube</span></a>
    <a class="dial" href="https://en.m.wikipedia.org/"><div class="dot">W</div><span>Wikipedia</span></a>
  </div>
  ${if (bookmarks.isNotEmpty()) "<div class='sec'>Bookmarks</div><div class='grid'>$bmHtml</div>" else ""}
</body>
</html>
""".trimIndent()
    }

    const val JS_SNIFF = """
(function(){
  if(window.__nvSniff)return; window.__nvSniff=1;
  function rep(u){
    if(!u||typeof u!=='string')return;
    if(u.indexOf('http')===0&&(/\.(mp4|webm|m3u8)(\?|$)/i.test(u)||u.indexOf('mime=video')>=0)){
      try{NovaDl.onMedia(u);}catch(e){}
    }
  }
  function scan(){
    document.querySelectorAll('video,video source,source').forEach(function(el){
      rep(el.currentSrc||el.src||el.getAttribute('src'));
    });
  }
  scan();
  setInterval(scan,2500);
})();"""

    const val JS_PASS_HOOK = """
(function(){
  if(window.__nvPass)return; window.__nvPass=1;
  document.addEventListener('submit',function(e){
    try{
      var f=e.target; if(!f||!f.querySelector)return;
      var pw=f.querySelector('input[type="password"]');
      if(!pw||!pw.value)return;
      var us=f.querySelector('input[type="email"],input[type="text"],input[name*="user"],input[name*="mail"]');
      NovaDl.onCreds(location.hostname,us?us.value:'',pw.value);
    }catch(err){}
  },true);
})();"""

    const val JS_NIGHT_ON = """
(function(){
  var s=document.getElementById('__nvNight');
  if(!s){s=document.createElement('style');s.id='__nvNight';s.textContent='html{filter:invert(0.9) hue-rotate(180deg);background:#111!important;}img,video,picture,canvas{filter:invert(1) hue-rotate(180deg);}';(document.head||document.documentElement).appendChild(s);}
})();"""

    const val JS_NIGHT_OFF = """
(function(){var s=document.getElementById('__nvNight');if(s)s.remove();})();"""

    fun jsFillCreds(user: String, pass: String): String = """
(function(u,p){
  var pw=document.querySelector('input[type="password"]');
  if(!pw)return 'no-password-field';
  var us=document.querySelector('input[type="email"],input[type="text"],input[name*="user"],input[name*="mail"]');
  function setVal(el,v){
    if(!el)return;
    var proto=HTMLInputElement.prototype;
    var d=Object.getOwnPropertyDescriptor(proto,'value');
    if(d&&d.set)d.set.call(el,v); else el.value=v;
    el.dispatchEvent(new Event('input',{bubbles:true}));
    el.dispatchEvent(new Event('change',{bubbles:true}));
  }
  if(us&&u)setVal(us,u);
  setVal(pw,p);
  return 'filled';
})(${JSONObject.quote(user)},${JSONObject.quote(pass)});"""
}
