package edu.learnsphere.ai.runtime;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * A stand-in llama-server, run as its own process by {@link LlamaServerManagerTest}. It takes the real
 * server's {@code --host}, {@code --port} and {@code --api-key} arguments, and one of its own first:
 *
 * <ul>
 *   <li>{@code ok}: answers {@code /health} with 503 for a moment (loading), then 200; streams a short chat
 *       reply to authorized requests;</li>
 *   <li>{@code crash}: exits with code 3 shortly after starting;</li>
 *   <li>{@code hang}: never becomes healthy.</li>
 * </ul>
 *
 * Uses only the JDK, so it runs from the test classes folder alone. Prints what it does, like the real server.
 */
public final class FakeLlamaServer {

    private FakeLlamaServer() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        List<String> rest = List.of(args).subList(1, args.length);
        int port = Integer.parseInt(value(rest, "--port"));
        String host = value(rest, "--host");
        String key = value(rest, "--api-key");
        System.out.println("fake llama-server " + mode + " starting on " + host + ":" + port + " with " + rest.size() + " args");
        if (System.getenv("HF_HUB_OFFLINE") != null) {
            System.out.println("HF_HUB_OFFLINE=" + System.getenv("HF_HUB_OFFLINE"));
        }
        if (mode.equals("crash")) {
            Thread.sleep(150);
            System.out.println("fake llama-server crashing");
            System.exit(3);
        }
        long readyAt = System.currentTimeMillis() + 300;
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/health", exchange -> {
            boolean ready = mode.equals("ok") && System.currentTimeMillis() >= readyAt;
            respond(exchange, ready ? 200 : 503, ready ? "{\"status\":\"ok\"}" : "{\"error\":{\"code\":503,\"message\":\"Loading model\"}}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (!("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{\"error\":{\"code\":401,\"message\":\"Invalid API Key\"}}");
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"Hi from " + port + "\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        System.out.println("fake llama-server listening");
        Thread.currentThread().join();
    }

    private static String value(List<String> args, String flag) {
        int at = args.indexOf(flag);
        if (at < 0 || at + 1 >= args.size()) {
            throw new IllegalArgumentException("missing " + flag);
        }
        return args.get(at + 1);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
