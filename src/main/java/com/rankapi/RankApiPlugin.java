package com.rankapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class RankApiPlugin extends JavaPlugin {

    private HttpServer server;
    private final String SECRET = "CHANGE_THIS_SECRET";
    private final List<String> allowedRanks = List.of("vip", "mvp", "legend");

    @Override
    public void onEnable() {
        try {
            server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/grant-rank", this::handleGrantRank);
            server.start();
            getLogger().info("Rank API started on port 8080");
        } catch (IOException e) {
            getLogger().severe("Failed to start API: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleGrantRank(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

        if (!body.contains(SECRET)) {
            exchange.sendResponseHeaders(401, -1);
            return;
        }

        String player = extract(body, "player");
        String rank = extract(body, "rank").toLowerCase();

        if (!allowedRanks.contains(rank)) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        Bukkit.getScheduler().runTask(this, () ->
                Bukkit.dispatchCommand(
                        Bukkit.getConsoleSender(),
                        "lp user " + player + " parent add " + rank
                )
        );

        String response = "{\"success\":true}";
        exchange.sendResponseHeaders(200, response.length());
        exchange.getResponseBody().write(response.getBytes(StandardCharsets.UTF_8));
        exchange.close();
    }

    private String extract(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return "";

        start += search.length();
        int end = json.indexOf("\"", start);

        return json.substring(start, end);
    }
}
