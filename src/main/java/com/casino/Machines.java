package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Maquinas fisicas del casino: carteles que los jugadores usan con clic derecho.
 *
 * Crear una maquina (requiere casino.admin), escribe en un cartel:
 *   linea 1: [Casino]
 *   linea 2: slots | moneda | dados
 *   linea 3: cantidad a apostar (ej. 3)
 *   linea 4: mineral (ej. diamante)
 */
public class Machines implements Listener {

    private static final int COIN_HEADS = 2;
    private static final int COIN_TAILS = 6;

    private final CasinoPlugin plugin;
    private final NamespacedKey gameKey;
    private final NamespacedKey amountKey;
    private final NamespacedKey materialKey;

    private static class CoinHolder implements InventoryHolder {
        Inventory inv;
        int amount;
        Material material;
        @Override public Inventory getInventory() { return inv; }
    }

    public Machines(CasinoPlugin plugin) {
        this.plugin = plugin;
        this.gameKey = new NamespacedKey(plugin, "game");
        this.amountKey = new NamespacedKey(plugin, "amount");
        this.materialKey = new NamespacedKey(plugin, "material");
    }

    // ---------- Crear maquina ----------

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        if (e.getSide() != Side.FRONT) return;
        String first = e.getLine(0);
        if (first == null || !first.trim().equalsIgnoreCase("[casino]")) return;

        Player p = e.getPlayer();
        if (!p.hasPermission("casino.admin")) {
            plugin.msg(p, "&cNo tienes permiso para crear maquinas.");
            e.setCancelled(true);
            return;
        }

        String game = parseGame(e.getLine(1));
        if (game == null) {
            plugin.msg(p, "&cLinea 2 invalida. Usa: &eslots&c, &emoneda &co &edados&c.");
            e.setCancelled(true);
            return;
        }
        int amount;
        try { amount = Integer.parseInt(e.getLine(2).trim()); }
        catch (Exception ex) {
            plugin.msg(p, "&cLinea 3 invalida: escribe un numero (ej. 3).");
            e.setCancelled(true);
            return;
        }
        Material mat = plugin.parseMaterial(e.getLine(3) == null ? "" : e.getLine(3).trim());
        if (mat == null || !plugin.getAllowed().containsKey(mat)) {
            plugin.msg(p, "&cLinea 4 invalida. Minerales: &e" + plugin.allowedNames());
            e.setCancelled(true);
            return;
        }
        int max = plugin.getAllowed().get(mat);
        if (amount < 1 || amount > max) {
            plugin.msg(p, "&cPara " + plugin.pretty(mat) + " la apuesta va de &e1 &ca &e" + max + "&c.");
            e.setCancelled(true);
            return;
        }

        e.setLine(0, plugin.color("&6&l[Casino]"));
        e.setLine(1, plugin.color(gameLabel(game)));
        e.setLine(2, plugin.color("&fApuesta: &a" + amount));
        e.setLine(3, plugin.color("&b" + plugin.pretty(mat)));

        final Block block = e.getBlock();
        final String g = game;
        final int a = amount;
        final Material m = mat;
        // El estado del cartel solo se puede modificar un tick despues
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(block.getState() instanceof Sign sign)) return;
            PersistentDataContainer pdc = sign.getPersistentDataContainer();
            pdc.set(gameKey, PersistentDataType.STRING, g);
            pdc.set(amountKey, PersistentDataType.INTEGER, a);
            pdc.set(materialKey, PersistentDataType.STRING, m.name());
            sign.setWaxed(true); // evita que otros jugadores editen el cartel
            sign.update();
            plugin.msg(p, "&aMaquina creada. Los jugadores solo tienen que hacer clic derecho.");
        });
    }

    private String parseGame(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase()) {
            case "slots", "tragaperras": return "slots";
            case "moneda", "coinflip", "caraocruz": return "coinflip";
            case "dados", "dice": return "dice";
            default: return null;
        }
    }

    private String gameLabel(String game) {
        return switch (game) {
            case "slots" -> "&eTragaperras";
            case "coinflip" -> "&eCara o Cruz";
            default -> "&eDados";
        };
    }

    // ---------- Usar maquina ----------

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null || !Tag.SIGNS.isTagged(b.getType())) return;
        if (!(b.getState() instanceof Sign sign)) return;

        PersistentDataContainer pdc = sign.getPersistentDataContainer();
        String game = pdc.get(gameKey, PersistentDataType.STRING);
        Integer amount = pdc.get(amountKey, PersistentDataType.INTEGER);
        String matName = pdc.get(materialKey, PersistentDataType.STRING);
        if (game == null || amount == null || matName == null) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        Material mat = Material.matchMaterial(matName);
        if (mat == null) return;
        if (!p.hasPermission("casino.play")) {
            plugin.msg(p, "&cNo tienes permiso para jugar.");
            return;
        }

        switch (game) {
            case "slots" -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, String.valueOf(amount), mat.name());
                if (bet != null) plugin.getSlots().play(p, bet);
            }
            case "dice" -> {
                CasinoPlugin.Bet bet = plugin.takeBet(p, String.valueOf(amount), mat.name());
                if (bet != null) plugin.dice(p, bet);
            }
            case "coinflip" -> openCoinMenu(p, amount, mat);
        }
    }

    private void openCoinMenu(Player p, int amount, Material mat) {
        CoinHolder holder = new CoinHolder();
        holder.amount = amount;
        holder.material = mat;
        Inventory inv = Bukkit.createInventory(holder, 9, plugin.color("&6&lCara o Cruz"));
        holder.inv = inv;

        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);
        inv.setItem(COIN_HEADS, named(Material.GOLD_NUGGET, plugin.color("&6&lCARA")));
        inv.setItem(COIN_TAILS, named(Material.IRON_NUGGET, plugin.color("&7&lCRUZ")));
        inv.setItem(4, named(Material.PAPER, plugin.color("&eApuesta: &f" + plugin.fmt(mat, amount))));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof CoinHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;

        int slot = e.getSlot();
        if (slot != COIN_HEADS && slot != COIN_TAILS) return;
        String side = slot == COIN_HEADS ? "cara" : "cruz";

        // Cerramos y jugamos un tick despues para no tocar el inventario dentro del evento
        Bukkit.getScheduler().runTask(plugin, () -> {
            p.closeInventory();
            CasinoPlugin.Bet bet = plugin.takeBet(p, String.valueOf(holder.amount), holder.material.name());
            if (bet != null) plugin.coinflip(p, bet, side);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof CoinHolder) e.setCancelled(true);
    }

    // ---------- Proteccion ----------

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!Tag.SIGNS.isTagged(b.getType())) return;
        if (!(b.getState() instanceof Sign sign)) return;
        if (!sign.getPersistentDataContainer().has(gameKey, PersistentDataType.STRING)) return;

        if (!e.getPlayer().hasPermission("casino.admin")) {
            e.setCancelled(true);
            plugin.msg(e.getPlayer(), "&cNo puedes romper las maquinas del casino.");
        } else {
            plugin.msg(e.getPlayer(), "&7Maquina del casino eliminada.");
        }
    }

    private ItemStack named(Material m, String name) {
        ItemStack item = new ItemStack(m);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }
}
