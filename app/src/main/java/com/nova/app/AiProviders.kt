package com.nova.app

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

interface AiProvider {
    fun name(): String
    fun chatUrl(): String
    fun isLoggedIn(webView: WebView): Boolean
    fun openLogin(webView: WebView)
    fun sendPrompt(webView: WebView, prompt: String)
}

internal object WebEvalHelper {
    private val main = Handler(Looper.getMainLooper())

    fun evalSync(webView: WebView, js: String, timeoutMs: Long = 3500L): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return ""
        var out = ""
        val latch = CountDownLatch(1)
        main.post {
            try {
                webView.evaluateJavascript(js) { res ->
                    out = if (res == null || res == "null") "" else try {
                        val v = JSONTokener(res).nextValue()
                        if (v is String) v else v.toString()
                    } catch (_: Exception) { res }
                    latch.countDown()
                }
            } catch (_: Exception) {
                latch.countDown()
            }
        }
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
        return out
    }

    fun postJs(webView: WebView, js: String) {
        main.post { try { webView.evaluateJavascript(js, null) } catch (_: Exception) {} }
    }

    fun loadUrl(webView: WebView, url: String) {
        main.post { try { webView.loadUrl(url) } catch (_: Exception) {} }
    }

    fun domHasComposer(webView: WebView): Boolean {
        val js = """
            (function(){
              var u=location.href.toLowerCase();
              if(u.indexOf('login')>=0||u.indexOf('signin')>=0||u.indexOf('auth')>=0)return 'login';
              var ta=document.querySelector('textarea,[contenteditable="true"],div[role="textbox"]');
              return ta?'ready':'none';
            })();
        """.trimIndent()
        return evalSync(webView, js) == "ready"
    }

    fun injectAndSend(webView: WebView, prompt: String, submitSelectors: String) {
        val q = JSONObject.quote(prompt)
        val js = """
            (function(msg){
              var el=document.querySelector('textarea')||document.querySelector('[contenteditable="true"]')||document.querySelector('div[role="textbox"]');
              if(!el)return;
              el.focus();
              if(el.tagName==='TEXTAREA'){
                var d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');
                if(d&&d.set)d.set.call(el,msg); else el.value=msg;
                el.dispatchEvent(new Event('input',{bubbles:true}));
                el.dispatchEvent(new Event('change',{bubbles:true}));
              }else{
                el.innerText=msg;
                el.dispatchEvent(new InputEvent('input',{bubbles:true,data:msg}));
              }
              setTimeout(function(){
                var btn=document.querySelector($submitSelectors);
                if(btn&&!btn.disabled){btn.click();return;}
                var ev=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true});
                el.dispatchEvent(ev);
              },350);
            })($q);
        """.trimIndent()
        postJs(webView, js)
    }

    fun extractReplyText(webView: WebView): String {
        val js = """
            (function(){
              var blocks=document.querySelectorAll('pre,code,.markdown,.prose,[data-message-author-role="assistant"]');
              if(!blocks||blocks.length===0) return (document.body?document.body.innerText:'').slice(-6000);
              var out=[];
              blocks.forEach(function(b){
                if(b.tagName==='PRE'){ out.push('```\n'+b.innerText+'\n```'); }
                else if(!b.querySelector('pre')){ out.push(b.innerText); }
              });
              return out.slice(-4).join('\n\n');
            })();
        """.trimIndent()
        return evalSync(webView, js, 4000L)
    }
}

class DeepSeekProvider : AiProvider {
    override fun name() = "DeepSeek"
    override fun chatUrl() = "https://chat.deepseek.com/"
    override fun isLoggedIn(webView: WebView): Boolean {
        val ck = try { CookieManager.getInstance().getCookie(chatUrl()) ?: "" } catch (_: Throwable) { "" }
        if (WebEvalHelper.domHasComposer(webView)) return true
        return ck.contains("ds_session", true) || ck.contains("token", true) || ck.contains("HWSESSION", true)
    }
    override fun openLogin(webView: WebView) = WebEvalHelper.loadUrl(webView, "https://chat.deepseek.com/sign_in")
    override fun sendPrompt(webView: WebView, prompt: String) =
        WebEvalHelper.injectAndSend(webView, prompt, "'div[role=\"button\"][aria-disabled=\"false\"],button[type=\"submit\"],button[aria-label*=\"Send\"]'")
}

class ChatGPTProvider : AiProvider {
    override fun name() = "ChatGPT"
    override fun chatUrl() = "https://chatgpt.com/"
    override fun isLoggedIn(webView: WebView): Boolean {
        val ck = try { CookieManager.getInstance().getCookie(chatUrl()) ?: "" } catch (_: Throwable) { "" }
        if (WebEvalHelper.domHasComposer(webView)) return true
        return ck.contains("__Secure-next-auth.session-token", true) || ck.contains("oai-did", true)
    }
    override fun openLogin(webView: WebView) = WebEvalHelper.loadUrl(webView, "https://chatgpt.com/auth/login")
    override fun sendPrompt(webView: WebView, prompt: String) =
        WebEvalHelper.injectAndSend(webView, prompt, "'button[data-testid=\"send-button\"],button[aria-label*=\"Send\"]'")
}

class ClaudeProvider : AiProvider {
    override fun name() = "Claude"
    override fun chatUrl() = "https://claude.ai/new"
    override fun isLoggedIn(webView: WebView): Boolean {
        val ck = try { CookieManager.getInstance().getCookie("https://claude.ai/") ?: "" } catch (_: Throwable) { "" }
        if (WebEvalHelper.domHasComposer(webView)) return true
        return ck.contains("sessionKey", true)
    }
    override fun openLogin(webView: WebView) = WebEvalHelper.loadUrl(webView, "https://claude.ai/login")
    override fun sendPrompt(webView: WebView, prompt: String) =
        WebEvalHelper.injectAndSend(webView, prompt, "'button[aria-label*=\"Send\"],button[type=\"submit\"]'")
}

class GeminiProvider : AiProvider {
    override fun name() = "Gemini"
    override fun chatUrl() = "https://gemini.google.com/app"
    override fun isLoggedIn(webView: WebView): Boolean {
        val ck = try { CookieManager.getInstance().getCookie("https://gemini.google.com/") ?: "" } catch (_: Throwable) { "" }
        if (WebEvalHelper.domHasComposer(webView)) return true
        return ck.contains("SID=", true) || ck.contains("__Secure-1PSID", true)
    }
    override fun openLogin(webView: WebView) = WebEvalHelper.loadUrl(webView, "https://accounts.google.com/")
    override fun sendPrompt(webView: WebView, prompt: String) =
        WebEvalHelper.injectAndSend(webView, prompt, "'button[aria-label*=\"Send\"],button.send-button'")
}

class GrokProvider : AiProvider {
    override fun name() = "Grok"
    override fun chatUrl() = "https://grok.com/"
    override fun isLoggedIn(webView: WebView): Boolean {
        val ck = try { CookieManager.getInstance().getCookie("https://grok.com/") ?: "" } catch (_: Throwable) { "" }
        if (WebEvalHelper.domHasComposer(webView)) return true
        return ck.contains("sso", true) || ck.contains("x-userid", true)
    }
    override fun openLogin(webView: WebView) = WebEvalHelper.loadUrl(webView, "https://grok.com/")
    override fun sendPrompt(webView: WebView, prompt: String) =
        WebEvalHelper.injectAndSend(webView, prompt, "'button[aria-label*=\"Submit\"],button[type=\"submit\"]'")
}
