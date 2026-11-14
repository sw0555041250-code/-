package com.chdv.searchplus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** One indexed book. */
data class Book(
    val id: Long,
    val path: String,
    val dir: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    val pages: Int,
    val status: Int,
    val textPages: Int,
    val emptyPages: Int,
) {
    val title: String get() = name.removeSuffix(".pdf").removeSuffix(".PDF")
}

object St {
    const val PENDING = 0
    const val DONE = 1
    const val ERROR = 2
}

object Kind {
    const val TEXT = 0
    const val OCR = 1
    const val EMPTY = 2
}

/**
 * The search index: an SQLite database with an FTS4 full-text table. Every PDF page is one row.
 * Kept on the device's shared storage-independent app folder.
 */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "index.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE files(id INTEGER PRIMARY KEY, path TEXT UNIQUE, dir TEXT, name TEXT, size INTEGER, mtime INTEGER, " +
                "pages INTEGER DEFAULT 0, status INTEGER DEFAULT 0, textPages INTEGER DEFAULT 0, emptyPages INTEGER DEFAULT 0, " +
                "emb INTEGER DEFAULT 0, err TEXT)"
        )
        db.execSQL("CREATE TABLE pages(id INTEGER PRIMARY KEY, file INTEGER, page INTEGER, kind INTEGER)")
        db.execSQL("CREATE INDEX pages_file ON pages(file, page)")
        db.execSQL("CREATE INDEX pages_kind ON pages(kind)")
        db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(body)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS files")
        db.execSQL("DROP TABLE IF EXISTS pages")
        db.execSQL("DROP TABLE IF EXISTS fts")
        onCreate(db)
    }

    // ---------- books ----------

    @Volatile
    private var cache: Map<Long, Book>? = null

    fun invalidate() { cache = null; Catalog.invalidate() }

    private fun readBook(c: android.database.Cursor) = Book(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
        c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9)
    )

    private val COLS = "id,path,dir,name,size,mtime,pages,status,textPages,emptyPages"

    fun books(): Map<Long, Book> {
        cache?.let { return it }
        val m = HashMap<Long, Book>()
        readableDatabase.rawQuery("SELECT $COLS FROM files", null).use { c ->
            while (c.moveToNext()) { val b = readBook(c); m[b.id] = b }
        }
        cache = m
        return m
    }

    fun book(id: Long): Book? = books()[id]

    fun bookByPath(path: String): Book? = books().values.firstOrNull { it.path == path }

    fun addFile(f: File): Long {
        val v = ContentValues().apply {
            put("path", f.absolutePath); put("dir", f.parent ?: ""); put("name", f.name)
            put("size", f.length()); put("mtime", f.lastModified()); put("status", St.PENDING)
        }
        val id = writableDatabase.insertWithOnConflict("files", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        invalidate()
        return id
    }

    fun clearContent(fileId: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM pages WHERE file=?)", arrayOf(fileId))
            db.execSQL("DELETE FROM pages WHERE file=?", arrayOf(fileId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun removeFile(fileId: Long) {
        clearContent(fileId)
        writableDatabase.execSQL("DELETE FROM files WHERE id=?", arrayOf(fileId))
        invalidate()
    }

    fun markChanged(fileId: Long, f: File) {
        clearContent(fileId)
        writableDatabase.execSQL(
            "UPDATE files SET size=?, mtime=?, status=?, pages=0, textPages=0, emptyPages=0, emb=0, err=NULL WHERE id=?",
            arrayOf(f.length(), f.lastModified(), St.PENDING, fileId)
        )
        invalidate()
    }

    fun resetAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM fts")
        db.execSQL("DELETE FROM pages")
        db.execSQL("UPDATE files SET status=0, pages=0, textPages=0, emptyPages=0, emb=0, err=NULL")
        invalidate()
    }

    fun pendingFiles(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id, path FROM files WHERE status=? ORDER BY dir, name", arrayOf(St.PENDING.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1))
        }
        return out
    }

    /** Save all pages of one book in one transaction. texts[i] == null means the page had no usable text. */
    fun saveBook(fileId: Long, texts: List<String?>, kind: Int = Kind.TEXT) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM fts WHERE docid IN (SELECT id FROM pages WHERE file=?)", arrayOf(fileId))
            db.execSQL("DELETE FROM pages WHERE file=?", arrayOf(fileId))
            val insPage = db.compileStatement("INSERT INTO pages(file, page, kind) VALUES(?,?,?)")
            val insFts = db.compileStatement("INSERT INTO fts(docid, body) VALUES(?,?)")
            var text = 0; var empty = 0
            texts.forEachIndexed { i, t ->
                insPage.bindLong(1, fileId); insPage.bindLong(2, (i + 1).toLong())
                insPage.bindLong(3, if (t == null) Kind.EMPTY.toLong() else kind.toLong())
                val pid = insPage.executeInsert()
                if (t != null) {
                    insFts.bindLong(1, pid); insFts.bindString(2, t); insFts.executeInsert()
                    text++
                } else empty++
            }
            db.execSQL(
                "UPDATE files SET pages=?, textPages=?, emptyPages=?, status=?, err=NULL WHERE id=?",
                arrayOf(texts.size, text, empty, St.DONE, fileId)
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidate()
    }

    fun markError(fileId: Long, err: String) {
        writableDatabase.execSQL("UPDATE files SET status=?, err=? WHERE id=?", arrayOf(St.ERROR, err.take(300), fileId))
        invalidate()
    }

    /** Pages that had no text layer, for OCR. Returns (pageId, fileId, page). */
    fun emptyPages(limit: Int, onlyFile: Long? = null): List<Triple<Long, Long, Int>> {
        val out = ArrayList<Triple<Long, Long, Int>>()
        val q = if (onlyFile != null) "SELECT id, file, page FROM pages WHERE kind=2 AND file=$onlyFile ORDER BY page LIMIT $limit"
        else "SELECT id, file, page FROM pages WHERE kind=2 ORDER BY file, page LIMIT $limit"
        readableDatabase.rawQuery(q, null).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getLong(1), c.getInt(2)))
        }
        return out
    }

    fun saveOcr(pageId: Long, fileId: Long, text: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (text != null) {
                db.execSQL("INSERT OR REPLACE INTO fts(docid, body) VALUES(?,?)", arrayOf(pageId, text))
                db.execSQL("UPDATE pages SET kind=1 WHERE id=?", arrayOf(pageId))
                db.execSQL("UPDATE files SET textPages=textPages+1, emptyPages=emptyPages-1 WHERE id=?", arrayOf(fileId))
            } else {
                // nothing readable even with OCR (blank page / picture) - mark as done so it is not retried
                db.execSQL("UPDATE pages SET kind=3 WHERE id=?", arrayOf(pageId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        invalidate()
    }

    fun pageText(fileId: Long, page: Int): String? {
        readableDatabase.rawQuery(
            "SELECT f.body FROM pages p JOIN fts f ON f.docid=p.id WHERE p.file=? AND p.page=?",
            arrayOf(fileId.toString(), page.toString())
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    /** Text of every page of a book that has text: page number → text. */
    fun pageTexts(fileId: Long): Map<Int, String> {
        val m = HashMap<Int, String>()
        readableDatabase.rawQuery("SELECT p.page, f.body FROM pages p JOIN fts f ON f.docid = p.id WHERE p.file=?", arrayOf(fileId.toString())).use { c ->
            while (c.moveToNext()) m[c.getInt(0)] = c.getString(1)
        }
        return m
    }

    fun pageTextById(pageId: Long): String? {
        readableDatabase.rawQuery("SELECT body FROM fts WHERE docid=?", arrayOf(pageId.toString())).use { c ->
            return if (c.moveToFirst()) c.getString(0) else null
        }
    }

    fun pageRef(pageId: Long): Pair<Long, Int>? {
        readableDatabase.rawQuery("SELECT file, page FROM pages WHERE id=?", arrayOf(pageId.toString())).use { c ->
            return if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else null
        }
    }

    // ---------- semantic bookkeeping ----------

    fun filesForEmbedding(): List<Book> {
        val ids = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id FROM files WHERE status=1 AND emb=0 ORDER BY dir, name", null).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        val m = books()
        return ids.mapNotNull { m[it] }
    }

    fun pagesOf(fileId: Long): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery(
            "SELECT p.id, f.body FROM pages p JOIN fts f ON f.docid=p.id WHERE p.file=? ORDER BY p.page",
            arrayOf(fileId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0) to c.getString(1)) }
        return out
    }

    fun markEmbedded(fileId: Long, v: Int = 1) {
        writableDatabase.execSQL("UPDATE files SET emb=? WHERE id=?", arrayOf(v, fileId))
    }

    fun resetEmbeddings() { writableDatabase.execSQL("UPDATE files SET emb=0") }

    fun embeddedCount(): Int = count("SELECT COUNT(*) FROM files WHERE emb=1")

    // ---------- stats ----------

    fun count(sql: String): Int = readableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    data class Stats(val books: Int, val done: Int, val pending: Int, val errors: Int, val pages: Int, val textPages: Int, val emptyPages: Int, val embedded: Int)

    fun stats(): Stats = Stats(
        count("SELECT COUNT(*) FROM files"),
        count("SELECT COUNT(*) FROM files WHERE status=1"),
        count("SELECT COUNT(*) FROM files WHERE status=0"),
        count("SELECT COUNT(*) FROM files WHERE status=2"),
        count("SELECT COALESCE(SUM(pages),0) FROM files"),
        count("SELECT COALESCE(SUM(textPages),0) FROM files"),
        count("SELECT COUNT(*) FROM pages WHERE kind=2"),
        count("SELECT COUNT(*) FROM files WHERE emb=1"),
    )

    fun errors(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT name, COALESCE(err,'') FROM files WHERE status=2 ORDER BY name LIMIT 300", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getString(1))
        }
        return out
    }

    companion object {
        @Volatile
        private var inst: Db? = null
        fun get(): Db = inst ?: synchronized(this) { inst ?: Db(App.ctx).also { inst = it } }
    }
}
