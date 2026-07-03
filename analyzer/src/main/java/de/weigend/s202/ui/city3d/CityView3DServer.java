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
package de.weigend.s202.ui.city3d;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

/**
 * Tiny loopback HTTP server that serves the built City3D bundle ({@code city3d/dist})
 * and a live {@code /city.json} produced from the currently analysed architecture.
 *
 * <p>Process-wide singleton, started on first use on an OS-assigned port. The
 * City3D web view fetches {@code ./city.json}; {@link #setCityJson(String)} swaps
 * the served model so re-opening the view shows the freshly analysed data.
 */
public final class CityView3DServer {

    private static CityView3DServer instance;

    private final HttpServer server;
    private final Path distDir;
    private final int port;
    private volatile byte[] cityJson = "{\"maxLevel\":0,\"districts\":[],\"buildings\":[],\"dependencies\":[]}"
            .getBytes(StandardCharsets.UTF_8);

    private CityView3DServer(Path distDir) throws IOException {
        this.distDir = distDir.toAbsolutePath().normalize();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newSingleThreadExecutor(r -> {
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
}
