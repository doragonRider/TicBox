package dev.ticbox.companion.healthmodule;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SharedMemory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class HealthJournalAccessTest {
    private final Application context = RuntimeEnvironment.getApplication();

    @Test public void onlySingleReadOnlyStatementsAreAccepted() {
        for (String sql : new String[]{"SELECT 1", " select * from data_point where _id in (?,?)", "WITH m AS (SELECT 1) SELECT * FROM m",
                "PRAGMA user_version", "PRAGMA main.table_xinfo(\"data_point\")", "pragma index_list('data_point')", "PRAGMA main.index_xinfo(\"i\")"})
            assertTrue(sql, HealthJournalAccess.readOnly(sql));
        for (String sql : new String[]{"", "DELETE FROM data_point", "INSERT INTO x VALUES(1)", "ATTACH DATABASE '/x' AS x", "SELECT 1; DELETE FROM x",
                "PRAGMA user_version=3", "PRAGMA journal_mode", "PRAGMA main.journal_mode(WAL)", "PRAGMA table_xinfo_x(a)", "VACUUM", "UPDATE x SET a=1"})
            assertFalse(sql, HealthJournalAccess.readOnly(sql));
    }

    @Test public void onlyTheTokenGetsTheBinderAndEveryQueryNeedsItWhileCellsKeepTheirStorageTypes() throws Exception {
        File file = context.getDatabasePath(HealthJournalAccess.DATABASE);
        file.getParentFile().mkdirs();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY, v TEXT, r REAL, b BLOB, n TEXT)");
            db.execSQL("INSERT INTO data_point VALUES(9223372036854775807, 'a\"b', 1.0, x'0102', NULL)");
        }
        HealthJournalAccess access = new HealthJournalAccess(context, HealthProfile.V56);
        access.register(new Handler(Looper.getMainLooper()));
        String token = new String(Files.readAllBytes(new File(context.getNoBackupFilesDir(), HealthJournalAccess.TOKEN_FILE).toPath()),
                StandardCharsets.US_ASCII);
        assertTrue(token.matches("[0-9a-f]{32}"));

        assertNull(handOver("0".repeat(32), HealthJournalAccess.RESULT_REFUSED));
        IBinder binder = handOver(token, HealthJournalAccess.RESULT_GRANTED);
        assertNotNull(binder);

        JSONObject query = new JSONObject().put("version", 1).put("op", "journal_query").put("token", token)
                .put("sql", "SELECT _id,v,r,b,n FROM data_point WHERE _id=?").put("args", new JSONArray().put("9223372036854775807").toString());
        JSONObject reply = transact(binder, query);
        assertEquals("[\"_id\",\"v\",\"r\",\"b\",\"n\"]", reply.getJSONArray("columns").toString());
        JSONArray row = reply.getJSONArray("rows").getJSONArray(0);
        assertEquals("i9223372036854775807", row.getString(0));
        assertEquals("sa\"b", row.getString(1));
        assertEquals("f1.0", row.getString(2));
        assertEquals("bAQI=", row.getString(3));
        assertTrue(row.isNull(4));

        assertEquals("journal_unauthorized", transact(binder, new JSONObject(query.toString()).put("token", "0".repeat(32))).getString("error"));
        assertEquals("journal_sql_rejected", transact(binder, new JSONObject(query.toString()).put("sql", "DELETE FROM data_point")).getString("error"));
        assertEquals("journal_sql_rejected", transact(binder, new JSONObject(query.toString()).put("sql", "SELECT 1").put("args", "[1]")).getString("error"));
        JSONObject described = transact(binder, new JSONObject().put("version", 1).put("op", "journal_describe").put("token", token));
        assertEquals(HealthProfile.V56.versionName, described.getString("versionName"));
        assertEquals(HealthProfile.V56.sha256, described.getString("apkSha256"));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM data_point", null).getCount());
        }
    }

    @Test public void largeAnswersGoAsReadOnlySharedMemoryAndRowsNoCursorCanHoldAreNamedAsSuch() throws Exception {
        byte[] small = "{\"version\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] large = new byte[HealthJournalAccess.MAX_INLINE + 1];
        Arrays.fill(large, (byte) 'x');
        assertArrayEquals(small, answered(small, HealthJournalAccess.REPLY_INLINE));
        assertArrayEquals(large, answered(large, HealthJournalAccess.REPLY_SHARED));

        File file = context.getDatabasePath(HealthJournalAccess.DATABASE);
        file.getParentFile().mkdirs();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE data_point (_id INTEGER PRIMARY KEY, v TEXT)");
            db.execSQL("INSERT INTO data_point VALUES(1, ?)", new Object[]{"x".repeat(3 * 1024 * 1024)});
        }
        HealthJournalAccess access = new HealthJournalAccess(context, HealthProfile.V56);
        String token = new String(Files.readAllBytes(new File(context.getNoBackupFilesDir(), HealthJournalAccess.TOKEN_FILE).toPath()),
                StandardCharsets.US_ASCII);
        JSONObject query = new JSONObject().put("version", 1).put("op", "journal_query").put("token", token).put("sql", "SELECT * FROM data_point");
        assertEquals("journal_row_too_big", new JSONObject(new String(access.handle(query.toString().getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8)).getString("error"));
    }

    /** Reads the reply as Local Bridge does. */
    private static byte[] answered(byte[] answer, int expectedKind) throws Exception {
        Parcel reply = Parcel.obtain();
        try {
            HealthJournalAccess.answer(reply, answer);
            reply.setDataPosition(0);
            assertEquals(expectedKind, reply.readInt());
            if (expectedKind == HealthJournalAccess.REPLY_INLINE) return reply.createByteArray();
            try (SharedMemory memory = SharedMemory.CREATOR.createFromParcel(reply)) {
                ByteBuffer buffer = memory.mapReadOnly();
                try { byte[] read = new byte[buffer.remaining()]; buffer.get(read); return read; } finally { SharedMemory.unmap(buffer); }
            }
        } finally { reply.recycle(); }
    }

    private IBinder handOver(String token, int expectedCode) {
        AtomicInteger code = new AtomicInteger(-1);
        AtomicReference<IBinder> binder = new AtomicReference<>();
        context.sendOrderedBroadcast(new Intent(HealthJournalAccess.ACTION).putExtra(HealthJournalAccess.EXTRA_TOKEN, token), null,
                new BroadcastReceiver() {
                    @Override public void onReceive(Context ignored, Intent intent) {
                        code.set(getResultCode());
                        if (getResultExtras(false) != null) binder.set(getResultExtras(false).getBinder(HealthJournalAccess.EXTRA_BINDER));
                    }
                }, new Handler(Looper.getMainLooper()), 0, null, null);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(expectedCode, code.get());
        return binder.get();
    }

    private static JSONObject transact(IBinder binder, JSONObject request) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeByteArray(request.toString().getBytes(StandardCharsets.UTF_8));
            assertTrue(binder.transact(HealthJournalAccess.TRANSACTION, data, reply, 0));
            assertEquals(HealthJournalAccess.REPLY_INLINE, reply.readInt());
            return new JSONObject(new String(reply.createByteArray(), StandardCharsets.UTF_8));
        } finally { data.recycle(); reply.recycle(); }
    }
}
