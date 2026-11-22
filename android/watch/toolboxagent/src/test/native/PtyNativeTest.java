package dev.ticbox.companion.toolboxagent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real host PTYs against the same C/JNI implementation shipped for Android. No emulator mocks. */
public final class PtyNativeTest {
    private static final ExecutorService workers = Executors.newCachedThreadPool();
    private static final byte[] buffer = new byte[65536];
    private static final AtomicInteger checks = new AtomicInteger();
    private static void check(boolean value, String reason) {
        checks.incrementAndGet();
        if (!value) throw new AssertionError(reason);
    }
    private static void write(long id, String value) throws IOException {
        PtyNative.write(id, value.getBytes(StandardCharsets.UTF_8));
    }
    private static String until(long id, String marker) throws Exception {
        StringBuilder received = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (System.nanoTime() < deadline) {
            int count = PtyNative.read(id, buffer, 100);
            if (count < 0) break;
            if (count > 0) received.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            if (received.indexOf(marker) >= 0) return received.toString();
        }
        throw new AssertionError("Missing marker " + marker + " in " + received);
    }
    private static long shell() throws Exception {
        long id = PtyNative.start("/bin/sh", "xterm-256color", 37, 91, 910, 740);
        write(id, "stty -echo; printf '\\n__READY__\\n'\n");
        String initial = until(id, "\r\n__READY__\r\n");
        check(!initial.contains("job control") && !initial.contains("can't find tty"), "shell has a controlling tty");
        return id;
    }
    private static <T> T bounded(Callable<T> action) throws Exception {
        return workers.submit(action).get(6, TimeUnit.SECONDS);
    }
    private static void terminalAndResizeAndExit() throws Exception {
        long id = shell();
        try {
            write(id, "test -t 0 && test -t 1 && test -t 2 && printf '__TTY__\\n'; printf '__CTTY__\\n' > /dev/tty; printf '__TERM:%s\\n' \"$TERM\"; stty size; printf '__ERR__\\n' >&2; printf '__DONE__\\n'\n");
            String output = until(id, "__DONE__\r\n");
            check(output.contains("__TTY__\r\n") && output.contains("__CTTY__\r\n"), "all three streams share a controlling terminal");
            check(output.contains("__TERM:xterm-256color\r\n"), "TERM reaches shell");
            check(output.contains("37 91\r\n"), "initial dimensions reach tty");
            check(output.contains("__ERR__\r\n"), "stderr is merged by terminal");
            PtyNative.resize(id, 52, 132, 1320, 1040);
            write(id, "stty size; printf '__SIZED__\\n'\n");
            check(until(id, "__SIZED__\r\n").contains("52 132\r\n"), "resize reaches tty");
            write(id, "exit 23\n");
            check(bounded(() -> PtyNative.awaitExit(id)) == 23, "actual exit code returned");
        } finally { PtyNative.close(id); }
        PtyNative.close(id);
        try { PtyNative.resize(id, 10, 20, 0, 0); throw new AssertionError("retired handle accepted"); }
        catch (IOException expected) { check(expected.getMessage().equals("terminal_closed"), "retired handle rejected"); }
    }
    private static void controlCharactersPreserveShell() throws Exception {
        long id = shell();
        try {
            write(id, "sh -c 'printf \"__CHILD_READY__\\n\"; exec sleep 30'\n");
            until(id, "__CHILD_READY__\r\n");
            PtyNative.write(id, new byte[]{3});
            write(id, "printf '__SHELL_SURVIVED__\\n'\n");
            until(id, "__SHELL_SURVIVED__\r\n");
            check(true, "Ctrl-C interrupts foreground command and leaves shell usable");
            PtyNative.write(id, new byte[]{4});
            check(bounded(() -> PtyNative.awaitExit(id)) == 0, "Ctrl-D at empty canonical input exits shell");
        } finally { PtyNative.close(id); }
    }
    private static void execFailureIsSynchronous() throws Exception {
        try {
            PtyNative.start("/nonexistent/ticbox-shell", "xterm", 24, 80, 0, 0);
            throw new AssertionError("missing executable reported ready");
        } catch (IOException expected) {
            check(expected.getMessage().equals("terminal_exec_failed"), "exec failure precedes ready");
        }
    }
    private static void hangupUnblocksIoAndWait() throws Exception {
        long id = shell();
        write(id, "sh -c 'printf \"__BLOCKED__\\n\"; exec sleep 30'\n");
        until(id, "__BLOCKED__\r\n");
        CountDownLatch started = new CountDownLatch(3);
        Future<?> reader = workers.submit(() -> {
            started.countDown();
            try { PtyNative.read(id, new byte[1024], 60000); }
            catch (IOException expected) { check(expected.getMessage().equals("terminal_closed"), "read cancelled by close"); }
        });
        Future<?> writer = workers.submit(() -> {
            started.countDown();
            byte[] content = new byte[65536]; Arrays.fill(content, (byte)'x');
            try { for (int i = 0; i < 128; i++) PtyNative.write(id, content); }
            catch (IOException expected) { check(expected.getMessage().equals("terminal_closed"), "write cancelled by close"); }
        });
        Future<?> waiter = workers.submit(() -> {
            started.countDown();
            try { PtyNative.awaitExit(id); }
            catch (IOException expected) { check(expected.getMessage().equals("terminal_closed"), "exit waiter cancelled by close"); }
        });
        check(started.await(2, TimeUnit.SECONDS), "concurrent operations started");
        PtyNative.close(id);
        reader.get(3, TimeUnit.SECONDS); writer.get(3, TimeUnit.SECONDS); waiter.get(3, TimeUnit.SECONDS);
        check(true, "close never waits on blocked terminal I/O");
    }
    private static void ignoredHangupIsNotForced() throws Exception {
        Path directory = Files.createTempDirectory("ticbox-pty-test-");
        Path file = directory.resolve("child.pid");
        long id = shell();
        ProcessHandle owned = null;
        try {
            write(id, "sh -c 'trap \"\" HUP; printf \"%s\\n\" $$ > " + file + "; printf \"__IGNORE_HUP__\\n\"; exec sleep 30'\n");
            until(id, "__IGNORE_HUP__\r\n");
            owned = ProcessHandle.of(Long.parseLong(Files.readString(file).trim())).orElseThrow();
            check(owned.isAlive(), "owned foreground child started");
            PtyNative.close(id);
            // Observe the owned child after close without extending its intended lifetime.
            Thread.sleep(150);
            check(owned.isAlive(), "HUP-ignoring child was not forcibly killed");
        } finally {
            PtyNative.close(id);
            if (owned != null && owned.isAlive()) owned.destroy();
            Files.deleteIfExists(file); Files.delete(directory);
        }
    }
    public static void main(String[] arguments) throws Exception {
        System.load(Path.of(arguments[0]).toAbsolutePath().toString());
        try {
            terminalAndResizeAndExit(); controlCharactersPreserveShell(); execFailureIsSynchronous();
            hangupUnblocksIoAndWait(); ignoredHangupIsNotForced();
            System.out.println("PTY native checks passed: " + checks.get());
        } finally { workers.shutdownNow(); }
    }
}
