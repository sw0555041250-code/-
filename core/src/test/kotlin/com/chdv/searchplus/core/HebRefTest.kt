package com.chdv.searchplus.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HebRefTest {
    @Test fun gematria() {
        assertEquals(27, HebNum.gematria("כז"))
        assertEquals(27, HebNum.gematria("כ\"ז"))
        assertEquals(15, HebNum.gematria("טו"))
        assertEquals(153, HebNum.gematria("קנג"))
        assertNull(HebNum.gematria("ויצא"))
        assertNull(HebNum.gematria("שלום"))
        assertEquals("כז", HebNum.toHeb(27))
        assertEquals("טז", HebNum.toHeb(16))
        assertEquals("קנג", HebNum.toHeb(153))
    }

    @Test fun numbers() {
        assertEquals(27 to 2, HebNum.read(listOf("כף", "זין"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("חף", "זין"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("עשרים", "ושבע"), 0))
        assertEquals(27 to 2, HebNum.read(listOf("שבע", "ועשרים"), 0))
        assertEquals(17 to 2, HebNum.read(listOf("שבעה", "עשר"), 0))
        assertEquals(27 to 1, HebNum.read(listOf("27"), 0))
        assertEquals(3 to 1, HebNum.read(listOf("ג"), 0))
        assertEquals(7 to 1, HebNum.read(listOf("זין"), 0))
    }

    @Test fun gemara() {
        val cases = listOf(
            "מסכת ברכות דף כז עמוד א",
            "ברכות דף כ\"ז ע\"א",
            "ברכות כז.",
            "גמרא ברכות דף כף זין עמוד אלף",
            "גמרא ברכות דף חף זין עמוד א'",
            "מסכת ברכות דף עשרים ושבע עמוד א במהדורת ש\"ס וילנא",
            "תפתח לי ברכות דף כ״ז ע״א",
        )
        for (c in cases) {
            val r = RefParser.parse(c)
            assertNotNull(c, r)
            assertEquals(c, Ref.Kind.GEMARA, r!!.kind)
            assertEquals(c, "ברכות", r.work)
            assertEquals(c, 27, r.daf)
            assertEquals(c, 1, r.amud)
        }
        val r = RefParser.parse("בבא מציעא דף נט עמוד ב שוטנשטיין")!!
        assertEquals("בבא מציעא", r.work); assertEquals(59, r.daf); assertEquals(2, r.amud)
        assertEquals(listOf("שוטנשטיין"), r.extra)
        val s = RefParser.parse("שבת דף לא")!!
        assertEquals("שבת", s.work); assertEquals(31, s.daf); assertNull(s.amud)
        assertEquals("עוז", RefParser.parse("ברכות דף ב עוז והדר")!!.extra.first())
        assertEquals(64, RefParser.parse("ברכות סד.")!!.daf)
    }

    @Test fun sichos() {
        val cases = listOf(
            "ליקוטי שיחות חלק ג שיחה לפרשת ויצא",
            "לקוטי שיחות חלק ג' פרשת ויצא",
            "ליקוטי שיחות ח\"ג ויצא",
            "לקו\"ש חלק ג ויצא",
            "ליקוטי שיחות חלק שלישי ויצא",
        )
        for (c in cases) {
            val r = RefParser.parse(c)
            assertNotNull(c, r)
            assertEquals(c, Ref.Kind.SICHOS, r!!.kind)
            assertEquals(c, 3, r.volume)
            assertEquals(c, "ויצא", r.parsha)
        }
        val r = RefParser.parse("ליקוטי שיחות חלק טו שיחה ב לפרשת חיי שרה")!!
        assertEquals(15, r.volume); assertEquals(2, r.sicha); assertEquals("חיי שרה", r.parsha)
    }

    @Test fun generic() {
        val r = RefParser.parse("תניא פרק ה")!!
        assertEquals(Ref.Kind.GENERIC, r.kind); assertEquals("תניא", r.work); assertEquals(5, r.chapter)
        val s = RefParser.parse("שולחן ערוך אורח חיים סימן קכח")!!
        assertEquals(128, s.siman); assertEquals("שולחן ערוך אורח חיים", s.work)
        assertNull(RefParser.parse("מה הדין של מי ששכח לברך"))
        assertNull(RefParser.parse("רבי שמעון בר יוחאי"))
    }

    @Test fun pageFinder() {
        assertEquals(listOf(PageFinder.amudIndex(27, 1)), PageFinder.dafMarks("פרק רביעי תפלת השחר ברכות דף כז ע\"א\nגמרא ...", "ברכות").distinct().take(1))
        assert(PageFinder.dafMarks("BERACHOS 27a  ...", null).contains(PageFinder.amudIndex(27, 1)))
        val anchors = listOf(10 to 0, 12 to 2, 20 to 10, 60 to 50)
        assertEquals(60, PageFinder.estimate(anchors, 50, 300))
        assertEquals(35, PageFinder.estimate(anchors, 25, 300))
        assertEquals(3, PageFinder.volumeOf("לקוטי שיחות חלק ג.pdf"))
        assertEquals(15, PageFinder.volumeOf("ליקוטי שיחות - חלק טו"))
        assertEquals(3, PageFinder.volumeOf("LS 3"))
        assert(PageFinder.headerHas("לקוטי שיחות ויצא\nא. ...", listOf("ויצא")))
        assert(PageFinder.hasNumbered("תניא פרק ה\n...", "פרק", 5))
    }
}
