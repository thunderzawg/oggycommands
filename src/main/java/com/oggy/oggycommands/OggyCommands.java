package com.oggy.oggycommands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OggyCommands extends JavaPlugin implements Listener, TabExecutor {

    /** A rectangle on the X/Z plane (all heights) inside one world. */
    private record Region(String world, int minX, int maxX, int minZ, int maxZ) {
        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int x = l.getBlockX(), z = l.getBlockZ();
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        String serialize() {
            return world + "," + minX + "," + maxX + "," + minZ + "," + maxZ;
        }

        static Region parse(String s) {
            String[] p = s.split(",");
            return new Region(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        }

        @Override
        public String toString() {
            return world + " (x " + minX + " to " + maxX + ", z " + minZ + " to " + maxZ + ")";
        }
    }

    private NamespacedKey wandKey;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final List<Region> regions = new ArrayList<>();

    // combat log
    private final Map<UUID, Long> combatTags = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "oggy_wand");
        loadRegions();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("oggycommand");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // action bar countdown + tag expiry
        Bukkit.getScheduler().runTaskTimer(this, this::tickCombat, 20L, 20L);
    }

    // ------------------------------------------------------------------ regions

    private void loadRegions() {
        regions.clear();
        for (String s : getConfig().getStringList("regions")) {
            try {
                regions.add(Region.parse(s));
            } catch (Exception e) {
                getLogger().warning("Bad region in config: " + s);
            }
        }
    }

    private void saveRegions() {
        List<String> out = new ArrayList<>();
        for (Region r : regions) out.add(r.serialize());
        getConfig().set("regions", out);
        saveConfig();
    }

    private boolean inAnyRegion(Location l) {
        for (Region r : regions) if (r.contains(l)) return true;
        return false;
    }

    // --------------------------------------------------------------------- wand

    private ItemStack makeWand() {
        ItemStack item = new ItemStack(Material.HEART_OF_THE_SEA);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Oggy Wand", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Left click: position 1", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("Right click: position 2", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || item.getType() != Material.HEART_OF_THE_SEA || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onWandUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.LEFT_CLICK_BLOCK && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isWand(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);
        if (!p.hasPermission("oggycommands.admin") || e.getClickedBlock() == null) return;

        Location loc = e.getClickedBlock().getLocation();
        if (a == Action.LEFT_CLICK_BLOCK) {
            pos1.put(p.getUniqueId(), loc);
            msg(p, "Position 1 set to " + loc.getBlockX() + ", " + loc.getBlockZ() + " (x, z)", NamedTextColor.GREEN);
        } else {
            pos2.put(p.getUniqueId(), loc);
            msg(p, "Position 2 set to " + loc.getBlockX() + ", " + loc.getBlockZ() + " (x, z)", NamedTextColor.GREEN);
        }
    }

    // ----------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("oggycommands.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /oggycommand wand | mvtp allow | mvtp list | mvtp remove <number> | mvtp clear",
                    NamedTextColor.YELLOW));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("wand")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Players only.");
                return true;
            }
            p.getInventory().addItem(makeWand());
            msg(p, "You got the Oggy Wand.", NamedTextColor.AQUA);
            return true;
        }

        if (sub.equals("mvtp")) {
            String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
            switch (action) {
                case "allow" -> {
                    if (!(sender instanceof Player p)) {
                        sender.sendMessage("Players only.");
                        return true;
                    }
                    Location a = pos1.get(p.getUniqueId());
                    Location b = pos2.get(p.getUniqueId());
                    if (a == null || b == null) {
                        msg(p, "Select both positions with the wand first.", NamedTextColor.RED);
                        return true;
                    }
                    if (!a.getWorld().equals(b.getWorld())) {
                        msg(p, "Both positions must be in the same world.", NamedTextColor.RED);
                        return true;
                    }
                    Region r = new Region(a.getWorld().getName(),
                            Math.min(a.getBlockX(), b.getBlockX()), Math.max(a.getBlockX(), b.getBlockX()),
                            Math.min(a.getBlockZ(), b.getBlockZ()), Math.max(a.getBlockZ(), b.getBlockZ()));
                    regions.add(r);
                    saveRegions();
                    msg(p, "/mvtp now works inside: " + r, NamedTextColor.GREEN);
                }
                case "list" -> {
                    if (regions.isEmpty()) {
                        sender.sendMessage(Component.text("No allowed areas. /mvtp is blocked everywhere.", NamedTextColor.YELLOW));
                    } else {
                        for (int i = 0; i < regions.size(); i++) {
                            sender.sendMessage(Component.text((i + 1) + ". " + regions.get(i), NamedTextColor.YELLOW));
                        }
                    }
                }
                case "remove" -> {
                    if (args.length < 3) {
                        sender.sendMessage(Component.text("Usage: /oggycommand mvtp remove <number>", NamedTextColor.RED));
                        return true;
                    }
                    try {
                        int n = Integer.parseInt(args[2]);
                        if (n < 1 || n > regions.size()) throw new NumberFormatException();
                        Region r = regions.remove(n - 1);
                        saveRegions();
                        sender.sendMessage(Component.text("Removed: " + r, NamedTextColor.GREEN));
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(Component.text("Invalid number. Use /oggycommand mvtp list.", NamedTextColor.RED));
                    }
                }
                case "clear" -> {
                    regions.clear();
                    saveRegions();
                    sender.sendMessage(Component.text("All allowed areas removed.", NamedTextColor.GREEN));
                }
                default -> sender.sendMessage(Component.text("Usage: /oggycommand mvtp allow|list|remove|clear", NamedTextColor.YELLOW));
            }
            return true;
        }

        sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("oggycommands.admin")) return List.of();
        if (args.length == 1) return filter(List.of("wand", "mvtp"), args[0]);
        if (args.length == 2 && args[0].equalsIgnoreCase("mvtp"))
            return filter(List.of("allow", "list", "remove", "clear"), args[1]);
        return List.of();
    }

    private static List<String> filter(List<String> opts, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : opts) if (o.startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }

    // ------------------------------------------------------- /mvtp restriction

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommandUse(PlayerCommandPreprocessEvent e) {
        String raw = e.getMessage();
        if (raw.length() < 2) return;
        String line = raw.substring(1).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        // strip "plugin:" prefix of the first word, e.g. multiverse-core:mvtp
        int space = line.indexOf(' ');
        String first = space == -1 ? line : line.substring(0, space);
        String rest = space == -1 ? "" : line.substring(space);
        int colon = first.indexOf(':');
        if (colon >= 0) first = first.substring(colon + 1);
        String normalized = first + rest;

        Player player = e.getPlayer();

        // 1) block commands while in combat (survival only), except the allowed list
        if (inCombatWorld(player) && isTagged(player)
                && !matchesAny("combat-log.allowed-commands", normalized)) {
            e.setCancelled(true);
            msg(player, "You can't use that command while in combat!", NamedTextColor.RED);
            return;
        }

        // 2) mvtp restriction: only inside an allowed area
        if (matchesAny("restricted-commands", normalized) && !inAnyRegion(player.getLocation())) {
            e.setCancelled(true);
            msg(player, "You can only use /mvtp inside the allowed area.", NamedTextColor.RED);
        }
    }

    private boolean matchesAny(String configPath, String normalized) {
        for (String c : getConfig().getStringList(configPath)) {
            String cc = c.toLowerCase(Locale.ROOT).trim();
            if (cc.startsWith("/")) cc = cc.substring(1);
            if (normalized.equals(cc) || normalized.startsWith(cc + " ")) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- combat log

    private boolean inCombatWorld(Player p) {
        return getConfig().getBoolean("combat-log.enabled", true)
                && p.getWorld().getName().equals(getConfig().getString("combat-log.world", "survival"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player pl) attacker = pl;
        else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player pl) attacker = pl;
        if (attacker == null || attacker.equals(victim)) return;
        if (!inCombatWorld(victim) || !inCombatWorld(attacker)) return;

        tag(victim);
        tag(attacker);
    }

    private void tag(Player p) {
        long seconds = getConfig().getLong("combat-log.seconds", 15);
        boolean already = combatTags.containsKey(p.getUniqueId());
        combatTags.put(p.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        if (!already) msg(p, "You are in combat! Don't log out for " + seconds + " seconds.", NamedTextColor.RED);
    }

    private boolean isTagged(Player p) {
        Long end = combatTags.get(p.getUniqueId());
        return end != null && end > System.currentTimeMillis();
    }

    private void tickCombat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = combatTags.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> en = it.next();
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                it.remove();
                continue;
            }
            long left = (en.getValue() - now + 999) / 1000;
            if (left <= 0) {
                it.remove();
                p.sendActionBar(Component.text("You are no longer in combat.", NamedTextColor.GREEN));
            } else {
                p.sendActionBar(Component.text("Combat: " + left + "s", NamedTextColor.RED));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        boolean tagged = isTagged(p) && inCombatWorld(p);
        combatTags.remove(p.getUniqueId());
        pos1.remove(p.getUniqueId());
        pos2.remove(p.getUniqueId());
        if (tagged && getConfig().getBoolean("combat-log.kill-on-logout", true)) {
            Bukkit.broadcast(Component.text(p.getName() + " logged out during combat and was killed.", NamedTextColor.RED));
            p.setHealth(0.0);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        combatTags.remove(e.getEntity().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        combatTags.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ helpers

    private void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text(text, color));
    }
}
