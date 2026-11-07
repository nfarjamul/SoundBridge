package com.soundbridge.app.data;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Private app-owned read-only ContentProvider over the local SQLite alert cache.
 * Demonstrates ContentProvider URI matching, MIME types, and ContentResolver queries.
 * Configured with android:exported="false" in AndroidManifest.xml.
 */
public class SoundBridgeContentProvider extends ContentProvider {
    public static final String AUTHORITY = "com.soundbridge.app.provider";
    public static final Uri CONTENT_URI_ALERTS = Uri.parse("content://" + AUTHORITY + "/alerts");
    public static final Uri CONTENT_URI_SYSTEM_EVENTS = Uri.parse("content://" + AUTHORITY + "/system_events");

    private static final int MATCH_ALERTS = 1;
    private static final int MATCH_ALERT_ITEM = 2;
    private static final int MATCH_SYSTEM_EVENTS = 3;

    private static final UriMatcher URI_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        URI_MATCHER.addURI(AUTHORITY, "alerts", MATCH_ALERTS);
        URI_MATCHER.addURI(AUTHORITY, "alerts/*", MATCH_ALERT_ITEM);
        URI_MATCHER.addURI(AUTHORITY, "system_events", MATCH_SYSTEM_EVENTS);
    }

    private AlertDatabaseHelper dbHelper;

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            dbHelper = new AlertDatabaseHelper(getContext());
            return true;
        }
        return false;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder
    ) {
        if (dbHelper == null) return null;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int match = URI_MATCHER.match(uri);
        Cursor cursor;
        switch (match) {
            case MATCH_ALERTS:
                cursor = db.query(
                        AlertDatabaseHelper.TABLE_ALERTS,
                        projection,
                        selection,
                        selectionArgs,
                        null,
                        null,
                        sortOrder != null ? sortOrder : AlertDatabaseHelper.COL_TIMESTAMP + " DESC"
                );
                break;
            case MATCH_ALERT_ITEM:
                String eventId = uri.getLastPathSegment();
                cursor = db.query(
                        AlertDatabaseHelper.TABLE_ALERTS,
                        projection,
                        AlertDatabaseHelper.COL_EVENT_ID + " = ?",
                        new String[]{eventId != null ? eventId : ""},
                        null,
                        null,
                        null
                );
                break;
            case MATCH_SYSTEM_EVENTS:
                cursor = db.query(
                        AlertDatabaseHelper.TABLE_SYSTEM_EVENTS,
                        projection,
                        selection,
                        selectionArgs,
                        null,
                        null,
                        sortOrder != null ? sortOrder : AlertDatabaseHelper.COL_SYS_TIME + " DESC"
                );
                break;
            default:
                throw new IllegalArgumentException("Unsupported SoundBridge ContentProvider URI: " + uri);
        }
        if (cursor != null && getContext() != null) {
            cursor.setNotificationUri(getContext().getContentResolver(), uri);
        }
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        int match = URI_MATCHER.match(uri);
        switch (match) {
            case MATCH_ALERTS:
                return "vnd.android.cursor.dir/vnd.com.soundbridge.app.alert";
            case MATCH_ALERT_ITEM:
                return "vnd.android.cursor.item/vnd.com.soundbridge.app.alert";
            case MATCH_SYSTEM_EVENTS:
                return "vnd.android.cursor.dir/vnd.com.soundbridge.app.system_event";
            default:
                return null;
        }
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("SoundBridgeContentProvider is read-only; use SoundBridgeRepository for mutations.");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("SoundBridgeContentProvider is read-only; use SoundBridgeRepository for mutations.");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("SoundBridgeContentProvider is read-only; use SoundBridgeRepository for mutations.");
    }
}

