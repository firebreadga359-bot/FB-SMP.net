package net.firebreadmc.fbsmp.paper.admin;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Collects live server metrics for the dashboard. Everything here is a
 * best-effort read of real state; nothing is fabricated, and missing data is
 * reported as unavailable rather than guessed.
 */
public final class ServerMetrics {

    public record WorldInfo(String name, String environment, long sizeBytes, int players) {}

    public static double tps() {
        try {
            return Bukkit.getTPS()[0];
        } catch (Throwable unavailable) {
            return -1.0d;
        }
    }

    public static double mspt() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable unavailable) {
            return -1.0d;
        }
    }

    public static long diskTotalBytes() {
        try {
            return Files.getFileStore(Path.of(".")).getTotalSpace();
        } catch (IOException exception) {
            return -1L;
        }
    }

    public static long diskUsableBytes() {
        try {
            return Files.getFileStore(Path.of(".")).getUsableSpace();
        } catch (IOException exception) {
            return -1L;
        }
    }

    public static long processCpuMillis() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getProcessCpuTime() / 1_000_000L;
            }
        } catch (Throwable unavailable) {
            // Fall through.
        }
        return -1L;
    }

    public static long systemLoadPercent() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
                double load = sun.getCpuLoad();
                return load < 0 ? -1L : Math.round(load * 100);
            }
        } catch (Throwable unavailable) {
            // Fall through.
        }
        return -1L;
    }

    public static List<WorldInfo> worlds() {
        List<WorldInfo> infos = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            infos.add(new WorldInfo(world.getName(), world.getEnvironment().name(),
                    directorySize(world.getWorldFolder().toPath()), world.getPlayers().size()));
        }
        return infos;
    }

    public static long directorySize(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return -1L;
        }
        final long[] total = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            return -1L;
        }
        return total[0];
    }

    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            return "unavailable";
        }
        long days = TimeUnit.SECONDS.toDays(seconds);
        long hours = TimeUnit.SECONDS.toHours(seconds) % 24;
        long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;
        return (days > 0 ? days + "d " : "") + hours + "h " + minutes + "m";
    }

    public static String iso(long epochMillis) {
        return epochMillis <= 0 ? "" : Instant.ofEpochMilli(epochMillis).toString();
    }

    public static int onlinePlayers() {
        return Bukkit.getOnlinePlayers().size();
    }

    public static int maxPlayers() {
        return Bukkit.getMaxPlayers();
    }

    public static List<Player> onlinePlayerList() {
        return new ArrayList<>(Bukkit.getOnlinePlayers());
    }
}
