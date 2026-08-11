/*
 * Adapted from the Mixpanel Android SDK — https://github.com/mixpanel/mixpanel-android
 * Copyright 2022 Mixpanel, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Modifications (c) 2026 Intempt Technologies, licensed under the Apache License 2.0:
 *   - renamed from MPDbAdapter; package moved to com.intempt.core.queue
 *   - reduced the four-table schema to a single events table
 *   - removed the multi-token column and multi-instance registry
 *   - removed people/group/anonymous-profile handling and v4-v7 migrations
 */
package com.intempt.core.queue;

import java.io.File;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;


/**
 * SQLite database adapter for the Intempt delivery queue.
 *
 * <p>Not thread-safe. Instances of this class should only be used
 * by a single thread.
 *
 */
/* package */ class EventDbAdapter {
    private static final String LOGTAG = "Intempt.Database";

    public enum Table {
        EVENTS ("events");

        Table(String name) {
            mTableName = name;
        }

        public String getName() {
            return mTableName;
        }

        private final String mTableName;
    }

    public static final String KEY_DATA = "data";
    public static final String KEY_CREATED_AT = "created_at";
    public static final String KEY_AUTOMATIC_DATA = "automatic_data";

    public static final int ID_COLUMN_INDEX = 0;
    public static final int DATA_COLUMN_INDEX = 1;
    public static final int CREATED_AT_COLUMN_INDEX = 2;
    public static final int AUTOMATIC_DATA_COLUMN_INDEX = 3;

    public static final int DB_UPDATE_ERROR = -1;
    public static final int DB_OUT_OF_MEMORY_ERROR = -2;
    public static final int DB_UNDEFINED_CODE = -3;

    private static final String DATABASE_NAME = "intempt_events";
    private static final int MIN_DB_VERSION = 1;

    // If you increment DATABASE_VERSION, don't forget to define migration
    private static final int DATABASE_VERSION = 1; // current database version
    private static final int MAX_DB_VERSION = 1; // Max database version onUpdate can migrate to.


    private static final String CREATE_EVENTS_TABLE =
       "CREATE TABLE " + Table.EVENTS.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        KEY_DATA + " STRING NOT NULL, " +
        KEY_CREATED_AT + " INTEGER NOT NULL, " +
        KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0)";
    private static final String EVENTS_TIME_INDEX =
        "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.EVENTS.getName() +
        " (" + KEY_CREATED_AT + ");";

    private final QueueDatabaseHelper mDb;

    private static class QueueDatabaseHelper extends SQLiteOpenHelper {
        QueueDatabaseHelper(Context context, String dbName, QueueConfig config) {
            super(context, dbName, null, DATABASE_VERSION);
            mDatabaseFile = context.getDatabasePath(dbName);
            mIsNewDatabase = !mDatabaseFile.exists();
            mConfig = config;
        }

        /**
         * Returns true if this is a newly created database (the database file did not exist
         * before this helper was initialized).
         */
        public boolean isNewDatabase() {
            return mIsNewDatabase;
        }

        /**
         * Completely deletes the DB file from the file system.
         */
        public void deleteDatabase() {
            close();
            mDatabaseFile.delete();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            QueueLog.v(LOGTAG, "Creating a new Intempt events DB");

            db.execSQL(CREATE_EVENTS_TABLE);
            db.execSQL(EVENTS_TIME_INDEX);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            QueueLog.v(LOGTAG, "Upgrading app, replacing Intempt events DB");

            // Schema starts at version 1, so there is no historical migration path to
            // honour. Any version change drops and recreates: the queue holds only
            // in-flight events, never a system of record, so discarding is acceptable
            // and strictly safer than migrating a shape we have never shipped.
            db.execSQL("DROP TABLE IF EXISTS " + Table.EVENTS.getName());
            db.execSQL(CREATE_EVENTS_TABLE);
            db.execSQL(EVENTS_TIME_INDEX);
        }

        public boolean aboveMemThreshold() {
            if (mDatabaseFile.exists()) {
                return mDatabaseFile.length() > Math.max(mDatabaseFile.getUsableSpace(), mConfig.getMinimumDatabaseLimit()) ||
                        mDatabaseFile.length() > mConfig.getMaximumDatabaseLimit();
            }
            return false;
        }

        private final File mDatabaseFile;
        private final boolean mIsNewDatabase;
        private final QueueConfig mConfig;
    }

    // Mixpanel's multi-instance registry (a static Map keyed by instance name) is not
    // inherited: Intempt runs one SDK instance per app, so Dagger owns the single
    // instance and there is nothing to key on.
    public EventDbAdapter(Context context, QueueConfig config) {
        this(context, DATABASE_NAME, config);
    }

    public EventDbAdapter(Context context, String dbName, QueueConfig config) {
        mDb = new QueueDatabaseHelper(context, dbName, config);
    }

    /**
     * Adds a JSON string representing an event with properties or a person record
     * to the SQLiteDatabase.
     * @param j the JSON to record
     * @param table the table to insert into, always Table.EVENTS
     * @return the number of rows in the table, or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
     * on failure
     */
    public int addJSON(JSONObject j, Table table) {
        // we are aware of the race condition here, but what can we do..?
        if (this.aboveMemThreshold()) {
            QueueLog.e(LOGTAG, "There is not enough space left on the device or " +
                    "the data was over the maximum size limit so it was discarded");
            return DB_OUT_OF_MEMORY_ERROR;
        }

        final String tableName = table.getName();

        Cursor c = null;
        int count = DB_UPDATE_ERROR;

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();

            final ContentValues cv = new ContentValues();
            cv.put(KEY_DATA, j.toString());
            cv.put(KEY_CREATED_AT, System.currentTimeMillis());
            db.insert(tableName, null, cv);

            c = db.rawQuery("SELECT COUNT(*) FROM " + tableName, null);
            c.moveToFirst();
            count = c.getInt(0);
        } catch (final SQLiteException e) {
            QueueLog.e(LOGTAG, "Could not add event data to table");

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            if (c != null) {
                c.close();
                c = null;
            }
            mDb.deleteDatabase();
        } catch (final OutOfMemoryError e) {
            QueueLog.e(LOGTAG, "Out of memory when adding event data to table");
        } finally {
            if (c != null) {
                c.close();
            }
            mDb.close();
        }
        return count;
    }

    /**
     * Removes events with an _id <= last_id from table
     * @param last_id the last id to delete
     * @param table the table to remove events from, always Table.EVENTS
     */
    public void cleanupEvents(String last_id, Table table) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            StringBuffer deleteQuery = new StringBuffer("_id <= " + last_id);

            db.delete(tableName, deleteQuery.toString(), null);
        } catch (final SQLiteException e) {
            QueueLog.e(LOGTAG, "Could not clean sent event records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } catch (final Exception e) {
            QueueLog.e(LOGTAG, "Unknown exception. Could not clean sent event records from " + tableName + ".Re-initializing database.", e);
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes events before time.
     * @param time the unix epoch in milliseconds to remove events before
     * @param table the table to remove events from, always Table.EVENTS
     */
    public void cleanupEvents(long time, Table table) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(tableName, KEY_CREATED_AT + " <= " + time, null);
        } catch (final SQLiteException e) {
            QueueLog.e(LOGTAG, "Could not clean timed-out event records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes all events from the table.
     * @param table the table to remove events from, always Table.EVENTS
     */
    public void cleanupAllEvents(Table table) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(tableName, null, null);
        } catch (final SQLiteException e) {
            QueueLog.e(LOGTAG, "Could not clean timed-out event records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    public void deleteDB() {
        mDb.deleteDatabase();
    }

    /**
     * Returns the data string to send to Intempt and the maximum ID of the row that
     * we're sending, so we know what rows to delete when a track request was successful.
     *
     * @param table the table to read the JSON from, always Table.EVENTS
     * @return String array containing the maximum ID, the data string
     * representing the events (or null if none could be successfully retrieved) and the total
     * current number of events in the queue.
     */
    public String[] generateDataString(Table table) {
        Cursor c = null;
        Cursor queueCountCursor = null;
        String data = null;
        String last_id = null;
        String queueCount = null;
        final String tableName = table.getName();
        final SQLiteDatabase db = mDb.getReadableDatabase();

        try {
            StringBuffer rawDataQuery = new StringBuffer("SELECT * FROM " + tableName + " ");
            StringBuffer queueCountQuery = new StringBuffer("SELECT COUNT(*) FROM " + tableName + " ");


            rawDataQuery.append("ORDER BY " + KEY_CREATED_AT + " ASC LIMIT " + Integer.toString(mDb.mConfig.getFlushBatchSize()));
            c = db.rawQuery(rawDataQuery.toString(), null);

            queueCountCursor = db.rawQuery(queueCountQuery.toString(), null);
            queueCountCursor.moveToFirst();
            queueCount = String.valueOf(queueCountCursor.getInt(0));

            final JSONArray arr = new JSONArray();

            while (c.moveToNext()) {
                if (c.isLast()) {
                    final int idColumnIndex = c.getColumnIndex("_id") >= 0 ? c.getColumnIndex("_id") : ID_COLUMN_INDEX;
                    last_id = c.getString(idColumnIndex);
                }
                try {
                    final int dataColumnIndex = c.getColumnIndex(KEY_DATA) >= 0 ? c.getColumnIndex(KEY_DATA) : DATA_COLUMN_INDEX;
                    final JSONObject j = new JSONObject(c.getString(dataColumnIndex));
                    arr.put(j);
                } catch (final JSONException e) {
                    // Ignore this object
                }
            }

            if (arr.length() > 0) {
                data = arr.toString();
            }
        } catch (final SQLiteException e) {
            QueueLog.e(LOGTAG, "Could not pull event records out of database " + tableName + ". Waiting to send.", e);

            // We'll dump the DB on write failures, but with reads we can
            // let things ride in hopes the issue clears up.
            // (A bit more likely, since we're opening the DB for read and not write.)
            // A corrupted or disk-full DB will be cleaned up on the next write or clear call.
            last_id = null;
            data = null;
        } finally {
            mDb.close();
            if (c != null) {
                c.close();
            }
            if (queueCountCursor != null) {
                queueCountCursor.close();
            }
        }

        if (last_id != null && data != null) {
            final String[] ret = {last_id, data, queueCount};
            return ret;
        }
        return null;
    }

    /**
     * Returns true if this is a newly created database (the database file did not exist
     * before this adapter was initialized). Used to detect first app launch.
     */
    public boolean isNewDatabase() {
        return mDb.isNewDatabase();
    }

    /* For testing use only, do not call from in production code */
    protected boolean aboveMemThreshold() {
        return mDb.aboveMemThreshold();
    }
}
