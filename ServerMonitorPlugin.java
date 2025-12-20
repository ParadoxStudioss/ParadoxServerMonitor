package com.example.servermonitor;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class ServerMonitorPlugin extends JavaPlugin {
    private Metrics metrics;
    private DiscordClient discord;
    private String messageId;
    private TpsMeter tps;
    private String webhookUrl;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        metrics = new Metrics();
        webhookUrl = getConfig().getString("webhook_url", "");
        if (webhookUrl == null) webhookUrl = "";
        discord = new DiscordClient(webhookUrl);
        loadMessageId();
        tps = new TpsMeter();
        tps.start(this);
        int interval = getConfig().getInt("update_interval_seconds", 30);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (webhookUrl.isEmpty()) return;
            String payload = buildPayload();
            try {
                if (messageId == null || messageId.isEmpty()) {
                    messageId = discord.createMessage(payload);
                    saveMessageId();
                } else {
                    boolean ok = discord.editMessage(messageId, payload);
                    if (!ok) {
                        messageId = discord.createMessage(payload);
                        saveMessageId();
                    }
                }
            } catch (Exception e) {
            }
        }, 0L, interval * 20L);
    }

    private String buildPayload() {
        long max = metrics.maxMemoryBytes();
        long total = metrics.totalMemoryBytes();
        long free = metrics.freeMemoryBytes();
        long used = metrics.usedMemoryBytes();
        double cpu = metrics.processCpuPercent();
        double sysCpu = metrics.systemCpuPercent();
        double pct = max > 0 ? (used * 100.0 / max) : 0.0;
        String title = getConfig().getString("title", "Server Usage");
        String ts = metrics.nowIso();
        String maxStr = metrics.formatBytes(max);
        String usedStr = metrics.formatBytes(used) + " (" + String.format("%.1f%%", pct) + ")";
        String cpuStr = cpu >= 0 ? String.format("%.1f%%", cpu) : "N/A";
        String sysCpuStr = sysCpu >= 0 ? String.format("%.1f%%", sysCpu) : "N/A";
        String totalStr = metrics.formatBytes(total);
        String freeStr = metrics.formatBytes(free);
        int players = getServer().getOnlinePlayers().size();
        double tpsNow = tps.getCurrentTps();
        double tpsAvg = tps.getAverageTps();
        double tickMs = tps.getLastTickMs();
        String uptime = metrics.formatDuration(metrics.uptimeMillis());
        String json = "{\"embeds\":[{" +
                "\"title\":\"" + escape(title) + "\"," +
                "\"color\":5814783," +
                "\"fields\":[{" +
                "\"name\":\"Memory Max\",\"value\":\"" + escape(maxStr) + "\",\"inline\":true},{" +
                "\"name\":\"Memory Used\",\"value\":\"" + escape(usedStr) + "\",\"inline\":true},{" +
                "\"name\":\"CPU Usage (proc)\",\"value\":\"" + cpuStr + "\",\"inline\":true},{" +
                "\"name\":\"CPU Usage (sys)\",\"value\":\"" + sysCpuStr + "\",\"inline\":true},{" +
                "\"name\":\"Memory Allocated\",\"value\":\"" + escape(totalStr) + "\",\"inline\":true},{" +
                "\"name\":\"Memory Free\",\"value\":\"" + escape(freeStr) + "\",\"inline\":true},{" +
                "\"name\":\"Players Online\",\"value\":\"" + players + "\",\"inline\":true},{" +
                "\"name\":\"TPS Now\",\"value\":\"" + String.format("%.2f", tpsNow) + "\",\"inline\":true},{" +
                "\"name\":\"TPS Avg\",\"value\":\"" + String.format("%.2f", tpsAvg) + "\",\"inline\":true},{" +
                "\"name\":\"Tick ms\",\"value\":\"" + String.format("%.1f ms", tickMs) + "\",\"inline\":true},{" +
                "\"name\":\"Uptime\",\"value\":\"" + escape(uptime) + "\",\"inline\":true},{" +
                "\"name\":\"Last Update\",\"value\":\"" + escape(ts) + "\",\"inline\":false}" +
                "],\"timestamp\":\"" + ts + "\"}" +
                "]}";
        return json;
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void loadMessageId() {
        try {
            File f = new File(getDataFolder(), "data.yml");
            if (!f.exists()) return;
            FileConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            messageId = cfg.getString("message_id", null);
        } catch (Exception e) {
            messageId = null;
        }
    }

    private void saveMessageId() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            File f = new File(getDataFolder(), "data.yml");
            FileConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
            cfg.set("message_id", messageId);
            cfg.save(f);
        } catch (IOException e) {
        }
    }
}
