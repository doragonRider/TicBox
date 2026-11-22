package dev.ticbox.companion.healthmodule;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One bounded request/reply per root-authenticated abstract Unix connection. */
final class HealthModuleServer {
    static final String SOCKET_NAME = "ticbox-health-v1";
    static final int MAX_FRAME = 64 * 1024;
    private final LocalServerSocket listener;
    private final HealthMainDispatcher dispatcher;
    private final HealthProfile profile;
    private final HealthProviderAccess provider;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> daemon("TicHealthModuleClient", runnable), new ThreadPoolExecutor.AbortPolicy());

    HealthModuleServer(HealthMainDispatcher dispatcher, HealthProfile profile, HealthProviderAccess provider) throws IOException {
        this.dispatcher = dispatcher; this.profile = profile; this.provider = provider;
        listener = new LocalServerSocket(SOCKET_NAME);
    }

    void start() { daemon("TicHealthModuleSocket", this::accept).start(); }

    private void accept() {
        for (;;) {
            final LocalSocket socket;
            try { socket = listener.accept(); }
            catch (IOException closed) { clients.shutdown(); return; }
            try { clients.execute(() -> serve(socket)); }
            catch (RuntimeException busy) { close(socket); }
        }
    }

    private void serve(LocalSocket socket) {
        try (LocalSocket connection = socket) {
            Credentials credentials = connection.getPeerCredentials();
            if (credentials == null || !authorized(credentials.getUid())) return;
            connection.setSoTimeout(15_000);
            JSONObject result;
            try {
                JSONObject request = readFrame(connection.getInputStream());
                result = request.optInt("version") == 1 && "status".equals(request.optString("op"))
                        ? runtimeStatus(profile) : "provider".equals(request.optString("op")) ? provider.execute(request) : dispatcher.request(request);
            }
            catch (IOException invalid) { result = HealthController.unavailable(profile, "FAILED", "health_module_invalid_frame"); }
            writeFrame(connection.getOutputStream(), result);
        } catch (Exception ignored) { /* Never propagate a client failure into the host health application. */ }
    }

    static JSONObject runtimeStatus(HealthProfile profile) throws Exception {
        return new JSONObject().put("version", 1).put("buildId", ModuleBuildInfo.ID)
                .put("packageName", HealthProfile.PACKAGE).put("versionCode", profile.versionCode).put("apkSha256", profile.sha256);
    }

    static boolean authorized(int uid) { return uid == 0; }

    static JSONObject readFrame(InputStream source) throws IOException {
        DataInputStream input = new DataInputStream(source);
        int size = input.readInt();
        if (size <= 0 || size > MAX_FRAME) throw new IOException("health_module_invalid_frame");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            requireFlatObject(text);
            JSONTokener tokener = new JSONTokener(text);
            Object value = tokener.nextValue();
            if (!(value instanceof JSONObject) || tokener.nextClean() != 0) throw new IOException("health_module_invalid_frame");
            return (JSONObject) value;
        } catch (Exception malformed) { throw new IOException("health_module_invalid_frame"); }
    }

    static void writeFrame(OutputStream target, JSONObject result) throws IOException {
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_FRAME) throw new IOException("health_module_reply_too_large");
        DataOutputStream output = new DataOutputStream(target);
        output.writeInt(bytes.length); output.write(bytes); output.flush();
    }

    private static void requireFlatObject(String text) throws IOException {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == ']' || (c == '{' && ++depth > 1) || (c == '}' && --depth < 0))
                throw new IOException("health_module_invalid_frame");
        }
        if (quoted || escaped || depth != 0) throw new IOException("health_module_invalid_frame");
    }

    private static Thread daemon(String name, Runnable action) { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }
    private static void close(LocalSocket socket) { try { socket.close(); } catch (IOException ignored) { } }
}
