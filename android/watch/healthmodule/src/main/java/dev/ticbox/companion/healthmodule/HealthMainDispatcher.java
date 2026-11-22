package dev.ticbox.companion.healthmodule;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Serializes complete operations without blocking the OEM main thread on its DB worker. */
final class HealthMainDispatcher {
    private static final int WAITING = 0, RUNNING = 1, CANCELLED = 2, DONE = 3;
    private final Handler main;
    private final HealthController controller;
    private final HealthProfile profile;
    private final long timeoutMillis;
    private final ArrayDeque<Task> queue = new ArrayDeque<>();
    private boolean busy;

    private static final class Task {
        final JSONObject request;
        final AtomicInteger state = new AtomicInteger(WAITING);
        final CountDownLatch completed = new CountDownLatch(1);
        volatile JSONObject result;
        Task(JSONObject request) { this.request = request; }
    }

    HealthMainDispatcher(Handler main, HealthController controller, HealthProfile profile) {
        this(main, controller, profile, 10_000L);
    }
    HealthMainDispatcher(Handler main, HealthController controller, HealthProfile profile, long timeoutMillis) {
        this.main = main; this.controller = controller; this.profile = profile; this.timeoutMillis = timeoutMillis;
    }

    JSONObject request(JSONObject request) {
        if (Looper.myLooper() == Looper.getMainLooper())
            return HealthController.unavailable(profile, "UNAVAILABLE", "health_module_socket_on_main_thread");
        Task task = new Task(request);
        if (!main.post(() -> { queue.add(task); drain(); }))
            return HealthController.unavailable(profile, "UNAVAILABLE", "health_module_main_unavailable");
        try {
            if (task.completed.await(timeoutMillis, TimeUnit.MILLISECONDS)) return task.result;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (task.state.compareAndSet(WAITING, CANCELLED)) {
            main.post(() -> queue.remove(task));
            return HealthController.unavailable(profile, "UNAVAILABLE", "health_module_main_timeout_before_start");
        }
        if (task.state.get() == DONE) return task.result;
        // Started operations finish their original side effects. Never replay,
        // roll back another process's preferences, or claim cancellation won.
        return HealthController.unavailable(profile, request.has("value") ? "STORED_UNVERIFIED" : "UNAVAILABLE", "health_module_operation_in_progress");
    }

    private void drain() {
        if (busy) return;
        Task task;
        do { task = queue.poll(); } while (task != null && !task.state.compareAndSet(WAITING, RUNNING));
        if (task == null) return;
        busy = true;
        final Task running = task;
        try {
            controller.execute(running.request, reply -> {
                Runnable finish = () -> {
                    if (running.state.get() != RUNNING) return;
                    running.result = reply;
                    running.state.set(DONE); running.completed.countDown(); busy = false; drain();
                };
                if (Looper.myLooper() == Looper.getMainLooper()) finish.run(); else main.post(finish);
            });
        } catch (Exception error) {
            running.result = HealthController.unavailable(profile, "STORED_UNVERIFIED", "health_module_operation_failed");
            running.state.set(DONE); running.completed.countDown(); busy = false; drain();
        } catch (LinkageError error) {
            running.result = HealthController.unavailable(profile, "STORED_UNVERIFIED", "health_module_oem_contract_changed");
            running.state.set(DONE); running.completed.countDown(); busy = false; drain();
        }
    }
}
