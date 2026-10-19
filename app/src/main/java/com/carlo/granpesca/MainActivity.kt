package com.carlo.granpesca

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import java.io.File
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var wantAny = false
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val cb = fileCallback
        fileCallback = null
        cb?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schermo intero: nasconde barra di stato e di navigazione
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF06324F.toInt())
            overScrollMode = WebView.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            isLongClickable = false
            setOnLongClickListener { true }
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF06324F.toInt()); addView(webView) }
        setContentView(root)
        // bordo a bordo (Android 15/16): evita notch e angoli arrotondati
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val c = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(c.left, c.top, c.right, c.bottom)
            insets
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
        }
        // dopo un aggiornamento si svuota la cache del WebView (non i dati di gioco)
        try {
            val prefs = getSharedPreferences("gp_boot", MODE_PRIVATE)
            val pi = packageManager.getPackageInfo(packageName, 0)
            val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            if (prefs.getLong("vc", -1L) != vc) { webView.clearCache(true); prefs.edit().putLong("vc", vc).apply() }
        } catch (e: Exception) { }
        // se l'app si è chiusa per un errore, al riavvio il gioco mostra la schermata di recupero
        val crashFlag = File(filesDir, "gp_crash.flag")
        val rescue = crashFlag.exists()
        try { crashFlag.delete() } catch (e: Exception) { }
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "gp_crash.flag").writeText(System.currentTimeMillis().toString()) } catch (x: Exception) { }
            prevHandler?.uncaughtException(t, e)
        }
        // i link esterni (pagina della privacy, email) si aprono nel browser o nell'app di posta, non dentro il gioco
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme == "mailto") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { }
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                try {
                    // immagini dalla galleria (nessun permesso fotocamera); qualsiasi file se serve un .gp
                    val any = params?.acceptTypes?.any { it.contains("gp") || it.contains("octet") || it.contains("text") } == true
                    pickImage.launch(if (any) "*/*" else "image/*")
                } catch (e: Exception) {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = null
                }
                return true
            }
        }

        // condivisione della foto ricordo con il menu di sistema: il file resta nella cache dell'app, nessun permesso richiesto
        webView.addJavascriptInterface(ShareBridge(), "GPAndroid")
        webView.loadUrl("file:///android_asset/index.html" + (if (rescue) "#rescue" else ""))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(window.handleBack ? window.handleBack() : false)"
                ) { result ->
                    if (result != "true") finish()
                }
            }
        })
    }

    inner class ShareBridge {
        // copia interna della partita: sopravvive allo svuotamento dei dati del WebView ed entra nel backup Android
        @JavascriptInterface
        fun mirrorWrite(json: String) {
            try {
                val cur = File(filesDir, "gp_mirror.json")
                val prev = File(filesDir, "gp_mirror.prev.json")
                if (cur.exists() && (!prev.exists() || System.currentTimeMillis() - prev.lastModified() > 3600_000L)) cur.copyTo(prev, true)
                val tmp = File(filesDir, "gp_mirror.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(cur)) { cur.writeText(json); tmp.delete() }
            } catch (e: Exception) { }
        }

        @JavascriptInterface
        fun mirrorRead(): String {
            return try {
                val cur = File(filesDir, "gp_mirror.json")
                val prev = File(filesDir, "gp_mirror.prev.json")
                if (cur.exists() && cur.length() > 2) cur.readText()
                else if (prev.exists()) prev.readText() else ""
            } catch (e: Exception) { "" }
        }

        // salva un file in Download (non in galleria). 1 = Download, 2 = cartella dell'app, 0 = errore
        @JavascriptInterface
        fun saveDownload(name: String, mime: String, b64: String): Int {
            return try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "GranPesca.bin" }
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val res = contentResolver
                    val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    var uri: Uri? = null
                    res.query(coll, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                        arrayOf(safe, Environment.DIRECTORY_DOWNLOADS + "%"), null)?.use { c ->
                        if (c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(coll, c.getLong(0))
                    }
                    if (uri == null) {
                        val cv = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, safe)
                            put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        }
                        uri = res.insert(coll, cv)
                    }
                    val u = uri ?: return 0
                    res.openOutputStream(u, "wt")?.use { it.write(bytes) } ?: return 0
                    1
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    dir.mkdirs(); File(dir, safe).writeBytes(bytes); 2
                }
            } catch (e: Exception) { 0 }
        }
        // rilegge da Download il file scritto da saveDownload (stesso nome): serve al controllo del file .gp. Stringa vuota se non c'è
        @JavascriptInterface
        fun readDownload(name: String): String {
            return try {
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
                if (safe.isEmpty()) return ""
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val res = contentResolver
                    val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    var uri: Uri? = null
                    res.query(coll, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                        arrayOf(safe, Environment.DIRECTORY_DOWNLOADS + "%"), null)?.use { c ->
                        if (c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(coll, c.getLong(0))
                    }
                    val u = uri ?: return ""
                    res.openInputStream(u)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    val f = File(dir, safe)
                    if (f.exists()) f.readText() else ""
                }
            } catch (e: Exception) { "" }
        }
        @JavascriptInterface
        fun shareImage(b64: String, name: String, text: String) {
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "shared").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "GranPesca.jpg" }
                    val f = File(dir, safe)
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.photos", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/jpeg"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, null))
                } catch (e: Exception) { }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
