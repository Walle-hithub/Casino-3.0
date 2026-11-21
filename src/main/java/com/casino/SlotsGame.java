package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class SlotsGame implements Listener {

    private static final int[] REEL_SLOTS = {11, 13, 15};
    private static final int RESULT_SLOT = 22;

    private final CasinoPlugin plugin;
    private final List<Material> symbols = new ArrayList<>();
    private final Map<Material, Double> multipliers = new HashMap<>();
    private final Set<UUID> playing = new HashSet<>();
    private double pairMultiplier;

    /** Holder propio para identificar nuestro inventario. */
    private static class SlotsHolder implements InventoryHolder {
        private Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public SlotsGame(CasinoPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        symbols.clear();
        multipliers.clear();
        pairMultiplier = plugin.getConfig().getDouble("slots.pair-multiplier", 1.0);
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("slots.symbols");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m == null) {
                    plugin.getLogger().warning("Material invalido en slots.symbols: " + key);
                    continue;
                }
                symbols.add(m);
                multipliers.put(m, sec.getDouble(key));
            }
        }
        if (symbols.size() < 2) {
            plugin.getLogger().warning("Pocos simbolos validos, usando valores por defecto.");
            symbols.clear();
            multipliers.clear();
            symbols.addAll(List.of(Material.DIAMOND, Material.EMERALD, Material.GOLD_INGOT, Material.REDSTONE, Material.COAL));
            double[] d = {20, 10, 6, 4, 3};
            for (int i = 0; i < d.length; i++) multipliers.put(symbols.get(i), d[i]);
        }
    }

    public List<Material> getSymbols() { return symbols; }

    public void play(Player p, CasinoPlugin.Bet bet) {
        if (!playing.add(p.getUniqueId())) {
            // No deberia pasar (hay cooldown), pero devolvemos la apuesta por seguridad
            plugin.give(p.getUniqueId(), bet.material(), bet.amount());
            plugin.msg(p, "&cYa estas jugando.");
            return;
        }

        // El resultado se decide desde el principio; la animacion es solo visual
        Material[] result = new Material[3];
        for (int i = 0; i < 3; i++) result[i] = symbols.get(plugin.getRandom().nextInt(symbols.size()));
        double multiplier = computeMultiplier(result);

        SlotsHolder holder = new SlotsHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, plugin.color("&6&lTragaperras"));
        holder.inv = inv;

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        inv.setItem(4, named(Material.HOPPER, plugin.color("&eApuesta: &f" + plugin.fmt(bet.material(), bet.amount()))));
        for (int s : REEL_SLOTS) inv.setItem(s, named(randomSymbol(), " "));
        p.openInventory(inv);

        final int[] stopFrames = {10, 16, 22};
        final UUID id = p.getUniqueId();

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);

                for (int i = 0; i < 3; i++) {
                    if (frame < stopFrames[i]) {
                        inv.setItem(REEL_SLOTS[i], named(randomSymbol(), " "));
                    } else if (frame == stopFrames[i]) {
                        inv.setItem(REEL_SLOTS[i], named(result[i], plugin.color("&f" + plugin.pretty(result[i]))));
                        if (online != null) online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f + i * 0.3f);
                    }
                }
                if (online != null && frame < stopFrames[2])
                    online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.5f);

                frame++;
                if (frame > stopFrames[2]) {
                    cancel();
                    finish(id, inv, bet, multiplier);
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    private void finish(UUID id, Inventory inv, CasinoPlugin.Bet bet, double multiplier) {
        Player p = Bukkit.getPlayer(id);
        int prize = (int) Math.floor(bet.amount() * multiplier);

        if (prize > 0) {
            plugin.give(id, bet.material(), prize); // si esta offline, se guarda como pendiente
            String text = plugin.fmt(bet.material(), prize);
            inv.setItem(RESULT_SLOT, named(Material.LIME_STAINED_GLASS_PANE,
                    plugin.color("&a¡Premio: " + text + "! (x" + trim(multiplier) + ")")));
            if (p != null) {
                plugin.msg(p, "&a¡Tragaperras! Ganaste &e" + text + " &7(x" + trim(multiplier) + ")");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        } else {
            inv.setItem(RESULT_SLOT, named(Material.RED_STAINED_GLASS_PANE, plugin.color("&cSin premio")));
            if (p != null) {
                plugin.msg(p, "&cNo hubo suerte. Perdiste &e" + plugin.fmt(bet.material(), bet.amount()) + "&c.");
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            }
        }
        playing.remove(id);
    }

    public double computeMultiplier(Material[] r) {
        if (r[0] == r[1] && r[1] == r[2]) return multipliers.getOrDefault(r[0], 0.0);
        if (r[0] == r[1] || r[1] == r[2] || r[0] == r[2]) return pairMultiplier;
        return 0;
    }

    public Material randomSymbol() {
        return symbols.get(plugin.getRandom().nextInt(symbols.size()));
    }

    private ItemStack named(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }

    private String trim(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // Evita que los jugadores saquen items del GUI
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SlotsHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof SlotsHolder) e.setCancelled(true);
    }
}
