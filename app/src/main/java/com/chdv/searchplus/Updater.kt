package com.chdv.searchplus

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

/**
 * Self update from GitHub Releases (github.com/sw0555041250-code/Search-Plus-). Every push to the
 * repository builds a new signed APK and publishes it as release v1.0.<build>. All builds are
 * signed with the same key, so an update installs over the existing app and keeps the index.
 */
object Updater {
    private const val API = "https://api.github.com/repos/sw0555041250-code/Search-Plus-/releases/latest"
    @Volatile private var busy = false

    private fun build(v: String): Int = v.trim().removePrefix("v").substringAfterLast('.').toIntOrNull() ?: 0

    private fun local(a: Activity) = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    fun dailyCheck(a: Activity) {
        if (!Prefs.b("autoUpdate", true)) return
        // after an update (or the first run of this version) check again right away; otherwise every 6 hours
        val v = local(a)
        if (Prefs.s("checkedForVersion", "") != v) { Prefs.put("checkedForVersion", v); Prefs.put("lastUpdateCheck", 0L) }
        if (System.currentTimeMillis() - Prefs.l("lastUpdateCheck", 0L) < 6L * 3600 * 1000) return
        Prefs.put("lastUpdateCheck", System.currentTimeMillis())
        check(a, true)
    }

    fun check(a: Activity, silent: Boolean) {
        if (busy) return
        busy = true
        if (!silent) a.toast("בודק אם יש עדכון…")
        Thread {
            try {
                val js = org.json.JSONObject(Net.text(API))
                val tag = js.optString("tag_name")
                val assets = js.optJSONArray("assets")
                val apk = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it).optString("browser_download_url") }.firstOrNull { it.endsWith(".apk") }
                // "what's new" in Hebrew, written for every version (docs/whatsnew.md)
                val notes = js.optString("body").replace("\r\n", "\n").trim().take(2500)
                val loc = local(a)
                ui {
                    busy = false
                    if (build(tag) > build(loc) && apk != null) {
                        Sheet.confirm(a, "יש עדכון לחיפוש פלוס",
                            "גרסה חדשה: ${tag.removePrefix("v")} (שלך: $loc)\n\n$notes", "הורדה והתקנה") { download(a, apk) }
                    } else if (!silent) Sheet.info(a, "אין עדכון חדש", "הגרסה שלך ($loc) היא העדכנית ביותר.")
                }
            } catch (e: Exception) {
                ui {
                    busy = false
                    Prefs.put("lastUpdateCheck", 0L) // try again next time
                    if (!silent) Sheet.info(a, "לא ניתן לבדוק עדכונים", "ודאו שהמכשיר מחובר לאינטרנט ונסו שוב.\n(${e.javaClass.simpleName}: ${e.message ?: ""})")
                }
            }
        }.start()
    }

    private fun download(a: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            Sheet.confirm(a, "צריך אישור חד-פעמי",
                "כדי להתקין עדכונים יש לאשר לחיפוש פלוס להתקין אפליקציות. אחרי האישור חזרו ולחצו שוב על \"בדיקת עדכונים\".", "פתיחת ההגדרה") {
                a.safeStart(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            }
            return
        }
        a.toast("מוריד את העדכון…")
        Thread {
            try {
                val out = File(File(a.cacheDir, "update").apply { mkdirs() }, "SearchPlus.apk")
                var last = -1
                Net.download(url, out) { p -> if (p / 25 != last / 25) { last = p; ui { a.toast("מוריד… $p%") } } }
                ui {
                    val i = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Library.uriFor(a, out), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (!a.safeStart(i)) a.toast("לא ניתן לפתוח את מתקין האפליקציות")
                }
            } catch (e: Exception) {
                ui { Sheet.info(a, "ההורדה נכשלה", "בדקו את החיבור לאינטרנט ונסו שוב.") }
            }
        }.start()
    }
}
