package org.ftc.shooter.tuner.ftc;

import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

public final class AutoTuneWebServer extends NanoHTTPD {
    public static final int PORT = 8081;
    public static final class Command {
        public final String action;
        public final JSONObject body;
        public final long submittedNanos = System.nanoTime();

        Command(String action, JSONObject body) {
            this.action = action;
            this.body = body;
        }
    }

    private final AssetManager assets;
    private final String key = UUID.randomUUID().toString();
    private final ArrayBlockingQueue<Command> commands = new ArrayBlockingQueue<>(4);
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private volatile long heartbeatNanos;
    private volatile String state = "{}";
    private volatile String exported = "";

    public AutoTuneWebServer(AssetManager assets) {
        super(PORT);
        this.assets = assets;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (session.getMethod() == Method.GET) {
                if (uri.equals("/api/state")) return reply(Response.Status.OK, "application/json", state);
                if (uri.equals("/api/export")) {
                    if (exported.isEmpty()) return reply(Response.Status.CONFLICT, "text/plain", "Complete loaded validation first");
                    Response response = reply(Response.Status.OK, "text/plain; charset=utf-8", exported);
                    response.addHeader("Content-Disposition", "attachment; filename=ShooterConstants.txt");
                    return response;
                }
                if (uri.equals("/api/results")) {
                    Response response = reply(Response.Status.OK, "application/json", state);
                    response.addHeader("Content-Disposition", "attachment; filename=ShooterAutoTune-results.json");
                    return response;
                }
                if (uri.equals("/") || uri.equals("/index.html")) {
                    return reply(Response.Status.OK, "text/html; charset=utf-8", asset("index.html").replace("SESSION_KEY", key));
                }
                if (uri.equals("/app.js")) return reply(Response.Status.OK, "application/javascript", asset("app.js"));
                if (uri.equals("/style.css")) return reply(Response.Status.OK, "text/css", asset("style.css"));
                return reply(Response.Status.NOT_FOUND, "text/plain", "Not found");
            }
            if (session.getMethod() != Method.POST) return reply(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST required");
            if (!key.equals(session.getHeaders().get("x-autotune-key"))) return reply(Response.Status.FORBIDDEN, "text/plain", "Reload the tuner page");
            String origin = session.getHeaders().get("origin");
            if (origin != null && !origin.equals("http://" + session.getHeaders().get("host"))) {
                return reply(Response.Status.FORBIDDEN, "text/plain", "Same-origin requests only");
            }
            if (uri.equals("/api/heartbeat")) {
                heartbeatNanos = System.nanoTime();
                return reply(Response.Status.OK, "application/json", "{}");
            }
            if (uri.equals("/api/stop")) {
                synchronized (commands) {
                    commands.clear();
                    stopRequested.set(true);
                }
                return reply(Response.Status.OK, "application/json", "{}");
            }
            if (!uri.matches("/api/(configure|direction|tune|loaded|test)")) return reply(Response.Status.NOT_FOUND, "text/plain", "Unknown action");
            int length = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
            if (length < 2 || length > 8192) return reply(Response.Status.BAD_REQUEST, "text/plain", "Expected JSON body under 8 KB");
            byte[] body = new byte[length];
            int read = 0;
            while (read < length) {
                int count = session.getInputStream().read(body, read, length - read);
                if (count < 0) throw new IOException("Incomplete request");
                read += count;
            }
            Command command = new Command(uri.substring(5), new JSONObject(new String(body, StandardCharsets.UTF_8)));
            synchronized (commands) {
                if (stopRequested.get() || !commands.offer(command)) return reply(Response.Status.CONFLICT, "text/plain", "Command pending; wait for telemetry");
            }
            return reply(Response.Status.ACCEPTED, "application/json", "{\"queued\":true}");
        } catch (Exception exception) {
            return reply(Response.Status.BAD_REQUEST, "text/plain", "Invalid request: " + exception.getMessage());
        }
    }

    private String asset(String name) throws IOException {
        try (InputStream input = assets.open("shooter-autotune/" + name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private Response reply(Response.Status status, String mime, String body) {
        Response response = newFixedLengthResponse(status, mime, body);
        response.addHeader("Cache-Control", "no-store");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'");
        return response;
    }

    public boolean browserAlive() {
        return heartbeatNanos != 0 && System.nanoTime() - heartbeatNanos < 1_500_000_000L;
    }

    public boolean takeStop() {
        synchronized (commands) {
            return stopRequested.getAndSet(false);
        }
    }

    public Command poll() { return commands.poll(); }
    public void clearCommands() { commands.clear(); }
    public void publish(String snapshot, String constants) { state = snapshot; exported = constants; }
}
