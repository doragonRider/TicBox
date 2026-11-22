package dev.ticbox.companion.healthmodule;

import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Root-only control; no broadcast receiver, network listener or command execution. */
final class AlertModuleServer {
    static final String SOCKET = "ticbox-notification-alerts-v1";
    private final LocalServerSocket listener;
    private final AlertPolicy policy;
    private final ThreadPoolExecutor clients = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), action -> daemon("TicNotifyClient", action), new ThreadPoolExecutor.AbortPolicy());

    AlertModuleServer(AlertPolicy policy) throws IOException { this.policy = policy; listener = new LocalServerSocket(SOCKET); }
    void start() { daemon("TicNotifySocket", this::accept).start(); }
    private void accept() {
        for (;;) {
            final LocalSocket socket;
            try { socket = listener.accept(); }
            catch (IOException closed) { clients.shutdown(); return; }
            try { clients.execute(() -> serve(socket)); }
            catch (RuntimeException busy) { try { socket.close(); } catch (IOException ignored) { } }
        }
    }
    private void serve(LocalSocket socket) {
        try (LocalSocket connection = socket) {
            Credentials peer = connection.getPeerCredentials();
            if (peer == null || !HealthModuleServer.authorized(peer.getUid())) return;
            connection.setSoTimeout(15_000);
            JSONObject result;
            try { result = policy.request(HealthModuleServer.readFrame(connection.getInputStream())); }
            catch (IOException invalid) { result = policy.result("invalid_frame"); }
            HealthModuleServer.writeFrame(connection.getOutputStream(), result);
        } catch (Exception ignored) { /* A control client must never crash SystemUI. */ }
    }
    private static Thread daemon(String name, Runnable action) { Thread thread = new Thread(action, name); thread.setDaemon(true); return thread; }
}
