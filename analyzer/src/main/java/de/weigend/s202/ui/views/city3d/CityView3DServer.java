/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.views.city3d;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Tiny loopback HTTP server that serves the built City3D bundle ({@code city3d/dist})
 * and a live {@code /city.json} produced from the currently analysed architecture.
 *
 * <p>Process-wide singleton, started on first use on an OS-assigned port. The
 * City3D web view fetches {@code ./city.json}; {@link #setCityJson(String)} swaps
 * the served model so re-opening the view shows the freshly analysed data.
 *
 * <p>It also carries a bidirectional selection channel between the host app and the
 * browser view:
 * <ul>
 *   <li><b>app → browser</b>: {@link #pushSelection(String)} broadcasts an fqn over a
 *       Server-Sent-Events stream ({@code /events}) that the page subscribes to;</li>
 *   <li><b>browser → app</b>: the page POSTs the clicked fqn to {@code /select}, which
 *       is delivered to the {@link #setSelectionListener(Consumer) registered listener}.</li>
 * </ul>
 */
public final class CityView3DServer {

    private static CityView3DServer instance;

    private final HttpServer server;
    private final Path distDir;
    private final int port;
    private volatile byte[] cityJson = "{\"maxLevel\":0,\"districts\":[],\"buildings\":[],\"dependencies\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private volatile String lastSelection;                 // replayed to newly-connected browsers
    private volatile Consumer<String> selectionListener;   // browser → app

    private CityView3DServer(Path distDir) throws IOException {
        this.distDir = distDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.createContext("/events", this::handleEvents);   // SSE: app -> browser
        server.createContext("/select", this::handleSelect);   // POST: browser -> app
        // A cached pool (not a single thread) so an open SSE stream doesn't block static requests.
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "city3d-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Starts the server (once) serving {@code distDir}, or returns the running instance. */
    public static synchronized CityView3DServer getOrStart(Path distDir) throws IOException {
        if (instance == null) {
            instance = new CityView3DServer(distDir);
        }
        return instance;
    }

    public void setCityJson(String json) {
        this.cityJson = json.getBytes(StandardCharsets.UTF_8);
    }

    public String url() {
        return "http://127.0.0.1:" + port + "/";
    }

    /** Register the handler invoked when the browser selects a node (fqn, or {@code null} to clear). */
    public void setSelectionListener(Consumer<String> listener) {
        this.selectionListener = listener;
    }

    /** Broadcast a selection (fqn, or {@code null}/empty to clear) to all connected browser views. */
    public void pushSelection(String fqn) {
        String data = fqn == null ? "" : fqn;
        this.lastSelection = fqn;
        for (SseClient client : sseClients) {
            if (!client.sendData(data)) {
                sseClients.remove(client);
            }
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if ("/".equals(path)) {
                path = "/index.html";
            }
            if ("/city.json".equals(path)) {
                send(ex, 200, "application/json", cityJson);
                return;
            }
            Path file = distDir.resolve(path.substring(1)).normalize();
            if (!file.startsWith(distDir) || !Files.isRegularFile(file)) {
                send(ex, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(ex, 200, mimeType(file), Files.readAllBytes(file));
        } catch (IOException e) {
            send(ex, 500, "text/plain", ("error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** SSE stream: keeps the connection open and pushes selections to the browser. */
    private void handleEvents(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);                    // 0 => open-ended (chunked) stream
        SseClient client = new SseClient(ex.getResponseBody());
        String current = lastSelection;
        if (current != null) {
            client.sendData(current);                      // replay current selection to a fresh view
        }
        sseClients.add(client);
        try {
            while (true) {
                Thread.sleep(15000);
                if (!client.ping()) {                      // heartbeat also detects a closed browser
                    break;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            sseClients.remove(client);
            client.close();
        }
    }

    /** Receives a selection made in the browser and forwards it to the registered listener. */
    private void handleSelect(HttpExchange ex) throws IOException {
        byte[] body = ex.getRequestBody().readAllBytes();
        String fqn = new String(body, StandardCharsets.UTF_8).trim();
        Consumer<String> listener = selectionListener;
        if (listener != null) {
            listener.accept(fqn.isEmpty() ? null : fqn);
        }
        send(ex, 204, "text/plain", new byte[0]);
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String mimeType(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    /** One connected browser's SSE stream; writes are serialised so app pushes and heartbeats don't interleave. */
    private static final class SseClient {
        private final OutputStream os;

        SseClient(OutputStream os) {
            this.os = os;
        }

        synchronized boolean sendData(String data) {
            return write("data: " + data + "\n\n");
        }

        synchronized boolean ping() {
            return write(": ping\n\n");                    // SSE comment line, ignored by EventSource
        }

        private boolean write(String s) {
            try {
                os.write(s.getBytes(StandardCharsets.UTF_8));
                os.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        void close() {
            try {
                os.close();
            } catch (IOException ignored) {
                // browser already gone
            }
        }
    }
}
