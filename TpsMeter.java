package com.example.servermonitor;

import org.bukkit.plugin.Plugin;

public class TpsMeter {
    private volatile double currentTps = 20.0;
    private volatile double emaTps = 20.0;
    private volatile double lastTickMs = 50.0;
    private long lastTime;

    public void start(Plugin plugin) {
        lastTime = System.nanoTime();
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            long delta = now - lastTime;
            lastTime = now;
            double ms = delta / 1_000_000.0;
            if (ms <= 0) return;
            lastTickMs = ms;
            double tps = Math.min(20.0, 1000.0 / ms);
            currentTps = tps;
            double alpha = 0.05;
            emaTps = emaTps + alpha * (tps - emaTps);
        }, 0L, 1L);
    }

    public double getCurrentTps() {
        return currentTps;
    }

    public double getAverageTps() {
        return emaTps;
    }

    public double getLastTickMs() {
        return lastTickMs;
    }
}

