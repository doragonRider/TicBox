package dev.ticbox.companion.healthmodule;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteBlobTooBigException;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SharedMemory;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Read-only queries on the official health database for TicBox's change-log reads, so a sync needs no root helper process
 * for them. Apps can't connect to another app's socket, so this goes over Binder: an ordered broadcast that carries the
 * token gets this Binder back, and every query carries the token again. The token is random per start and sits in this
 * app's private files, so only root (and this app) can read it. Every check of the schema and the log stays with the
 * caller, which runs the same code on these answers as its root helper runs on the file.
 */
final class HealthJournalAccess {
    static final String ACTION = "dev.ticbox.companion.healthmodule.JOURNAL";
    static final String EXTRA_TOKEN = "token";
    static final String EXTRA_BINDER = "journal";
    static final int RESULT_GRANTED = 1, RESULT_REFUSED = 2;
    static final int TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    static final String TOKEN_FILE = "ticbox-health-token";
    static final String DATABASE = "health_common.db";
    /** A process has about 1 MB for all its Binder transactions at once, so a larger answer goes as shared memory. */
    static final int MAX_INLINE = 256 * 1024;
    /** The most one answer may take, which this app holds only while it answers; the caller splits a larger one. */
    static final int MAX_REPLY = 4 * 1024 * 1024;
    static final int REPLY_INLINE = 0, REPLY_SHARED = 1;
    static final int MAX_REQUEST = 64 * 1024;
    private static final int MAX_SQL = 16 * 1024;
    private static final long QUERY_MILLIS = 5_000, IDLE_MILLIS = 30_000;
    private static final Set<String> READ_PRAGMAS = Set.of("USER_VERSION", "TABLE_XINFO", "FOREIGN_KEY_LIST", "INDEX_LIST", "INDEX_XINFO");
    private static final ScheduledExecutorService TIMERS = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "TicHealthJournalTimer"); thread.setDaemon(true); return thread;
    });

    private final Context context;
    private final HealthProfile profile;
    private final byte[] token;
    private SQLiteDatabase database;
    private long openedDevice, openedInode;
    private int users;
    private ScheduledFuture<?> idle;

    final Binder binder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != TRANSACTION) return super.onTransact(code, data, reply, flags);
            byte[] answer;
            // A failure here must never reach the official app's Binder thread as anything but an answer.
            try { answer = handle(data.createByteArray()); } catch (Exception failed) { answer = bytes(error("journal_query_failed")); }
            if (reply != null) answer(reply, answer);
            return true;
        }
    };

    final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            try {
                if (!isOrderedBroadcast()) return;
                if (!authorized(intent.getStringExtra(EXTRA_TOKEN))) { setResultCode(RESULT_REFUSED); return; }
                Bundle extras = new Bundle(); extras.putBinder(EXTRA_BINDER, binder);
                setResult(RESULT_GRANTED, null, extras);
            } catch (Exception malformed) { /* Another app's extras can fail to unparcel; that must not reach the host. */ }
        }
    };

    HealthJournalAccess(Context context, HealthProfile profile) throws Exception {
        this.context = context; this.profile = profile;
        byte[] random = new byte[16]; new SecureRandom().nextBytes(random);
        token = hex(random).getBytes(StandardCharsets.US_ASCII);
        File directory = context.getNoBackupFilesDir();
        File temporary = new File(directory, TOKEN_FILE + ".tmp"), target = new File(directory, TOKEN_FILE);
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(token); output.getFD().sync(); }
        // Owner only: this application and root can read it, no other application can.
        if (!temporary.setReadable(false, false) || !temporary.setReadable(true, true) || !temporary.setWritable(false, false) ||
                !temporary.setWritable(true, true) || !temporary.renameTo(target)) throw new IOException("health_journal_token_unavailable");
    }

    /** Receives the broadcasts on the given thread, never on the official app's main thread. */
    void register(Handler handler) {
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(receiver, filter, null, handler);
    }

    boolean authorized(String value) {
        return value != null && MessageDigest.isEqual(token, value.getBytes(StandardCharsets.US_ASCII));
    }

    byte[] handle(byte[] request) throws Exception {
        if (request == null || request.length == 0 || request.length > MAX_REQUEST) return bytes(error("journal_request_invalid"));
        JSONObject parsed = new JSONObject(new String(request, StandardCharsets.UTF_8));
        if (!authorized(parsed.optString("token", null))) return bytes(error("journal_unauthorized"));
        byte[] answer = bytes(execute(parsed));
        return answer.length <= MAX_REPLY ? answer : bytes(error("journal_reply_too_large"));
    }

    JSONObject execute(JSONObject request) {
        try {
            if (request.optInt("version") != 1) return error("journal_version_unsupported");
            switch (request.optString("op")) {
                case "journal_describe": return describe();
                case "journal_query": return query(request.optString("sql"), request.optString("args", "[]"));
                default: return error("journal_op_unsupported");
            }
        } catch (android.os.OperationCanceledException slow) { return error("journal_query_timeout"); }
        // The caller reads such a row on its own, without its largest cells.
        catch (SQLiteBlobTooBigException tooLarge) { return error("journal_row_too_big"); }
        catch (Exception failed) { return error("journal_query_failed"); }
    }

    /** The same identity the root helper reads for the file: the official app's version and the file's device and inode. */
    private JSONObject describe() throws Exception {
        StructStat stat = Os.stat(context.getDatabasePath(DATABASE).getPath());
        return new JSONObject().put("version", 1).put("journal", 1).put("packageName", HealthProfile.PACKAGE)
                .put("versionCode", profile.versionCode).put("versionName", profile.versionName).put("apkSha256", profile.sha256)
                .put("device", stat.st_dev).put("inode", stat.st_ino);
    }

    private JSONObject query(String sql, String argsText) throws Exception {
        if (!readOnly(sql)) return error("journal_sql_rejected");
        JSONArray parsed = new JSONArray(argsText);
        String[] args = new String[parsed.length()];
        for (int i = 0; i < args.length; i++) {
            Object value = parsed.get(i);
            if (!(value instanceof String)) return error("journal_sql_rejected");
            args[i] = (String) value;
        }
        SQLiteDatabase db = acquire();
        CancellationSignal cancel = new CancellationSignal();
        ScheduledFuture<?> deadline = TIMERS.schedule(cancel::cancel, QUERY_MILLIS, TimeUnit.MILLISECONDS);
        try (Cursor cursor = db.rawQuery(sql, args, cancel)) {
            JSONArray columns = new JSONArray();
            for (String name : cursor.getColumnNames()) columns.put(name);
            JSONArray rows = new JSONArray(); long size = 0;
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    String cell = cell(cursor, i);
                    row.put(cell == null ? JSONObject.NULL : cell);
                    size += cell == null ? 5 : cell.length() + 3;
                }
                // Characters never outnumber the UTF-8 bytes, so this only stops answers that can't fit anyway.
                if (size > MAX_REPLY) return error("journal_reply_too_large");
                rows.put(row);
            }
            return new JSONObject().put("version", 1).put("columns", columns).put("rows", rows);
        } finally { deadline.cancel(false); release(); }
    }

    /** One read-only connection, reopened when the file was replaced and closed after a while without queries. */
    private synchronized SQLiteDatabase acquire() throws Exception {
        if (idle != null) { idle.cancel(false); idle = null; }
        String path = context.getDatabasePath(DATABASE).getPath();
        StructStat stat = Os.stat(path);
        if (database != null && (stat.st_dev != openedDevice || stat.st_ino != openedInode)) { database.close(); database = null; }
        if (database == null) {
            database = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            openedDevice = stat.st_dev; openedInode = stat.st_ino;
        }
        users++;
        return database;
    }

    private synchronized void release() {
        if (--users == 0) idle = TIMERS.schedule(this::closeIdle, IDLE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private synchronized void closeIdle() {
        if (users == 0 && database != null) { database.close(); database = null; }
    }

    /**
     * The answer itself when it is small, otherwise read-only shared memory holding it. Shared memory that can't be made
     * leaves an error in its place, so the caller reads this one the slow way.
     */
    static void answer(Parcel reply, byte[] answer) {
        if (answer.length > MAX_INLINE) {
            try (SharedMemory memory = SharedMemory.create("TicHealthJournal", answer.length)) {
                ByteBuffer buffer = memory.mapReadWrite();
                try { buffer.put(answer); } finally { SharedMemory.unmap(buffer); }
                if (!memory.setProtect(OsConstants.PROT_READ)) throw new IOException("journal_reply_unprotected");
                // The parcel keeps its own copy of the descriptor.
                reply.writeInt(REPLY_SHARED); memory.writeToParcel(reply, 0);
                return;
            } catch (Exception failed) { answer = bytes(error("journal_reply_unavailable")); }
        }
        reply.writeInt(REPLY_INLINE); reply.writeByteArray(answer);
    }

    /** Each cell with its storage type, so the reader rebuilds exactly what a cursor on the file would give. */
    static String cell(Cursor cursor, int index) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_NULL: return null;
            case Cursor.FIELD_TYPE_INTEGER: return "i" + cursor.getLong(index);
            case Cursor.FIELD_TYPE_FLOAT: return "f" + cursor.getDouble(index);
            case Cursor.FIELD_TYPE_BLOB: return "b" + Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP);
            default: { String text = cursor.getString(index); return text == null ? null : "s" + text; }
        }
    }

    /**
     * One statement that only reads: SELECT, WITH, or a PRAGMA the schema checks use, without a value. The connection is
     * read-only as well, so a statement that got past this still couldn't write.
     */
    static boolean readOnly(String sql) {
        if (sql == null || sql.isEmpty() || sql.length() > MAX_SQL || sql.indexOf(';') >= 0) return false;
        String text = sql.trim().toUpperCase(Locale.ROOT);
        if (text.startsWith("SELECT ") || text.startsWith("WITH ")) return true;
        if (!text.startsWith("PRAGMA ") || text.indexOf('=') >= 0) return false;
        String name = text.substring(7).trim();
        if (name.startsWith("MAIN.")) name = name.substring(5);
        int end = 0;
        while (end < name.length() && (Character.isLetter(name.charAt(end)) || name.charAt(end) == '_')) end++;
        String rest = name.substring(end).trim();
        return READ_PRAGMAS.contains(name.substring(0, end)) && (rest.isEmpty() || rest.startsWith("("));
    }

    private static JSONObject error(String code) {
        try { return new JSONObject().put("version", 1).put("error", code); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] bytes(JSONObject value) { return value.toString().getBytes(StandardCharsets.UTF_8); }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) { text.append(Character.forDigit((value & 255) >>> 4, 16)); text.append(Character.forDigit(value & 15, 16)); }
        return text.toString();
    }
}
