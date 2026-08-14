package com.intempt.core.queue

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * A minimal, append-only local record of every consent decision the SDK has attempted to
 * send, independent of whether that send actually reached the server.
 *
 * Consent events bypass the durable event queue (DeliveryMessages/EventDbAdapter) and are
 * POSTed directly by EventPoolManagerService.sendConsentEvent, because a consent decision
 * needs to reach the consent endpoint immediately rather than wait for the next batched
 * track-event flush. That direct post previously had no fallback: on a failed send, the
 * exception was logged and the consent decision was gone -- there was no local record that
 * the user had even made a decision. For a compliance-sensitive record ("did this user
 * consent, when, to what, until when") that is not acceptable: a support/compliance request
 * to prove a user's consent history must be answerable from local state, not from having
 * trusted that a single HTTP call, once, succeeded.
 *
 * This is intentionally not folded into EventDbAdapter's `events` table. That table's shape,
 * wire format, and cleanup cadence are tuned for the track-event delivery pipeline (a single
 * destination endpoint, a batched "track" envelope, a 5-day expiration meant for
 * already-delivered noise). Consent decisions need to stay recognisable as their own thing,
 * addressed to their own endpoint, and are not subject to the same short expiration --
 * mixing them into the track queue would either misroute them to the events endpoint or
 * require teaching that vendored, already-delicate substrate a second wire format and
 * destination. A small dedicated table on the same SQLite mechanism is the narrower change.
 */
internal class ConsentAuditLog(context: Context) {
    private val dbHelper = ConsentAuditDbHelper(context.applicationContext)

    /**
     * Records a consent decision. Safe to call regardless of whether the network send to the
     * consent endpoint succeeded, failed, or hasn't been attempted yet.
     */
    @Synchronized
    fun record(payload: JSONObject) {
        try {
            val db = dbHelper.writableDatabase
            val cv = ContentValues()
            cv.put(COLUMN_PAYLOAD, payload.toString())
            cv.put(COLUMN_CREATED_AT, System.currentTimeMillis())
            db.insert(TABLE_NAME, null, cv)
        } catch (e: Exception) {
            // Nothing further to fall back to: this *is* the fallback. The call site already
            // logs whether the network send failed.
        } finally {
            dbHelper.close()
        }
    }

    /** Returns every recorded consent decision, oldest first, for audit/compliance queries. */
    @Synchronized
    fun getAll(): List<JSONObject> {
        val results = mutableListOf<JSONObject>()
        try {
            val db = dbHelper.readableDatabase
            db.query(
                TABLE_NAME,
                arrayOf(COLUMN_PAYLOAD),
                null,
                null,
                null,
                null,
                "$COLUMN_ID ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results.add(JSONObject(cursor.getString(0)))
                }
            }
        } catch (e: Exception) {
            // Best-effort read; an unreadable audit log should not crash the caller.
        } finally {
            dbHelper.close()
        }
        return results
    }

    private class ConsentAuditDbHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_TABLE)
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            db.execSQL(CREATE_TABLE)
        }
    }

    companion object {
        private const val DATABASE_NAME = "intempt_consent_audit"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "consent_decisions"
        private const val COLUMN_ID = "_id"
        private const val COLUMN_PAYLOAD = "payload"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val CREATE_TABLE =
            "CREATE TABLE $TABLE_NAME (" +
                "$COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "$COLUMN_PAYLOAD TEXT NOT NULL, " +
                "$COLUMN_CREATED_AT INTEGER NOT NULL)"
    }
}
