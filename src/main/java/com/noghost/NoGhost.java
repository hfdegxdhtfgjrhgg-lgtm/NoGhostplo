package com.noghost;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

public final class NoGhost extends JavaPlugin implements Listener {

    private final Deque<HitRecord> history = new ArrayDeque<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("NoGhost enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("NoGhost disabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(ChatColor.RED + "Usage: /noghost reload");
            return true;
        }
        if (!sender.hasPermission("noghost.admin")) {
            sender.sendMessage(ChatColor.RED + "No permission!");
            return true;
        }
        reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "NoGhost config reloaded!");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!getConfig().getBoolean("combat.enabled", true)) return;

        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        long now = System.currentTimeMillis();
        long window = getConfig().getLong("combat.duplicate-window-ms", 50L);

        while (!history.isEmpty() && (now - history.peekFirst().time) > window) {
            history.pollFirst();
        }

        for (HitRecord rec : history) {
            if (rec.attacker.equals(attacker.getUniqueId())
                    && rec.victim.equals(victim.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
        }
        history.addLast(new HitRecord(attacker.getUniqueId(), victim.getUniqueId(), now));

        if (attacker.isDead() || victim.isDead()) {
            event.setCancelled(true);
            return;
        }
        if (attacker.getGameMode() == GameMode.SPECTATOR
                || victim.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
            return;
        }
        if (victim.isInvulnerable()) {
            event.setCancelled(true);
            return;
        }

        if (getConfig().getBoolean("combat.distance-check", true)) {
            double baseReach = getConfig().getDouble("combat.base-reach", 3.0);
            double maxReach = getConfig().getDouble("combat.max-reach", 4.5);

            int ping = getPing(attacker);
            double allowed = baseReach;

            if (getConfig().getBoolean("combat.high-ping-compensation", true)) {
                if (ping <= 50) allowed = baseReach;
                else if (ping <= 100) allowed = baseReach + 0.3;
                else if (ping <= 150) allowed = baseReach + 0.6;
                else if (ping <= 200) allowed = baseReach + 0.9;
                else allowed = baseReach + 1.2;
            }

            if (allowed > maxReach) allowed = maxReach;

            double distance = eyeToBox(attacker, victim);
            if (distance > allowed) {
                event.setCancelled(true);
                if (getConfig().getBoolean("debug.enabled", false)) {
                    getLogger().info("[NoGhost] Rejected: " + attacker.getName()
                            + " -> " + victim.getName()
                            + " (" + String.format("%.2f", distance)
                            + " > " + String.format("%.2f", allowed) + ")");
                }
            }
        }
    }

    private int getPing(Player p) {
        try {
            return p.getPing();
        } catch (Throwable t) {
            return 0;
        }
    }

    private double eyeToBox(Player attacker, Player victim) {
        Location eye = attacker.getEyeLocation();
        BoundingBox box = victim.getBoundingBox();
        double cx = Math.max(box.getMinX(), Math.min(box.getMaxX(), eye.getX()));
        double cy = Math.max(box.getMinY(), Math.min(box.getMaxY(), eye.getY()));
        double cz = Math.max(box.getMinZ(), Math.min(box.getMaxZ(), eye.getZ()));
        double dx = eye.getX() - cx;
        double dy = eye.getY() - cy;
        double dz = eye.getZ() - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final class HitRecord {
        final UUID attacker;
        final UUID victim;
        final long time;

        HitRecord(UUID a, UUID v, long t) {
            this.attacker = a;
            this.victim = v;
            this.time = t;
        }
    }
}
