package com.chdv.searchplus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A library of 12,000 books shaped like a real Chabad library, and the requests people make. */
class NamesTest {
    private val items = ArrayList<Names.Item>()
    private val titleOf = HashMap<Long, String>()
    private var id = 1L
    private fun add(title: String, vararg folders: String) { items.add(Names.Item(id, title, folders.toList())); titleOf[id] = title; id++ }

    private val names: Names by lazy {
        for (v in 1..39) add("לקוטי שיחות חלק ${HebNum.toHeb(v)}", "חסידות", "רבותינו נשיאינו", "הרבי", "לקוטי שיחות")
        add("ליקוטי שיחות - כרך 24 (מהדורה חדשה)", "חסידות", "ליקוטי שיחות מהדורה חדשה")
        for (m in RefParser.MASECHTOT) add(m, "גמרא", "ש\"ס וילנא")
        for (m in listOf("ברכות", "שבת", "בבא מציעא")) add("$m - עוז והדר", "גמרא", "עוז והדר")
        add("ברכות א - שוטנשטיין", "גמרא", "Schottenstein")
        add("ירושלמי ברכות", "גמרא", "ירושלמי")
        add("משניות ברכות", "משנה")
        add("חלק א - אורח חיים", "הלכה", "שו\"ע אדה\"ז")
        add("חלק ב - אורח חיים", "הלכה", "שו\"ע אדה\"ז")
        add("קיצור שולחן ערוך", "הלכה")
        add("משנה ברורה חלק ג", "הלכה", "משנה ברורה")
        add("ספר התניא", "חסידות", "תניא")
        for (v in 1..30) add("אגרות קודש חלק ${HebNum.toHeb(v)}", "חסידות", "רבותינו נשיאינו", "אגרות קודש")
        add("תורת מנחם תשמ\"ח", "חסידות", "תורת מנחם")
        add("scan_0042", "חסידות", "ספרים שונים")
        // filler up to 12,000
        val voc = listOf("ספר", "קונטרס", "מאמרים", "שיחות", "דרושים", "פירוש", "חידושים", "תורה", "חסידות", "הלכות", "שבת", "יום טוב", "מועדים", "פסח",
            "ביאור", "מכתבים", "תולדות", "זכרון", "לקט", "אוצר", "מנחת", "שערי", "דברי", "נועם", "אור", "חיים", "ישועות", "בית", "יוסף", "אהרן")
        val r = java.util.Random(1)
        while (items.size < 12000) add((1..3).joinToString(" ") { voc[r.nextInt(voc.size)] } + " " + r.nextInt(500), voc[r.nextInt(voc.size)], voc[r.nextInt(voc.size)])
        Names(items)
    }

    private fun top(q: String): List<String> {
        val ref = RefParser.parse(q)
        return names.find(q, ref).map { titleOf[it.id]!! }
    }

    @Test fun findsTheRightBooks() {
        val t0 = System.currentTimeMillis()
        names.size
        println("built ${names.size} in ${System.currentTimeMillis() - t0}ms")

        fun check(q: String, wantFirst: String, orAlso: String? = null) {
            val t = System.currentTimeMillis()
            val r = top(q)
            println("「$q」 → ${r.take(3)} (${System.currentTimeMillis() - t}ms)")
            assertTrue("「$q」: got $r", r.isNotEmpty())
            if (orAlso != null && r.first() == orAlso) return
            assertEquals("「$q」", wantFirst, r.first())
        }
        check("לקוטי שיחות חלק כד עמוד 358", "לקוטי שיחות חלק כד")
        check("לקו\"ש חכ\"ד עמ' 358", "לקוטי שיחות חלק כד", "ליקוטי שיחות - כרך 24 (מהדורה חדשה)")
        check("ליקוטי שיחות חלק ד שיחה לפרשת נח", "לקוטי שיחות חלק ד")
        check("לקוטי שיחות חלק כ פרשת ויצא", "לקוטי שיחות חלק כ")
        check("ברכות דף כג", "ברכות")
        check("שו\"ע אדה\"ז סימן ב", "חלק א - אורח חיים")
        check("שולחן ערוך אדמו\"ר הזקן סימן רעח", "חלק א - אורח חיים")
        check("אגרות קודש חלק ה", "אגרות קודש חלק ה")
        check("אג\"ק חלק ה עמוד 20", "אגרות קודש חלק ה")
        check("תניא פרק ה", "ספר התניא")
        check("משנה ברורה", "משנה ברורה חלק ג")
        check("קיצור שולחן ערוך", "קיצור שולחן ערוך")
        // all Berachos editions, the Bavli first; not the Yerushalmi / Mishna
        val b = top("ברכות כז")
        assertTrue(b.containsAll(listOf("ברכות", "ברכות - עוז והדר", "ברכות א - שוטנשטיין")))
        assertTrue(b.indexOf("ירושלמי ברכות").let { it < 0 || it > 2 })

        val t = System.currentTimeMillis()
        repeat(50) { top("לקו\"ש חלק כד עמוד 358") }
        val per = (System.currentTimeMillis() - t) / 50
        println("one request: ${per}ms")
        assertTrue("too slow: ${per}ms", per < 300)
    }
}
