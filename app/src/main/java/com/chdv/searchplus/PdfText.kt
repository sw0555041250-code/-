package com.chdv.searchplus

import android.content.Context
import android.os.ParcelFileDescriptor
import io.legere.pdfiumandroid.PdfiumCore
import java.io.File

/**
 * Fast text reading with PDFium (the PDF engine of Chrome, in native code) – many times faster than
 * PDFBox on a weak device. Used for the scan, and to find a place in a book that was not scanned yet.
 * PDFBox stays as the fallback (and for the letter positions used to mark words in the reader).
 */
object PdfText {
    @Volatile private var core: PdfiumCore? = null
    @Volatile var broken = false

    @Synchronized
    private fun core(c: Context): PdfiumCore = core ?: PdfiumCore(c.applicationContext).also { core = it }

    /** Raw text of every page (null for a page without text). Null when PDFium can't read the file. */
    fun pages(c: Context, f: File, cancel: () -> Boolean = { false }): List<String?>? {
        if (broken) return null
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                core(c).newDocument(pfd).use { doc ->
                    val n = doc.getPageCount()
                    val out = ArrayList<String?>(n)
                    for (i in 0 until n) {
                        if (cancel()) break
                        out.add(pageText(doc, i, Int.MAX_VALUE))
                    }
                    out
                }
            }
        } catch (e: UnsatisfiedLinkError) { broken = true; null } catch (e: Throwable) { null }
    }

    /**
     * Only the top and bottom of each page (headers, page numbers) – enough to find a parsha, a daf
     * or a printed page number in a book that was not scanned yet. Page numbers start at 1.
     */
    fun heads(c: Context, f: File, cancel: () -> Boolean = { false }): Map<Int, String> {
        if (broken) return emptyMap()
        val out = HashMap<Int, String>()
        try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                core(c).newDocument(pfd).use { doc ->
                    for (i in 0 until doc.getPageCount()) {
                        if (cancel()) break
                        val t = pageText(doc, i, Int.MAX_VALUE) ?: continue
                        val p = Indexer.prepare(t) ?: continue
                        out[i + 1] = if (p.length <= 600) p else p.take(400) + "\n" + p.takeLast(200)
                    }
                }
            }
        } catch (e: UnsatisfiedLinkError) { broken = true } catch (e: Throwable) {}
        return out
    }

    private fun pageText(doc: io.legere.pdfiumandroid.PdfDocument, i: Int, max: Int): String? = try {
        doc.openPage(i).use { page ->
            page.openTextPage().use { tp ->
                val n = tp.textPageCountChars()
                if (n <= 0) null else tp.textPageGetText(0, minOf(n, max))?.replace("\r\n", "\n")?.replace('\r', '\n')
            }
        }
    } catch (e: Throwable) { null }
}
