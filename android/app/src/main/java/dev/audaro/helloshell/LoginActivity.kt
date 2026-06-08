package dev.audaro.helloshell

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * A3 on-device Jagex login — the Android twin of the iOS JagexLoginViewController. A WebView runs the Stage-A
 * OAuth consent flow (cookies cleared first so each login starts fresh and account-switching works); the
 * redirect to the loopback `http://localhost/#id_token=…` is intercepted, the id_token minted into a game
 * session + character list ([JagexLogin]), a character picked, and tokens.json written. The process is then
 * restarted so the next boot snapshots JX_* at JNI_CreateJavaVM (the Review-2 login-first-boot rule).
 *
 * Only redacted lines are logged — never a raw token. Started with no tokens (login-first gate) or from the
 * in-app "Sign in" affordance (account switch).
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private val nonce = JagexLogin.randomToken()
    private val state = JagexLogin.randomToken()
    private var captured = false   // handle the loopback redirect exactly once

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(26, 26, 26))
            setPadding(28, 16, 28, 16)
        }
        bar.addView(TextView(this).apply {
            text = "Log in with Jagex"; setTextColor(Color.WHITE); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(Button(this).apply { text = "Close"; setOnClickListener { cancelAndExit() } })
        root.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setTextColor(Color.GREEN); setBackgroundColor(Color.rgb(13, 13, 13)); textSize = 11f
            setPadding(20, 14, 20, 14); text = "Loading Jagex login…"
        }
        root.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        // Ephemeral session: clear cookies so the IdP doesn't auto-authenticate the last account from a warm
        // SSO cookie (staying logged in across launches is carried by tokens.json, not web cookies).
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = JagexLogin.LAUNCHER_UA
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                // The consent flow redirects to http://localhost/#id_token=…&code=…&state=… — a loopback that
                // won't load. Catch it BEFORE the doomed navigation and pull the id_token from the fragment.
                if (url.host == "localhost") {
                    if (!captured) {
                        captured = true
                        handleRedirect(url.encodedFragment ?: url.fragment ?: "")
                    }
                    return true   // cancel the localhost load
                }
                return false
            }
        }
        web.loadUrl(JagexLogin.consentUrl(nonce, state))
    }

    private fun handleRedirect(fragment: String) {
        val idToken = JagexLogin.valueFrom(fragment, "id_token")
        if (idToken.isNullOrEmpty()) {
            log("✗ no id_token in redirect (frag len=${fragment.length})")
            captured = false   // allow a retry if the user re-attempts
            return
        }
        log("✓ authenticated — minting game session…")
        web.visibility = View.GONE
        thread(name = "jagex-login") {
            val sessionId = JagexLogin.mintSession(idToken)
            if (sessionId == null) { runOnUiThread { log("✗ session mint failed (see logcat)") }; return@thread }
            val accounts = JagexLogin.fetchAccounts(sessionId)
            if (accounts == null || accounts.length() == 0) {
                runOnUiThread { log("✗ no characters returned (see logcat)") }; return@thread
            }
            runOnUiThread { log("✓ ${accounts.length()} character(s) — choose one"); presentPicker(sessionId, accounts) }
        }
    }

    private fun presentPicker(sessionId: String, accounts: JSONArray) {
        if (accounts.length() == 1) { finishWith(sessionId, accounts.getJSONObject(0), accounts); return }
        val labels = (0 until accounts.length()).map { JagexLogin.accountLabel(accounts.getJSONObject(it)) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose character")
            .setItems(labels) { _, which -> finishWith(sessionId, accounts.getJSONObject(which), accounts) }
            .setNegativeButton("Cancel") { _, _ -> cancelAndExit() }
            .setCancelable(false)
            .show()
    }

    private fun finishWith(sessionId: String, chosen: JSONObject, accounts: JSONArray) {
        val name = chosen.optString("displayName", "")
        log("✓ selected ${name.ifEmpty { "(unnamed)" }} — writing tokens + restarting")
        teardownWeb()
        if (!JagexLogin.writeTokens(this, sessionId, chosen, accounts)) { log("✗ tokens.json write failed"); return }
        restartIntoCleanBoot()
    }

    /** Relaunch the app in a fresh task + kill this process, so JNI_CreateJavaVM re-snapshots JX_* from the
     *  newly-written tokens.json (Review-2: login-first or a clean process restart). */
    private fun restartIntoCleanBoot() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun cancelAndExit() {
        teardownWeb()
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun teardownWeb() {
        if (!::web.isInitialized) return
        web.stopLoading()
        web.webViewClient = WebViewClient()
        (web.parent as? LinearLayout)?.removeView(web)
        web.destroy()
    }

    private fun log(line: String) {
        Log.i("JagexLogin", "[login] $line")
        runOnUiThread { status.text = "${status.text}\n$line" }
    }

    override fun onDestroy() {
        teardownWeb()
        super.onDestroy()
    }
}
