package com.example.sms_reader_pmj

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "sms_database.db"
        const val DATABASE_VERSION = 5          // ← bumped for new columns

        // sms_messages
        const val TABLE_NAME       = "sms_messages"
        const val COLUMN_ID        = "id"
        const val COLUMN_SENDER    = "sender"
        const val COLUMN_MESSAGE   = "message"
        const val COLUMN_TIMESTAMP = "timestamp"
        const val COLUMN_NUMBER_ID = "number_id"
        // ✅ NEW: sync tracking
        const val COLUMN_SYNCED    = "synced"          // 0 = pending, 1 = confirmed by backend
        const val COLUMN_MSG_HASH  = "msg_hash"        // sha256(sender+message+timestamp) for dedup

        // target_numbers
        const val TABLE_NUMBERS  = "target_numbers"
        const val COL_NUM_ID     = "number_id"
        const val COL_NUM_LABEL  = "label"
        const val COL_NUM_PHONE  = "phone"
    }

    /* ──────────────────────────────────────────────
       Schema
    ────────────────────────────────────────────── */

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID        INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SENDER    TEXT,
                $COLUMN_MESSAGE   TEXT,
                $COLUMN_TIMESTAMP TEXT,
                $COLUMN_NUMBER_ID TEXT DEFAULT 'number_1',
                $COLUMN_SYNCED    INTEGER DEFAULT 0,
                $COLUMN_MSG_HASH  TEXT UNIQUE,
                UNIQUE($COLUMN_MESSAGE, $COLUMN_TIMESTAMP)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE $TABLE_NUMBERS (
                $COL_NUM_ID    TEXT PRIMARY KEY,
                $COL_NUM_LABEL TEXT,
                $COL_NUM_PHONE TEXT UNIQUE
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Safe incremental migrations
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_NUMBER_ID TEXT DEFAULT 'number_1'") } catch (_: Exception) {}
        }
        if (oldVersion < 3) {
            try {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS $TABLE_NUMBERS (
                        $COL_NUM_ID TEXT PRIMARY KEY,
                        $COL_NUM_LABEL TEXT,
                        $COL_NUM_PHONE TEXT UNIQUE
                    )
                """.trimIndent())
            } catch (_: Exception) {}
        }
        if (oldVersion < 5) {
            try { db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_SYNCED INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_MSG_HASH TEXT") } catch (_: Exception) {}
            // Back-fill hashes for existing rows
            try {
                db.execSQL("""
                    UPDATE $TABLE_NAME
                    SET $COLUMN_MSG_HASH = hex($COLUMN_SENDER || '|' || $COLUMN_MESSAGE || '|' || $COLUMN_TIMESTAMP)
                    WHERE $COLUMN_MSG_HASH IS NULL
                """.trimIndent())
            } catch (_: Exception) {}
        }
    }

    /* ──────────────────────────────────────────────
       Hash helper
    ────────────────────────────────────────────── */

    private fun buildHash(sender: String, message: String, timestamp: String): String {
        val raw = "$sender|$message|$timestamp"
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /* ──────────────────────────────────────────────
       Insert / upsert
    ────────────────────────────────────────────── */

    /**
     * Inserts a message. Returns the row-id on success, -1 if duplicate (silently ignored).
     * The [synced] flag lets callers mark backend-received rows as already synced.
     */
    fun insertMessage(
        sender: String,
        message: String,
        timestamp: String,
        numberId: String = "number_1",
        synced: Int = 0
    ): Long {
        val hash = buildHash(sender, message, timestamp)
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_SENDER,    sender)
            put(COLUMN_MESSAGE,   message)
            put(COLUMN_TIMESTAMP, timestamp)
            put(COLUMN_NUMBER_ID, numberId)
            put(COLUMN_SYNCED,    synced)
            put(COLUMN_MSG_HASH,  hash)
        }
        val result = db.insertWithOnConflict(
            TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
        db.close()
        return result
    }

    /** Mark a set of hashes as synced=1 */
    fun markSynced(hashes: List<String>) {
        if (hashes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "UPDATE $TABLE_NAME SET $COLUMN_SYNCED = 1 WHERE $COLUMN_MSG_HASH = ?"
            )
            hashes.forEach { h ->
                stmt.bindString(1, h)
                stmt.executeUpdateDelete()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    /* ──────────────────────────────────────────────
       Queries
    ────────────────────────────────────────────── */

    /** Rows not yet confirmed by backend */
    fun getUnsyncedMessages(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COLUMN_SYNCED = 0",
            null, null, null,
            "$COLUMN_ID ASC"
        )
        cursor.use {
            while (it.moveToNext()) list.add(rowToMap(it))
        }
        db.close()
        return list
    }

    /** Return all known hashes so the backend can diff */
    fun getAllHashes(): List<String> {
        val hashes = mutableListOf<String>()
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT $COLUMN_MSG_HASH FROM $TABLE_NAME WHERE $COLUMN_MSG_HASH IS NOT NULL",
            null
        )
        cursor.use { while (it.moveToNext()) hashes.add(it.getString(0)) }
        db.close()
        return hashes
    }

    fun getAllMessages(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val db = readableDatabase
        val cursor = db.query(TABLE_NAME, null, null, null, null, null, "$COLUMN_ID DESC")
        cursor.use { while (it.moveToNext()) list.add(rowToMap(it)) }
        db.close()
        return list
    }

    fun getMessagesByNumberId(numberId: String): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val db = readableDatabase
        val cursor = db.query(
            TABLE_NAME, null,
            "$COLUMN_NUMBER_ID = ?", arrayOf(numberId),
            null, null, "$COLUMN_ID DESC"
        )
        cursor.use { while (it.moveToNext()) list.add(rowToMap(it)) }
        db.close()
        return list
    }

    fun deleteAllMessages() {
        val db = writableDatabase
        db.delete(TABLE_NAME, null, null)
        db.close()
    }

    /* ──────────────────────────────────────────────
       Target numbers
    ────────────────────────────────────────────── */

    fun addTargetNumber(numberId: String, label: String, phone: String): Boolean {
        return try {
            val db = writableDatabase
            val values = ContentValues().apply {
                put(COL_NUM_ID,    numberId)
                put(COL_NUM_LABEL, label)
                put(COL_NUM_PHONE, phone)
            }
            db.insertWithOnConflict(TABLE_NUMBERS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.close()
            true
        } catch (_: Exception) { false }
    }

    fun getAllTargetNumbers(): List<Map<String, String>> {
        val list = mutableListOf<Map<String, String>>()
        val db = readableDatabase
        val cursor = db.query(TABLE_NUMBERS, null, null, null, null, null, null)
        cursor.use {
            while (it.moveToNext()) {
                list.add(mapOf(
                    "number_id" to it.getString(it.getColumnIndexOrThrow(COL_NUM_ID)),
                    "label"     to it.getString(it.getColumnIndexOrThrow(COL_NUM_LABEL)),
                    "phone"     to it.getString(it.getColumnIndexOrThrow(COL_NUM_PHONE))
                ))
            }
        }
        db.close()
        return list
    }

    fun deleteTargetNumber(numberId: String) {
        val db = writableDatabase
        db.delete(TABLE_NUMBERS, "$COL_NUM_ID = ?", arrayOf(numberId))
        db.delete(TABLE_NAME,    "$COLUMN_NUMBER_ID = ?", arrayOf(numberId))
        db.close()
    }

    /* ──────────────────────────────────────────────
       Helpers
    ────────────────────────────────────────────── */

    private fun rowToMap(cursor: android.database.Cursor): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in 0 until cursor.columnCount) {
            map[cursor.getColumnName(i)] = cursor.getString(i) ?: ""
        }
        return map
    }


fun resetSyncedStatus(hashes: List<String>) {
    if (hashes.isEmpty()) return
    val db = writableDatabase
    db.beginTransaction()
    try {
        val stmt = db.compileStatement(
            "UPDATE $TABLE_NAME SET $COLUMN_SYNCED = 0 WHERE $COLUMN_MSG_HASH = ?"
        )
        hashes.forEach { h ->
            stmt.bindString(1, h)
            stmt.executeUpdateDelete()
            stmt.clearBindings()
        }
        db.setTransactionSuccessful()
        Log.d("SmsSync", "🔄 Reset ${hashes.size} message(s) to unsynced — will re-push next cycle")
    } finally {
        db.endTransaction()
        db.close()
    }
}



}