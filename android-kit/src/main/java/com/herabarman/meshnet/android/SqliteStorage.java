package com.herabarman.meshnet.android;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.herabarman.meshnet.core.StorageAdapter;
import java.util.ArrayList;
import java.util.List;

/**
 * StorageAdapter over a private SQLite file ("meshkit.db"), separate from your app database.
 * Holds: the identity key, pinned peer keys, the outbox, the relay queue, id and zone caches.
 * SQLiteDatabase serialises access, so it is safe to call from any thread.
 */
public final class SqliteStorage extends SQLiteOpenHelper implements StorageAdapter {
    public SqliteStorage(Context ctx) {
        super(ctx.getApplicationContext(), "meshkit.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v BLOB NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) { }

    @Override
    public void put(String key, byte[] value) {
        ContentValues cv = new ContentValues();
        cv.put("k", key);
        cv.put("v", value);
        getWritableDatabase().insertWithOnConflict("kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override
    public byte[] get(String key) {
        Cursor c = getReadableDatabase().query("kv", new String[]{"v"}, "k = ?", new String[]{key}, null, null, null);
        try {
            return c.moveToFirst() ? c.getBlob(0) : null;
        } finally {
            c.close();
        }
    }

    @Override
    public void remove(String key) {
        getWritableDatabase().delete("kv", "k = ?", new String[]{key});
    }

    @Override
    public List<String> keys(String prefix) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("kv", new String[]{"k"}, "k >= ? AND k < ?",
                new String[]{prefix, prefix + "\uffff"}, null, null, null);
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }
}
