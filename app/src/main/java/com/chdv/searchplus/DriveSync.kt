package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.CheckBox
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Connect Google Drive": the app asks the user to sign in with their Google account and allow
 * reading their Drive. Then it finds every PDF there (with its folders), the user chooses what to
 * bring, and the books are downloaded into the cloud library folder on the device and scanned like
 * any other book – so searching and answers happen on the device. Read-only: nothing in Drive is changed.
 *
 * Works when the developer registered the app with Google once (package name + signing fingerprint,
 * Drive API enabled) – see docs/GOOGLE_DRIVE_SETUP.md.
 */
object DriveSync {
    const val REQ = 61
    private const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val API = "https://www.googleapis.com/drive/v3/files"

    class Item(val id: String, val name: String, val folder: String, val size: Long, val modified: String)

    /** Step 1: ask for permission (Google shows its own sign-in / consent screen). */
    fun connect(a: Activity, onToken: (String) -> Unit) {
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        try {
            Identity.getAuthorizationClient(a).authorize(req)
                .addOnSuccessListener { r ->
                    if (r.hasResolution()) {
                        pending = onToken
                        try {
                            @Suppress("DEPRECATION")
                            a.startIntentSenderForResult(r.pendingIntent!!.intentSender, REQ, null, 0, 0, 0, null)
                        } catch (e: Exception) { failed(a, e) }
                    } else r.accessToken?.let(onToken) ?: failed(a, null)
                }
                .addOnFailureListener { e -> failed(a, e) }
        } catch (e: Throwable) { failed(a, e) }
    }

    private var pending: ((String) -> Unit)? = null

    /** Call from onActivityResult. */
    fun onResult(a: Activity, requestCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ) return false
        val cb = pending; pending = null
        try {
            val r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data)
            val t = r.accessToken
            if (t != null && cb != null) cb(t) else failed(a, null)
        } catch (e: Throwable) { failed(a, e) }
        return true
    }

    /** The one-time registration with Google, step by step (only the app's owner can do it, with their Google account). */
    const val SETUP = "https://github.com/sw0555041250-code/Search-Plus-/blob/main/docs/GOOGLE_DRIVE_SETUP.md"

    private fun failed(a: Activity, e: Throwable?) {
        val msg = e?.message ?: ""
        val notRegistered = msg.contains("DEVELOPER_ERROR") || msg.contains("10:") || msg.contains("UNREGISTERED") || e == null
        if (notRegistered) {
            Sheet.confirm(a, "Google עוד לא מכירה את חיפוש פלוס",
                "כדי שהתוכנה תוכל לקרוא את ה-Drive, צריך לרשום אותה פעם אחת אצל Google (כ-10 דקות, עם חשבון Google של בעל התוכנה). אחרי זה החיבור עובד לכל מי שמשתמש בה.\n\n" +
                    "בינתיים אפשר להביא ספרים מה-Drive בלי חיבור: \"בחירת ספרים מ-Drive\" – נכנסים לתיקייה, ⋮ › \"בחירת הכול\"." +
                    (if (msg.isNotEmpty()) "\n\n($msg)" else ""), "להוראות הרישום") {
                if (!a.safeStart(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SETUP)))) a.toast(SETUP)
            }
            return
        }
        Sheet.info(a, "החיבור ל-Google Drive לא הצליח",
            "אפשר לנסות שוב. אם זה חוזר: ודאו שיש אינטרנט, שיש במכשיר שירותי Google ושאתם מחוברים לחשבון.\n\n$msg")
    }

    // ---------- Drive API ----------

    private fun get(url: String, token: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 20000; c.readTimeout = 60000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect() }
            else if (code >= 400) { val e = c.errorStream?.bufferedReader()?.readText() ?: ""; c.disconnect(); throw java.io.IOException("Drive $code ${e.take(200)}") }
            else return c
        }
        throw java.io.IOException("too many redirects")
    }

    /** Every PDF in the user's Drive (own files and shared with them), with the folder path of each. */
    fun listAll(token: String, progress: (String) -> Unit): List<Item> {
        val folders = HashMap<String, Pair<String, String?>>() // id → (name, parent)
        val shared = HashSet<String>() // folders shared with me (someone else's)
        val pdfs = ArrayList<Triple<JSONObject, String?, Unit>>()
        var page: String? = null
        val q = URLEncoder.encode("(mimeType='application/pdf' or mimeType='application/vnd.google-apps.folder') and trashed=false", "UTF-8")
        do {
            val url = "$API?q=$q&pageSize=1000&fields=" + URLEncoder.encode("nextPageToken,files(id,name,mimeType,parents,size,modifiedTime,ownedByMe)", "UTF-8") +
                "&includeItemsFromAllDrives=true&supportsAllDrives=true" + (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val c = get(url, token)
            val js = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            val files = js.optJSONArray("files")
            if (files != null) for (k in 0 until files.length()) {
                val f = files.getJSONObject(k)
                val parent = f.optJSONArray("parents")?.optString(0)
                if (f.optString("mimeType") == "application/vnd.google-apps.folder") {
                    folders[f.getString("id")] = f.optString("name") to parent
                    if (!f.optBoolean("ownedByMe", true)) shared.add(f.getString("id"))
                } else pdfs.add(Triple(f, parent, Unit))
            }
            progress("מצאתי ${pdfs.size} ספרים…")
            page = js.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        // books shared with me (not on my Drive): under "משותף איתי", in the folders they were shared in
        fun path(id: String?, fileShared: Boolean): String {
            val parts = ArrayList<String>()
            var cur = id; var guard = 0; var top: String? = null
            while (cur != null && guard++ < 30) { val f = folders[cur] ?: break; parts.add(0, f.first); top = cur; cur = f.second }
            val isShared = if (top != null) top in shared else fileShared
            return (if (isShared) listOf("משותף איתי") + parts else parts).joinToString("/")
        }
        return pdfs.map { (f, parent, _) -> Item(f.getString("id"), f.optString("name"), path(parent, !f.optBoolean("ownedByMe", true)),
            f.optString("size").toLongOrNull() ?: 0L, f.optString("modifiedTime")) }
    }

    fun download(token: String, it: Item, dest: File) {
        val c = get("$API/${it.id}?alt=media&supportsAllDrives=true", token)
        val tmp = File(dest.parentFile, dest.name + ".part")
        try { c.inputStream.use { i -> FileOutputStream(tmp).use { o -> i.copyTo(o, 1 shl 16) } } } finally { c.disconnect() }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw java.io.IOException("rename")
    }

    private fun safe(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "ספר" }

    /** The whole flow: connect → list → choose folders → download new/changed books → scan. */
    fun run(a: Activity, dir: File, done: () -> Unit) {
        connect(a) { token ->
            val st = a.label("מתחבר…", 15f, Col.SUB)
            val d = Sheet.make(a, a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(18)); addView(a.label("☁ קורא את ה-Drive שלך", 18f, Col.TEXT, true)); addView(st) })
            d.setCancelable(false); d.show()
            Thread {
                val items = try { listAll(token) { t -> ui { st.text = t } } } catch (e: Throwable) {
                    ui { d.dismiss(); Sheet.info(a, "לא ניתן לקרוא את ה-Drive", e.message ?: e.javaClass.simpleName) }; return@Thread
                }
                ui {
                    d.dismiss()
                    if (items.isEmpty()) { Sheet.info(a, "לא נמצאו ספרים", "ב-Google Drive שלך אין קבצי PDF."); return@ui }
                    choose(a, items) { chosen -> fetch(a, token, chosen, dir, done) }
                }
            }.start()
        }
    }

    /** Folder checklist (top-level folders), with how many books and how big. */
    private fun choose(a: Activity, items: List<Item>, go: (List<Item>) -> Unit) {
        // each folder that was shared with me is its own choice ("משותף איתי › …")
        val groups = items.groupBy { i ->
            val parts = i.folder.split('/').filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> "(ראשי)"
                parts[0] == "משותף איתי" -> if (parts.size > 1) "משותף איתי › " + parts[1] else "משותף איתי"
                else -> parts[0]
            }
        }.toSortedMap()
        val box = a.vbox().apply { setPadding(a.dp(18), a.dp(16), a.dp(18), a.dp(14)) }
        box.addView(a.label("נמצאו ${items.size} ספרים (${human(items.sumOf { it.size })})", 18f, Col.TEXT, true))
        box.addView(a.label("בחרו אילו תיקיות להביא למכשיר. ספרים שכבר הובאו לא יורדים שוב.", 14f, Col.SUB).apply { setPadding(0, a.dp(4), 0, a.dp(8)) })
        val checks = ArrayList<Pair<CheckBox, List<Item>>>()
        val list = a.vbox()
        for ((name, l) in groups) {
            val cb = CheckBox(a).apply {
                text = "📁 $name  ·  ${l.size} ספרים · ${human(l.sumOf { it.size })}"
                isChecked = true; textSize = 15f * scale(); setTextColor(Col.TEXT)
                layoutDirection = View.LAYOUT_DIRECTION_RTL
            }
            list.addView(cb); checks.add(cb to l)
        }
        box.addView(android.widget.ScrollView(a).apply { addView(list) }, lp(MATCH, a.dp(300)))
        lateinit var dlg: android.app.Dialog
        val row = a.hbox()
        row.addView(a.pill("הבאה") { dlg.dismiss(); go(checks.filter { it.first.isChecked }.flatMap { it.second }) }, lp(0, WRAP, 1f).m(a.dp(4), a.dp(10), a.dp(4), 0))
        row.addView(a.pill("ביטול", false) { dlg.dismiss() }, lp(0, WRAP, 1f).m(a.dp(4), a.dp(10), a.dp(4), 0))
        box.addView(row)
        dlg = Sheet.make(a, box); dlg.show()
    }

    private fun fetch(a: Activity, token: String, items: List<Item>, dir: File, done: () -> Unit) {
        if (items.isEmpty()) return
        val st = a.label("", 15f, Col.SUB)
        var stop = false
        lateinit var dlg: android.app.Dialog
        val box = a.vbox().apply { setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(14)); addView(a.label("☁ מביא ספרים מה-Drive", 18f, Col.TEXT, true)); addView(st) }
        box.addView(a.pill("עצירה (מה שירד נשמר)", false) { stop = true }, lp(MATCH, WRAP).m(0, a.dp(12), 0, 0))
        dlg = Sheet.make(a, box); dlg.setCancelable(false); dlg.show()
        Thread {
            var ok = 0; var skip = 0; var fail = 0
            for ((k, it) in items.withIndex()) {
                if (stop) break
                val sub = it.folder.split('/').filter { s -> s.isNotBlank() }.joinToString("/") { s -> safe(s) }
                val name = safe(it.name).let { n -> if (n.endsWith(".pdf", true)) n else "$n.pdf" }
                val dest = File(if (sub.isEmpty()) dir else File(dir, sub), name)
                ui { st.text = "${k + 1} / ${items.size}\n${it.name}" }
                try {
                    if (dest.exists() && (it.size == 0L || dest.length() == it.size)) { skip++; continue }
                    dest.parentFile?.mkdirs()
                    download(token, it, dest); ok++
                } catch (e: Throwable) { fail++ }
            }
            Prefs.put("driveLastSync", System.currentTimeMillis())
            ui {
                dlg.dismiss()
                if (Library.roots().none { dir.absolutePath == it || dir.absolutePath.startsWith("$it/") }) Library.setRoots(Library.roots() + dir.absolutePath)
                Indexer.start(a, user = true)
                done()
                Sheet.info(a, "הספרים מה-Drive הובאו ✓", "ירדו $ok ספרים" + (if (skip > 0) " · $skip כבר היו" else "") + (if (fail > 0) " · $fail לא ירדו" else "") +
                    ".\n\nהם בתיקיות כמו ב-Drive, נכנסו למאגר ונסרקים עכשיו ברקע. לסנכרון ספרים חדשים – לוחצים שוב \"חיבור ל-Google Drive\".")
            }
        }.start()
    }
}
