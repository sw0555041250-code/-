package com.chdv.searchplus.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderNamesTest {
    @Test fun same() {
        assertTrue(FolderNames.same("ליקוטי שיחות", "לקוטי שיחות"))
        assertTrue(FolderNames.same("לקו\"ש", "ליקוטי שיחות"))
        assertTrue(FolderNames.same("תניא", "תניא (2)"))
        assertTrue(FolderNames.same("תניא", "תניא - עותק"))
        assertTrue(FolderNames.same("תניא", "תניא חדש"))
        assertTrue(FolderNames.same("שולחן ערוך", "שלחן ערוך"))
        assertTrue(FolderNames.same("Tanya", "tanya copy"))
        assertFalse(FolderNames.same("ליקוטי שיחות חלק א", "ליקוטי שיחות חלק ב"))
        assertFalse(FolderNames.same("תניא", "תורה אור"))
        assertFalse(FolderNames.same("ספר 1", "ספר 2"))
        assertFalse(FolderNames.same("(2)", "עותק"))
    }
}
