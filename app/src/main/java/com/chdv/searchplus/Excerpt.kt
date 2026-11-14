package com.chdv.searchplus

import android.content.Context
import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * For reading apps that cannot be told "open at page X and mark these words" (ReadEra, NeoReader…):
 * a small PDF made of the found page and the few pages after it, with the found words (or the
 * answering sentence) marked inside the PDF itself. The app opens it on the right page, marked.
 */
object Excerpt {
    private const val PAGES_AFTER = 4

    fun make(c: Context, path: String, page: Int, terms: List<String>, phrase: String?): File? = try {
        Indexer.initPdfBox(c)
        val dir = File(c.cacheDir, "excerpts").apply { mkdirs() }
        // keep only the last few
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
        val title = File(path).nameWithoutExtension.take(60)
        val out = File(dir, "$title – עמוד $page.pdf")
        Indexer.loadDoc(File(path)).use { src ->
            val n = src.numberOfPages
            val p0 = page.coerceIn(1, n)
            val boxes = find(c, src, path, p0, terms, phrase)
            PDDocument().use { dst ->
                for (k in p0..minOf(n, p0 + PAGES_AFTER)) dst.importPage(src.getPage(k - 1))
                if (boxes.isNotEmpty()) {
                    val pg = dst.getPage(0)
                    val box = pg.cropBox
                    val gs = PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 0.38f; strokingAlphaConstant = 0.9f }
                    PDPageContentStream(dst, pg, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                        cs.setGraphicsStateParameters(gs)
                        cs.setNonStrokingColor(1f, 0.75f, 0f)
                        cs.setStrokingColor(0.9f, 0.32f, 0f)
                        cs.setLineWidth(1.6f)
                        for (r in boxes) {
                            // the boxes are measured from the top of the page; PDF counts from the bottom
                            val x = box.lowerLeftX + r.left
                            val y = box.upperRightY - r.bottom
                            cs.addRect(x, y, r.width(), r.height())
                            cs.fillAndStroke()
                        }
                    }
                }
                dst.save(out)
            }
        }
        out
    } catch (e: Throwable) { null }

    /** Boxes (in PDF units, from the top-left) of the sentence, or of the searched words. */
    private fun find(c: Context, d: PDDocument, path: String, p: Int, terms: List<String>, phrase: String?): List<RectF> {
        val raw = PDFTextStripper().apply { startPage = p; endPage = p }.getText(d)
        val method = Heb.repairPage(raw).method
        val words = ArrayList<Pair<String, RectF>>()
        val st = object : PDFTextStripper() {
            override fun writeString(text: String?, tps: MutableList<TextPosition>?) {
                val list = tps ?: return
                val word = ArrayList<TextPosition>()
                fun flush() {
                    if (word.isEmpty()) return
                    val s = Heb.clean(Heb.applyMethod(word.joinToString("") { it.unicode ?: "" }, method)).filter { it.isLetterOrDigit() }
                    if (s.isNotEmpty()) {
                        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -1f; var b = -1f
                        for (tp in word) {
                            val h = tp.heightDir.coerceAtLeast(tp.fontSizeInPt * 0.7f)
                            l = minOf(l, tp.xDirAdj); r = maxOf(r, tp.xDirAdj + tp.widthDirAdj)
                            t = minOf(t, tp.yDirAdj - h); b = maxOf(b, tp.yDirAdj)
                        }
                        words.add(s to RectF(l - 1.5f, t - 1.5f, r + 1.5f, b + 2.5f))
                    }
                    word.clear()
                }
                for (tp in list) { val u = tp.unicode ?: ""; if (u.isBlank() || u == "," || u == "." || u == ":" || u == "-") flush() else word.add(tp) }
                flush()
            }
        }
        st.startPage = p; st.endPage = p
        st.getText(d)
        if (words.size < 5) {
            // a scanned page: OCR it, in PDF units
            val w = d.getPage(p - 1).cropBox.width.toInt().coerceAtLeast(100)
            words.clear(); words.addAll(Ocr.wordBoxes(c, path, p - 1, w))
        }
        val ph = phrase?.let { Heb.words(it).filter { w -> w.length > 1 } } ?: emptyList()
        if (ph.size >= 3) {
            val n = minOf(ph.size, 40); val want = ph.take(n)
            var best = -1; var bestScore = 0
            for (i in words.indices) {
                var sc = 0
                for (k in 0 until n) { val w = words.getOrNull(i + k)?.first ?: break; if (want.any { it == w || (it.length > 2 && (w.endsWith(it) || it.endsWith(w))) }) sc++ }
                if (sc > bestScore) { bestScore = sc; best = i }
            }
            if (best >= 0 && bestScore >= minOf(3, n)) return words.subList(best, minOf(words.size, best + n)).map { it.second }
        }
        val want = terms.map { it.removeSuffix("*") }.filter { it.length > 1 }
        return words.filter { (w, _) -> want.any { w.contains(it) } }.map { it.second }
    }
}
