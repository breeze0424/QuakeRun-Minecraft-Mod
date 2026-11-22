package com.cmcplugins.quakerun;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One aggregate connection; only validated mainland EEW reports leave this class. */
public final class WolfxFeed implements AutoCloseable {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "quakerun-wolfx");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<QuakeEvent> receiver;
    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private volatile WebSocket socket;
    private volatile long lastFrameMillis;
    private volatile int failures;
    private volatile boolean connected;

    public WolfxFeed(Consumer<QuakeEvent> receiver) { this.receiver = receiver; }
    public boolean connected() { return connected; }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        timer.scheduleAtFixedRate(() -> {
            WebSocket ws = socket;
            if (active.get() && connected && ws != null
                    && System.currentTimeMillis() - lastFrameMillis > 150_000) {
                ws.abort();
                connected = false;
                reconnect();
            }
        }, 60, 60, TimeUnit.SECONDS);
        connect();
    }

    private void connect() {
        if (!active.get()) return;
        http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("wss://ws-api.wolfx.jp/all_eew"), new Listener())
                .whenComplete((ws, error) -> {
                    if (error != null) reconnect();
                    else if (!active.get()) ws.abort();
                });
    }

    private void reconnect() {
        if (!active.get() || !reconnectScheduled.compareAndSet(false, true)) return;
        int n = Math.min(6, failures++);
        long seconds = Math.min(300, 5L << n);
        timer.schedule(() -> {
            reconnectScheduled.set(false);
            connect();
        }, seconds, TimeUnit.SECONDS);
    }

    @Override public void close() {
        active.set(false);
        connected = false;
        WebSocket ws = socket;
        if (ws != null) ws.abort();
        timer.shutdownNow();
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder frame = new StringBuilder();

        @Override public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            connected = true;
            lastFrameMillis = System.currentTimeMillis();
            webSocket.request(1);
            // The cached reply is accepted only if its ReportTime is still fresh.
            webSocket.sendText("query_cenceew", true)
                    .thenCompose(ws -> ws.sendText("query_sceew", true))
                    .thenCompose(ws -> ws.sendText("query_fjeew", true))
                    .thenCompose(ws -> ws.sendText("query_cqeew", true));
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastFrameMillis = System.currentTimeMillis();
            if (frame.length() + data.length() > 65536) {
                frame.setLength(0);
                webSocket.request(1);
                return null;
            }
            frame.append(data);
            if (last) {
                failures = 0;
                String body = frame.toString();
                frame.setLength(0);
                if (body.contains("\"type\":\"heartbeat\"") || body.contains("\"type\": \"heartbeat\"")) {
                    webSocket.sendText("ping", true);
                } else {
                    QuakeEvent event = QuakeEvent.parse(body, Instant.now());
                    if (event != null) receiver.accept(event);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                connected = false;
                socket = null;
                reconnect();
            }
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) {
                connected = false;
                socket = null;
                reconnect();
            }
        }
    }
}
