package com.rankapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class RankApiPlugin extends JavaPlugin implements Listener {

    private static final String AUTORUN_FILE_NAME = "autorun";

    private String baseUrl;
    private int intervalSeconds;
    private boolean debug;
    private String apiSecret;
    private List<String> allowedRanks;
    private boolean autoUpdateEnabled;
    private String updateUrl;
    private String pluginVersion;
    private String pluginFileName;
    private HttpClient client;
    private boolean firstAutoPoll = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        getServer().getPluginManager().registerEvents(this, this);
        checkAutoUpdate();
        printStartupBanner();
        scheduleAutoPolling();
    }

    @Override
    public void onDisable() {
        logInfo("RankAPI plugin disabled.");
    }

    private void loadConfig() {
        this.baseUrl = getConfig().getString("api.base-url", "");
        if (this.baseUrl.endsWith("/")) this.baseUrl = this.baseUrl.substring(0, this.baseUrl.length() - 1);
        this.intervalSeconds = getConfig().getInt("api.interval-seconds", 60);
        this.debug = getConfig().getBoolean("debug", true);
        this.apiSecret = getConfig().getString("api.secret", "");
        this.allowedRanks = getConfig().getStringList("allowed-ranks");
        if (this.allowedRanks == null || this.allowedRanks.isEmpty()) {
            this.allowedRanks = List.of("vip", "mvp", "legend");
        }
        this.autoUpdateEnabled = getConfig().getBoolean("auto-update.enabled", false);
        this.updateUrl = getConfig().getString("auto-update.url", "");
        this.pluginVersion = getDescription().getVersion();
        this.pluginFileName = getFile() != null ? getFile().getName() : "RankApi.jar";
    }

    private void logInfo(String message) {
        getLogger().info(colorConsole(message));
    }

    private void logDebug(String message) {
        if (debug) {
            getLogger().info(colorConsole("DEBUG: " + message));
        }
    }

    private void logWarn(String message) {
        getLogger().info(colorConsole("WARNING: " + message));
    }

    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private void logError(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, colorConsole("ERROR: " + message), throwable);
    }

    private void savePluginLog(String title, String content) {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            Path logFile = getDataFolder().toPath().resolve("rankapi.log");
            String entry = String.format("%s [%s] %s%n%s%n----%n",
                    LocalDateTime.now().format(LOG_TIMESTAMP), title, baseUrl != null ? baseUrl : "<unset>", content);
            Files.writeString(logFile, entry, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ex) {
            logWarn("Unable to write rankapi.log: " + ex.getMessage());
        }
    }

    private String formatMessage(String message) {
        return ChatColor.GREEN + "[RankAPI] " + ChatColor.WHITE + message;
    }

    private void sendFormattedMessage(CommandSender sender, String message) {
        sender.sendMessage(formatMessage(message));
    }

    private void scheduleAutoPolling() {
        long intervalTicks = Math.max(1, intervalSeconds) * 20L;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (firstAutoPoll) {
                    logInfo("Auto-fetch initialized. First poll running.");
                    firstAutoPoll = false;
                    pollOnce(true);
                } else {
                    pollOnce(false);
                }
            }
        }.runTaskTimer(this, 0L, intervalTicks);
    }

    private void printStartupBanner() {
        String banner = "\n"
                + "\u001B[32m========================================\n"
                + "   RankAPI | Powered by LuckyDev\n"
                + "   Green Gateway for rank execution\n"
                + "   Auto-fetch every " + intervalSeconds + " seconds\n"
                + "========================================\u001B[0m";
        getLogger().info(banner);
        logInfo("RankAPI plugin enabled and polling every " + intervalSeconds + " seconds.");
        logInfo("Use /rankapi fetch to manually trigger order fetch.");
    }

    // Manual polling invoked by command
    private void pollOnce(boolean showNoOrders) {
        if (baseUrl == null || baseUrl.isBlank()) {
            logWarn("base-url is not set in config.yml. polling disabled.");
            return;
        }

        try {
            String url = baseUrl + "/api/plugin/orders?pending=true";
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json");

            if (apiSecret != null && !apiSecret.isBlank()) {
                reqBuilder.header("X-Plugin-Secret", apiSecret);
            }

            HttpRequest request = reqBuilder.build();

            CompletableFuture<HttpResponse<String>> future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            future.thenAccept(response -> {
                if (response.statusCode() != 200) {
                    savePluginLog("FETCH_RESPONSE_FAILED", "status=" + response.statusCode() + "\n" + response.body());
                    logWarn("API responded with status " + response.statusCode() + " while fetching orders.");
                    return;
                }

                savePluginLog("FETCH_RESPONSE", response.body());
                try {
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    JsonArray orders = root.has("orders") ? root.getAsJsonArray("orders") : null;
                    if (orders == null || orders.size() == 0) {
                        if (showNoOrders) {
                            logInfo("No pending orders at this time.");
                        }
                        return;
                    }

                    for (JsonElement el : orders) {
                        JsonObject order = el.getAsJsonObject();
                        String orderId = order.has("id") ? order.get("id").getAsString() : null;
                        String player = order.has("player") ? order.get("player").getAsString() : null;
                        JsonArray commands = order.has("commands") ? order.getAsJsonArray("commands") : null;
                        if (orderId == null || commands == null || commands.size() == 0 || player == null || player.isBlank()) continue;

                        if (Bukkit.getPlayerExact(player) != null) {
                            logInfo("Processing order " + orderId + " for online player " + player + ".");
                            executeOrder(orderId, commands);
                        } else {
                            logInfo("Player " + player + " is offline; scheduling order " + orderId + " for autorun.");
                            addAutorunEntry(player, orderId, commands);
                            sendCompletionCallback(orderId);
                        }
                    }

                } catch (Exception ex) {
                    logError("Failed parsing orders response.", ex);
                }
            }).exceptionally(ex -> {
                logError("Poll request failed.", ex);
                return null;
            });

        } catch (Exception e) {
            logError("Error polling orders.", e);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        String playerName = event.getPlayer().getName();
        scheduleAutorunForPlayer(playerName);
    }

    @Override
    public boolean onCommand(CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rankapi")) return false;

        if (args.length == 0) {
            sendFormattedMessage(sender, "Usage: /rankapi fetch");
            return true;
        }

        String subcommand = args[0].toLowerCase();
        if (subcommand.equals("fetch") || subcommand.equals("fatch")) {
            sendFormattedMessage(sender, "Fetching pending orders...");
            pollOnce(true);
            return true;
        }

        sendFormattedMessage(sender, "Unknown subcommand. Use /rankapi fetch.");
        return true;
    }

    private String colorConsole(String message) {
        return "\u001B[32m[RankAPI] " + message + "\u001B[0m";
    }

    private void executeOrder(String orderId, JsonArray commands) {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (JsonElement cmdEl : commands) {
                    String cmd = cmdEl.getAsString();
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    logInfo("Executing command: " + cmd);
                    try {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    } catch (Exception ex) {
                        logError("Error executing command: " + cmd, ex);
                    }
                }
                CompletableFuture.runAsync(() -> sendCompletionCallback(orderId, "executed"));
            }
        }.runTask(this);
    }

    private void scheduleAutorunForPlayer(String playerName) {
        JsonArray queue = loadAutorunQueue();
        JsonArray remaining = new JsonArray();

        for (JsonElement element : queue) {
            JsonObject entry = element.getAsJsonObject();
            if (!playerName.equalsIgnoreCase(entry.get("player").getAsString())) {
                remaining.add(entry);
                continue;
            }
            String orderId = entry.get("orderId").getAsString();
            JsonArray commands = entry.getAsJsonArray("commands");
            logInfo("Running queued autorun order " + orderId + " for " + playerName + ".");
            executeOrder(orderId, commands);
        }

        if (remaining.size() != queue.size()) {
            saveAutorunQueue(remaining);
        }
    }

    private void addAutorunEntry(String player, String orderId, JsonArray commands) {
        JsonArray queue = loadAutorunQueue();
        JsonObject entry = new JsonObject();
        entry.addProperty("player", player);
        entry.addProperty("orderId", orderId);
        entry.add("commands", commands.deepCopy());
        queue.add(entry);
        saveAutorunQueue(queue);
        savePluginLog("AUTORUN_PENDING", "player=" + player + " orderId=" + orderId + " commands=" + commands.toString());
    }

    private JsonArray loadAutorunQueue() {
        try {
            Path autorunFile = getDataFolder().toPath().resolve(AUTORUN_FILE_NAME);
            if (!Files.exists(autorunFile)) {
                return new JsonArray();
            }
            String content = Files.readString(autorunFile, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                return new JsonArray();
            }
            return JsonParser.parseString(content).getAsJsonArray();
        } catch (Exception ex) {
            logWarn("Unable to load autorun queue: " + ex.getMessage());
            return new JsonArray();
        }
    }

    private void saveAutorunQueue(JsonArray queue) {
        try {
            if (!getDataFolder().exists()) {
                getDataFolder().mkdirs();
            }
            Path autorunFile = getDataFolder().toPath().resolve(AUTORUN_FILE_NAME);
            Files.writeString(autorunFile, queue.toString(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ex) {
            logWarn("Unable to save autorun queue: " + ex.getMessage());
        }
    }

    private void sendCompletionCallback(String orderId) {
        sendCompletionCallback(orderId, "executed");
    }

    private void sendCompletionCallback(String orderId, String status) {
        try {
            String url = baseUrl + "/api/plugin/orders/" + orderId + "/complete";
            String payload = String.format("{\"status\":\"%s\"}", status);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            if (apiSecret != null && !apiSecret.isBlank()) {
                reqBuilder.header("X-Plugin-Secret", apiSecret);
            }

            HttpRequest request = reqBuilder.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() == 200) {
                logDebug("Marked order " + orderId + " as " + status + ".");
            } else {
                logWarn("Failed to mark order " + orderId + ". Status: " + response.statusCode());
            }

        } catch (Exception e) {
            logError("Error sending completion callback for " + orderId + ".", e);
        }
    }

    private void checkAutoUpdate() {
        if (!autoUpdateEnabled) {
            return;
        }
        if (updateUrl == null || updateUrl.isBlank()) {
            logWarn("Auto-update is enabled but update URL is not configured.");
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(updateUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                logWarn("Auto-update check failed with status " + response.statusCode());
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            String remoteVersion = root.has("version") ? root.get("version").getAsString() : null;
            String downloadUrl = root.has("downloadUrl") ? root.get("downloadUrl").getAsString() : null;

            if (remoteVersion == null || downloadUrl == null) {
                logWarn("Auto-update response is missing version or downloadUrl.");
                return;
            }

            if (isRemoteVersionNewer(pluginVersion, remoteVersion)) {
                logInfo("New plugin version available: " + remoteVersion + ". Downloading update...");
                if (downloadUpdateJar(downloadUrl, remoteVersion)) {
                    logInfo("Downloaded update for version " + remoteVersion + ". Restart server to apply the update.");
                }
            } else {
                logDebug("Plugin is up to date.");
            }
        } catch (Exception ex) {
            logWarn("Auto-update check failed: " + ex.getMessage());
        }
    }

    private boolean isRemoteVersionNewer(String current, String remote) {
        if (current == null || remote == null) {
            return false;
        }

        String[] currentParts = current.split("\\.");
        String[] remoteParts = remote.split("\\.");
        int length = Math.max(currentParts.length, remoteParts.length);
        for (int i = 0; i < length; i++) {
            int currentValue = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
            int remoteValue = i < remoteParts.length ? parseVersionPart(remoteParts[i]) : 0;
            if (remoteValue > currentValue) {
                return true;
            }
            if (remoteValue < currentValue) {
                return false;
            }
        }
        return false;
    }

    private int parseVersionPart(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean downloadUpdateJar(String downloadUrl, String remoteVersion) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                logWarn("Download failed with status " + response.statusCode());
                return false;
            }

            File pluginFile = getFile();
            if (pluginFile == null) {
                logWarn("Unable to resolve plugin file path for update.");
                return false;
            }

            Path target = pluginFile.toPath().getParent().resolve(pluginFileName + ".new");
            Files.write(target, response.body(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            savePluginLog("AUTO_UPDATE", "Downloaded version " + remoteVersion + " to " + target);
            return true;
        } catch (Exception ex) {
            logWarn("Failed to download update: " + ex.getMessage());
            return false;
        }
    }
}
