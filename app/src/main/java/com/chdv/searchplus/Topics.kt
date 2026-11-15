package com.chdv.searchplus

/**
 * "What shall we look into today?" – topics to search, chosen for the day (and the Hebrew month),
 * and offered only if they really appear in the user's books, with how many places they appear in.
 */
object Topics {
    private val GENERAL = listOf(
        "אהבת ישראל", "ביטחון", "שמחה", "תשובה", "תפילה", "צדקה", "אמונה", "השגחה פרטית", "מסירות נפש", "ביאת המשיח",
        "גאולה", "לימוד התורה", "חינוך", "שלום בית", "ענווה", "זריזות", "הפצת המעיינות", "אחדות", "בירור הניצוצות",
        "דירה בתחתונים", "יראת שמים", "קבלת עול", "שבת קודש", "תפילין", "ארץ ישראל", "בית המקדש", "נשמה", "אור וחושך",
        "יעקב בורח מעשו", "פורס מפה ומקדש", "ויצא יעקב מבאר שבע", "סולם מוצב ארצה", "לך לך מארצך", "עקידת יצחק",
        "קריעת ים סוף", "מתן תורה", "אברהם אבינו", "משה רבינו", "דוד המלך", "רבי שמעון בר יוחאי", "הבעל שם טוב",
        "יוסף ואחיו", "שבירת הלוחות", "ברכת המזון", "קריאת שמע", "נטילת ידיים", "הדלקת נרות", "ברכות הנהנין",
        "אהבה ויראה", "מוחין דגדלות", "ביטול היש", "עבודה שבלב", "חסד ורחמים", "מחשבה דיבור ומעשה",
    )

    /** By Hebrew month (ICU: 0 = Tishrei … 12 = Elul; 5/6 = Adar). */
    private val MONTH = mapOf(
        0 to listOf("ראש השנה", "יום הכיפורים", "סוכה", "שמחת תורה", "ארבעת המינים"),
        1 to listOf("נח והמבול", "אברהם אבינו", "מבול"),
        2 to listOf("י״ט כסלו", "חנוכה", "נר חנוכה", "יעקב אבינו", "מסירות נפש"),
        3 to listOf("עשרה בטבת", "יוסף הצדיק", "ירידת מצרים"),
        4 to listOf("ט״ו בשבט", "יו״ד שבט", "יציאת מצרים", "שירת הים"),
        5 to listOf("פורים", "מגילת אסתר", "משלוח מנות", "מרדכי ואסתר"),
        6 to listOf("פורים", "מגילת אסתר", "משלוח מנות", "מרדכי ואסתר"),
        7 to listOf("פסח", "יציאת מצרים", "חמץ ומצה", "הגדה של פסח"),
        8 to listOf("ספירת העומר", "ל״ג בעומר", "רבי שמעון בר יוחאי"),
        9 to listOf("מתן תורה", "שבועות", "קבלת התורה"),
        10 to listOf("י״ב תמוז", "גאולה", "שבעה עשר בתמוז"),
        11 to listOf("תשעה באב", "ט״ו באב", "נחמה", "בית המקדש"),
        12 to listOf("חודש אלול", "תשובה", "המלך בשדה", "ראש השנה"),
    )

    class Topic(val text: String, val places: Int)

    private fun count(t: String): Int = try {
        val expr = Search.buildMatch(Search.terms(t), Match.ALL, true)
        Db.get().readableDatabase.rawQuery("SELECT count(*) FROM (SELECT docid FROM fts WHERE fts MATCH ? LIMIT 999)", arrayOf(expr))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (e: Exception) { 0 }

    /** Today's suggestions (cached for the day). Runs in the background. */
    fun today(n: Int = 6): List<Topic> {
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
        if (Prefs.s("topicsDay", "") == day) {
            val l = Prefs.list("topics").mapNotNull { r -> r.split('|').takeIf { it.size == 2 }?.let { Topic(it[0], it[1].toIntOrNull() ?: 0) } }
            if (l.isNotEmpty()) return l
        }
        val month = try { android.icu.util.HebrewCalendar().get(android.icu.util.Calendar.MONTH) } catch (e: Throwable) { -1 }
        val rnd = java.util.Random(day.toLong())
        val pool = (MONTH[month].orEmpty().shuffled(rnd).take(3) + GENERAL.shuffled(rnd)).distinct()
        val out = ArrayList<Topic>()
        for (t in pool) {
            if (out.size >= n) break
            val c = count(t)
            if (c > 0) out.add(Topic(t, c))
        }
        if (out.isNotEmpty()) { Prefs.put("topicsDay", day); Prefs.putList("topics", out.map { "${it.text}|${it.places}" }) }
        return out
    }
}
