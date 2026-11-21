package com.casino;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CasinoPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    /** Apuesta ya cobrada: mineral + cantidad. */
    public record Bet(Material material, int amount) {}

    private static final Map<String, Material> ALIASES = Map.ofEntries(
            Map.entry("DIAMANTE", Material.DIAMOND),
            Map.entry("ESMERALDA", Material.EMERALD),
            Map.entry("ORO", Material.GOLD_INGOT),
            Map.entry("HIERRO", Material.IRON_INGOT),
            Map.entry("LAPISLAZULI", Material.LAPIS_LAZULI),
            Map.entry("LAPIS", Material.LAPIS_LAZULI),
            Map.entry("CARBON", Material.COAL),
            Map.entry("NETHERITA", Material.NETHERITE_INGOT)
    );

    private final Map<Material, Integer> allowed = new LinkedHashMap<>();
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private SlotsGame slots;
    private MachineManager machineManager;
    private File pendingFile;
    private YamlConfiguration pending;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadAllowed();
        pendingFile = new File(getDataFolder(), "pending.yml");
        pending = YamlConfiguration.loadConfiguration(pendingFile);

        slots = new SlotsGame(this);
        getServer().getPluginManager().registerEvents(slots, this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new Machines(this), this);
        machineManager = new MachineManager(this);
        getServer().getPluginManager().registerEvents(machineManager, this);
        getCommand("casino").setExecutor(this);
        getCommand("casino").setTabCompleter(this);
    }

    private void loadAllowed() {
        allowed.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("bet.items");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            if (m == null) { getLogger().warning("Material invalido en bet.items: " + key); continue; }
            allowed.put(m, Math.max(1, sec.getInt(key, 16)));
        }
    }

    public Random getRandom() { return random; }
    public SlotsGame getSlots() { return slots; }
    public Map<Material, Integer> getAllowed() { return allowed; }
    public String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    public void msg(Player p, String text) {
        p.sendMessage(color(getConfig().getString("prefix", "&6[Casino] &f")) + color(text));
    }

    public String pretty(Material m) {
        String n = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    public String fmt(Material m, int amount) { return amount + " " + pretty(m); }

    public Material parseMaterial(String in) {
        String key = in.toUpperCase().replace("_", "").replace("Á", "A").replace("É", "E").replace("Í", "I")
                .replace("Ó", "O").replace("Ú", "U");
        if (ALIASES.containsKey(key)) return ALIASES.get(key);
        return Material.matchMaterial(in);
    }

    /** Valida la apuesta, quita los minerales del inventario y devuelve la apuesta (o null). */
    public Bet takeBet(Player p, String amountStr, String itemStr) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("bet.cooldown-seconds", 3) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            msg(p, "&cEspera un momento antes de volver a apostar.");
            return null;
        }

        int amount;
        try { amount = Integer.parseInt(amountStr); }
        catch (NumberFormatException e) { msg(p, "&cCantidad invalida."); return null; }

        Material mat = parseMaterial(itemStr);
        if (mat == null || !allowed.containsKey(mat)) {
            msg(p, "&cEse mineral no se puede apostar. Opciones: &e" + allowedNames());
            return null;
        }
        int max = allowed.get(mat);
        if (amount < 1 || amount > max) {
            msg(p, "&cPuedes apostar entre &e1 &cy &e" + max + " " + pretty(mat) + "&c.");
            return null;
        }
        ItemStack stack = new ItemStack(mat, amount);
        if (!p.getInventory().containsAtLeast(new ItemStack(mat), amount)) {
            msg(p, "&cNo tienes &e" + fmt(mat, amount) + "&c en tu inventario.");
            return null;
        }
        // removeItem devuelve lo que no pudo quitar; si algo falla, no se juega
        Map<Integer, ItemStack> notRemoved = p.getInventory().removeItem(stack);
        if (!notRemoved.isEmpty()) {
            int missing = notRemoved.values().stream().mapToInt(ItemStack::getAmount).sum();
            // devolvemos lo que se llego a quitar
            giveNow(p, mat, amount - missing);
            msg(p, "&cNo se pudo cobrar la apuesta.");
            return null;
        }
        cooldowns.put(p.getUniqueId(), now);
        return new Bet(mat, amount);
    }

    public String allowedNames() {
        StringJoiner j = new StringJoiner(", ");
        for (Material m : allowed.keySet()) j.add(m.name().toLowerCase());
        return j.toString();
    }

    /** Entrega premio; si el jugador esta desconectado se guarda hasta que entre. */
    public void give(UUID id, Material m, int amount) {
        if (amount <= 0) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            giveNow(p, m, amount);
        } else {
            String path = id + "." + m.name();
            pending.set(path, pending.getInt(path, 0) + amount);
            savePending();
        }
    }

    private void giveNow(Player p, Material m, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int n = Math.min(remaining, m.getMaxStackSize());
            Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, n));
            // Si el inventario esta lleno, se tira al suelo junto al jugador
            for (ItemStack s : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), s);
            remaining -= n;
        }
    }

    private void savePending() {
        try { pending.save(pendingFile); }
        catch (IOException e) { getLogger().severe("No se pudo guardar pending.yml: " + e.getMessage()); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        if (!pending.contains(id.toString())) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(id);
            ConfigurationSection sec = pending.getConfigurationSection(id.toString());
            if (p == null || sec == null) return;
            for (String key : sec.getKeys(false)) {
                Material m = Material.matchMaterial(key);
                if (m != null) {
                    int amt = sec.getInt(key);
                    giveNow(p, m, amt);
                    msg(p, "&aRecibes tus premios pendientes: &e" + fmt(m, amt));
                }
            }
            pending.set(id.toString(), null);
            savePending();
        }, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("casino.admin")) {
                sender.sendMessage(ChatColor.RED + "No tienes permiso.");
                return true;
            }
            reloadConfig();
            loadAllowed();
            slots.reload();
            sender.sendMessage(ChatColor.GREEN + "Casino recargado.");
            return true;
        }

        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        if (!p.hasPermission("casino.play")) { msg(p, "&cNo tienes permiso para jugar."); return true; }
        if (args.length == 0) { help(p); return true; }

        switch (args[0].toLowerCase()) {
            case "slots", "tragaperras" -> {
                if (args.length < 3) { msg(p, "&eUso: /casino slots <cantidad> <mineral>"); return true; }
                Bet bet = takeBet(p, args[1], args[2]);
                if (bet != null) slots.play(p, bet);
            }
            case "coinflip", "moneda" -> {
                if (args.length < 4) { msg(p, "&eUso: /casino coinflip <cantidad> <mineral> <cara|cruz>"); return true; }
                String side = args[3].toLowerCase();
                if (!side.equals("cara") && !side.equals("cruz")) { msg(p, "&cElige &ecara &co &ecruz&c."); return true; }
                Bet bet = takeBet(p, args[1], args[2]);
                if (bet != null) coinflip(p, bet, side);
            }
            case "construir", "build" -> {
                if (!p.hasPermission("casino.admin")) { msg(p, "&cNo tienes permiso."); return true; }
                if (args.length < 4) {
                    msg(p, "&eUso: /casino construir <tragaperras|ruleta> <cantidad> <mineral>");
                    return true;
                }
                machineManager.build(p, args[1], args[2], args[3]);
            }
            case "dados", "dice" -> {
                if (args.length < 3) { msg(p, "&eUso: /casino dados <cantidad> <mineral>"); return true; }
                Bet bet = takeBet(p, args[1], args[2]);
                if (bet != null) dice(p, bet);
            }
            default -> help(p);
        }
        return true;
    }

    private void help(Player p) {
        msg(p, "&6&lCasino &7- Apuesta minerales reales:");
        p.sendMessage(color("&e/casino slots <cantidad> <mineral> &7- Tragaperras"));
        p.sendMessage(color("&e/casino coinflip <cantidad> <mineral> <cara|cruz>"));
        p.sendMessage(color("&e/casino dados <cantidad> <mineral>"));
        if (p.hasPermission("casino.admin"))
            p.sendMessage(color("&e/casino construir <tragaperras|ruleta> <cantidad> <mineral> &7- Construye una maquina"));
        p.sendMessage(color("&7Minerales: &f" + allowedNames()));
        p.sendMessage(color("&7Ejemplo: &f/casino slots 3 diamante"));
    }

    public void coinflip(Player p, Bet bet, String chosen) {
        boolean win = random.nextDouble() < getConfig().getDouble("coinflip.win-chance", 0.48);
        String result = win ? chosen : (chosen.equals("cara") ? "cruz" : "cara");
        UUID id = p.getUniqueId();
        msg(p, "&7Lanzas la moneda al aire...");

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player on = Bukkit.getPlayer(id);
                if (frame < 10) {
                    if (on != null) {
                        on.sendTitle(color(frame % 2 == 0 ? "&6CARA" : "&7CRUZ"), "", 0, 8, 0);
                        on.playSound(on.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.6f);
                    }
                    frame++;
                    return;
                }
                cancel();
                if (win) {
                    int prize = bet.amount() * 2;
                    give(id, bet.material(), prize);
                    if (on != null) {
                        on.sendTitle(color("&a¡" + result.toUpperCase() + "!"), color("&eGanaste " + fmt(bet.material(), prize)), 0, 40, 10);
                        msg(on, "&7Salio &e" + result + "&7. &a¡Ganaste " + fmt(bet.material(), prize) + "!");
                        on.playSound(on.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    }
                } else if (on != null) {
                    on.sendTitle(color("&c" + result.toUpperCase()), color("&7Perdiste " + fmt(bet.material(), bet.amount())), 0, 40, 10);
                    msg(on, "&7Salio &e" + result + "&7. &cPerdiste " + fmt(bet.material(), bet.amount()) + ".");
                    on.playSound(on.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    public void dice(Player p, Bet bet) {
        int mine = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        int house = random.nextInt(6) + 1 + random.nextInt(6) + 1;
        UUID id = p.getUniqueId();
        msg(p, "&7Tiras los dados...");

        new BukkitRunnable() {
            int frame = 0;

            @Override
            public void run() {
                Player on = Bukkit.getPlayer(id);
                if (frame < 8) {
                    if (on != null) {
                        on.sendTitle(color("&e" + (random.nextInt(11) + 2)), color("&7Casa: &e?"), 0, 8, 0);
                        on.playSound(on.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 1.4f);
                    }
                    frame++;
                    return;
                }
                cancel();
                String info = "&7Tu: &e" + mine + " &7| Casa: &e" + house;
                if (mine > house) {
                    int prize = (int) Math.floor(bet.amount() * getConfig().getDouble("dice.win-multiplier", 2.0));
                    give(id, bet.material(), prize);
                    if (on != null) {
                        on.sendTitle(color("&a" + mine + " vs " + house), color("&eGanaste " + fmt(bet.material(), prize)), 0, 40, 10);
                        msg(on, info + "&7. &a¡Ganaste " + fmt(bet.material(), prize) + "!");
                        on.playSound(on.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                    }
                } else if (on != null) {
                    on.sendTitle(color("&c" + mine + " vs " + house),
                            color(mine == house ? "&7Empate: gana la casa" : "&7Perdiste " + fmt(bet.material(), bet.amount())), 0, 40, 10);
                    msg(on, info + "&7. " + (mine == house ? "&cEmpate: gana la casa. " : "&cPerdiste. ")
                            + "&c-" + fmt(bet.material(), bet.amount()));
                    on.playSound(on.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                }
            }
        }.runTaskTimer(this, 0L, 3L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("slots", "coinflip", "dados", "construir", "reload"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("construir")) {
            for (String s : List.of("tragaperras", "ruleta"))
                if (s.startsWith(args[1].toLowerCase())) out.add(s);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("construir")) {
            for (Material m : allowed.keySet())
                if (m.name().toLowerCase().startsWith(args[3].toLowerCase())) out.add(m.name().toLowerCase());
        } else if (args.length == 3 && !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("construir")) {
            for (Material m : allowed.keySet())
                if (m.name().toLowerCase().startsWith(args[2].toLowerCase())) out.add(m.name().toLowerCase());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("coinflip")) {
            for (String s : List.of("cara", "cruz"))
                if (s.startsWith(args[3].toLowerCase())) out.add(s);
        }
        return out;
    }
}
