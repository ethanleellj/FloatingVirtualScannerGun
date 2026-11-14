package com.virtualscanner.gun;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫码历史（本地 SQLite，最多保留 500 条）。
 *
 * 联网为零：内容只存本机，卸载即消失。界面上也据此向用户说明。
 */
public final class ScanHistory extends SQLiteOpenHelper {

    private static final String DB = "vsg_history.db";
    private static final int VERSION = 1;
    private static final String TABLE = "history";
    private static final int MAX_ROWS = 500;

    /** 一条历史记录。 */
    public static final class Entry {
        public long id;
        public String content = "";
        public String format = "";
        public long ts;
        public boolean ok;
        public int channel;
        public String pkg = "";
    }

    private static ScanHistory instance;

    public static synchronized ScanHistory get(Context ctx) {
        if (instance == null) {
            instance = new ScanHistory(ctx.getApplicationContext());
        }
        return instance;
    }

    private ScanHistory(Context ctx) {
        super(ctx, DB, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "content TEXT NOT NULL,"
                + "format TEXT,"
                + "ts INTEGER NOT NULL,"
                + "ok INTEGER NOT NULL,"
                + "channel INTEGER NOT NULL,"
                + "pkg TEXT)");
        db.execSQL("CREATE INDEX idx_history_ts ON " + TABLE + " (ts DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public void add(String content, String format, boolean ok, int channel, String pkg) {
        try {
            ContentValues cv = new ContentValues();
            cv.put("content", content);
            cv.put("format", format == null ? "" : format);
            cv.put("ts", System.currentTimeMillis());
            cv.put("ok", ok ? 1 : 0);
            cv.put("channel", channel);
            cv.put("pkg", pkg == null ? "" : pkg);
            getWritableDatabase().insert(TABLE, null, cv);
            getWritableDatabase().execSQL(
                    "DELETE FROM " + TABLE + " WHERE id NOT IN "
                            + "(SELECT id FROM " + TABLE + " ORDER BY ts DESC LIMIT " + MAX_ROWS + ")");
        } catch (Throwable ignored) {
            // 历史只是辅助功能，写失败不能影响主链路
        }
    }

    /** 按关键词过滤，空关键词返回全部；时间倒序。 */
    public List<Entry> query(String keyword, int limit) {
        List<Entry> list = new ArrayList<>();
        String where = "";
        String[] args = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            where = "WHERE content LIKE ?";
            args = new String[]{"%" + keyword.trim() + "%"};
        }
        try (Cursor c = getReadableDatabase().query(TABLE, null, where, args,
                null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                list.add(read(c));
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    public void delete(long id) {
        try {
            getWritableDatabase().delete(TABLE, "id = ?", new String[]{String.valueOf(id)});
        } catch (Throwable ignored) {
        }
    }

    public void clear() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Throwable ignored) {
        }
    }

    private Entry read(Cursor c) {
        Entry e = new Entry();
        e.id = c.getLong(c.getColumnIndexOrThrow("id"));
        e.content = c.getString(c.getColumnIndexOrThrow("content"));
        e.format = c.getString(c.getColumnIndexOrThrow("format"));
        e.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        e.ok = c.getInt(c.getColumnIndexOrThrow("ok")) != 0;
        e.channel = c.getInt(c.getColumnIndexOrThrow("channel"));
        e.pkg = c.getString(c.getColumnIndexOrThrow("pkg"));
        return e;
    }
}
