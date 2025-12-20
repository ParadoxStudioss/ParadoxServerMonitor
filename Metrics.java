package com.example.servermonitor;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;

public class Metrics {
    public long maxMemoryBytes() {
        return Runtime.getRuntime().maxMemory();
    }

    public long totalMemoryBytes() {
        return Runtime.getRuntime().totalMemory();
    }

    public long freeMemoryBytes() {
        return Runtime.getRuntime().freeMemory();
    }

    public long usedMemoryBytes() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    public double processCpuPercent() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double v = os.getProcessCpuLoad();
            if (v < 0) return -1;
            return v * 100.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public double systemCpuPercent() {
        try {
            OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double v = os.getSystemCpuLoad();
            if (v < 0) return -1;
            return v * 100.0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public String formatBytes(long bytes) {
        double b = bytes;
        double gb = b / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format("%.2f GB", gb);
        double mb = b / (1024.0 * 1024.0);
        return String.format("%.0f MB", mb);
    }

    public String nowIso() {
        return Instant.now().toString();
    }

    public long uptimeMillis() {
        RuntimeMXBean rb = ManagementFactory.getRuntimeMXBean();
        return rb.getUptime();
    }

    public String formatDuration(long ms) {
        long s = ms / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        s %= 60;
        if (d > 0) return String.format("%dd %dh %dm", d, h, m);
        if (h > 0) return String.format("%dh %dm %ds", h, m, s);
        if (m > 0) return String.format("%dm %ds", m, s);
        return String.format("%ds", s);
    }
}
