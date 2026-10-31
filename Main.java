package com.example.smp;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Beacon;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.event.block.BlockExplodeEvent;
import java.time.LocalDate;
import java.time.ZoneId;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class Main extends JavaPlugin implements Listener {
    private static final int RADIUS = 50, BUFF_RADIUS = 500, DIAMOND_COST = 64;
    private static final long COMBAT_TAG_MS = 3 * 60 * 1000L;
    private final Map<UUID, Long> combatTaggedUntil = new HashMap<>();
    private static final long BEACON_TP_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final long GRACE_MS = 60 * 60 * 1000L, BREAK_TICKS = 120 * 20L;
    private static final double BASE_SPEED = 4.0, SPECIAL_SPEED = 1024.0;
    // Protection X armor health bonuses (half of v1.4).
    private static final UUID PROT10_HEALTH_MODIFIER_ID = UUID.fromString("5c27b0f4-fc1d-4e2a-9436-7d4b6d5e1010");
    private static final double PROT10_BONUS_HEALTH = 5.0;
    // Chestplate +20 HP (10 hearts); leggings +5 HP (2.5 hearts).
    private static final UUID PROT10_CHEST_MODIFIER_ID = UUID.fromString("5c27b0f4-fc1d-4e2a-9436-7d4b6d5e1020");
    private static final UUID PROT10_LEGS_MODIFIER_ID = UUID.fromString("5c27b0f4-fc1d-4e2a-9436-7d4b6d5e1030");
    private final Set<UUID> speedBootsApplied = new HashSet<>();
    private final Random random = new Random();
    private final Map<UUID, Integer> dailyTrades = new HashMap<>();
    private final Map<UUID, Integer> beaconPurchases = new HashMap<>();
    private final Map<UUID, Long> beaconTpLastUsed = new HashMap<>();
    private String tradeDate = LocalDate.now().toString();
    private final Set<UUID> potionWarning = new HashSet<>();
    private final Set<UUID> initialized = new HashSet<>(), everPlaced = new HashSet<>(), permanentlyLost = new HashSet<>();
    private final Map<UUID, Location> beacons = new HashMap<>(), starts = new HashMap<>();
    private final Map<UUID, Location> teamSpawns = new HashMap<>();
    private final Map<String, UUID> beaconOwners = new HashMap<>();
    private final Map<UUID, List<Location>> barrierColumns = new HashMap<>();
    private final Map<UUID, UUID> merged = new HashMap<>();
    private final Set<UUID> manualTeamMembers = new HashSet<>();
    private final Map<UUID, Boolean> previouslyActive = new HashMap<>();
    private final Map<UUID, BukkitTask> breakTasks = new HashMap<>();
    private final Map<String, Long> alerts = new HashMap<>();
    private final Set<UUID> penaltyApplied = new HashSet<>();
    private long startedAt = -1;
    private File stateFile;
    @Override public void onEnable() {
        stateFile = new File(getDataFolder(), "state.yml");
        loadState();
        Bukkit.getPluginManager().registerEvents(this, this);
        forceHard();
        Bukkit.getScheduler().runTaskTimer(this, this::enforcePotionLimit, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::forceHard, 20L, 200L);
        Bukkit.getScheduler().runTaskTimer(this, this::saveState, 1200L, 1200L);
        getLogger().info("SMP 1.19.4 enabled");
    }
    @Override public void onDisable() {
        cancelAllBreaks();
        for (Player p : Bukkit.getOnlinePlayers()) { setSpeed(p, BASE_SPEED); removeArmorBonuses(p); removeBootsSpeed(p); }
        saveState();
    }
    private void forceHard() { for (World w : Bukkit.getWorlds()) w.setDifficulty(Difficulty.HARD); }
    @EventHandler public void onWorldLoad(WorldLoadEvent e) { e.getWorld().setDifficulty(Difficulty.HARD); }

    // v1.12 balance patch
    private boolean banned(Enchantment e) {
        return Enchantment.MENDING.equals(e) || Enchantment.ARROW_INFINITE.equals(e);
    }
    private boolean containsBanned(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return false;
        if (stack.getEnchantments().keySet().stream().anyMatch(this::banned)) return true;
        return stack.getItemMeta() instanceof EnchantmentStorageMeta &&
                ((EnchantmentStorageMeta) stack.getItemMeta()).getStoredEnchants().keySet().stream().anyMatch(this::banned);
    }
    private boolean isPotion(ItemStack stack) {
        return stack != null && (stack.getType() == Material.POTION || stack.getType() == Material.SPLASH_POTION ||
                stack.getType() == Material.LINGERING_POTION);
    }
    private int countPotions(Player p) {
        int count = 0;
        for (ItemStack item : p.getInventory().getStorageContents()) if (isPotion(item)) count += item.getAmount();
        for (ItemStack item : p.getInventory().getExtraContents()) if (isPotion(item)) count += item.getAmount();
        return count;
    }
    private void enforcePotionLimit() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int extra = countPotions(p) - 2;
            if (extra <= 0) { potionWarning.remove(p.getUniqueId()); continue; }
            // Remove only excess, then drop it at player's feet (never delete paid items).
            PlayerInventory inv = p.getInventory();
            for (int slot = 0; slot < inv.getSize() && extra > 0; slot++) {
                ItemStack item = inv.getItem(slot);
                if (!isPotion(item)) continue;
                int taken = Math.min(extra, item.getAmount());
                ItemStack drop = item.clone(); drop.setAmount(taken);
                if (item.getAmount() == taken) inv.setItem(slot, null);
                else { item.setAmount(item.getAmount() - taken); inv.setItem(slot, item); }
                p.getWorld().dropItemNaturally(p.getLocation(), drop);
                extra -= taken;
            }
            if (potionWarning.add(p.getUniqueId())) p.sendMessage("§c포션은 최대 2개만 휴대할 수 있어 초과분을 바닥에 떨어뜨렸습니다.");
        }
    }
    private void resetTradeDay() {
        String today = LocalDate.now().toString();
        if (!today.equals(tradeDate)) { tradeDate = today; dailyTrades.clear(); saveState(); }
    }
    @EventHandler(ignoreCancelled = true) public void onTradeClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        if (e.getView().getTopInventory() instanceof MerchantInventory && e.getRawSlot() == 2) {
            resetTradeDay();
            if (dailyTrades.getOrDefault(p.getUniqueId(), 0) >= 20) {
                e.setCancelled(true); p.sendMessage("§c주민 거래는 하루 최대 20회입니다."); return;
            }
            ItemStack result = e.getCurrentItem();
            if (result != null && result.getType() != Material.AIR) {
                if (containsBanned(result)) { e.setCancelled(true); p.sendMessage("§c수선과 무한 인챈트는 금지되어 있습니다."); return; }
                // Schedule a count only after Bukkit has processed the trade.
                Bukkit.getScheduler().runTask(this, () -> {
                    dailyTrades.merge(p.getUniqueId(), 1, Integer::sum);
                    saveState();
                });
            }
        }
    }
    @EventHandler(ignoreCancelled = true) public void onEnchant(EnchantItemEvent e) {
        if (e.getEnchantsToAdd().keySet().stream().anyMatch(this::banned)) {
            e.setCancelled(true); e.getEnchanter().sendMessage("§c수선과 무한 인챈트는 금지되어 있습니다.");
        }
    }
    @EventHandler(ignoreCancelled = true) public void onPotionPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player) || !isPotion(e.getItem().getItemStack())) return;
        Player p = (Player) e.getEntity();
        if (countPotions(p) + e.getItem().getItemStack().getAmount() > 2) {
            e.setCancelled(true); p.sendMessage("§c포션은 최대 2개만 휴대할 수 있습니다.");
        }
    }
    @EventHandler(ignoreCancelled = true) public void onGoldenApple(org.bukkit.event.player.PlayerItemConsumeEvent e) {
        if (e.getItem().getType() != Material.GOLDEN_APPLE) return;
        Player p = e.getPlayer();
        // Vanilla golden apple regeneration begins after consuming the item.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            PotionEffect regen = p.getPotionEffect(PotionEffectType.REGENERATION);
            if (regen != null && regen.getDuration() > 40 && regen.getAmplifier() == 1)
                p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 40, 1,
                        regen.isAmbient(), regen.hasParticles(), regen.hasIcon()), true);
        }, 1L);
    }
    private boolean reducedExplosion(World.Environment dimension, Material source) {
        if (source == Material.RESPAWN_ANCHOR) return dimension != World.Environment.NETHER;
        if (source == Material.RED_BED || source.name().endsWith("_BED")) return dimension != World.Environment.NORMAL;
        return source == Material.END_CRYSTAL && dimension == World.Environment.THE_END;
    }
    @EventHandler(ignoreCancelled = true) public void onBlockExplosionDamage(EntityDamageByBlockEvent e) {
        if (e.getDamager() == null) return;
        Material type = e.getDamager().getType();
        if (reducedExplosion(e.getEntity().getWorld().getEnvironment(), type)) e.setDamage(e.getDamage() * 0.25);
    }
    @EventHandler(ignoreCancelled = true) public void onCrystalExplosionDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof EnderCrystal &&
                e.getEntity().getWorld().getEnvironment() == World.Environment.THE_END)
            e.setDamage(e.getDamage() * 0.25);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "teamset":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                if (args.length < 1) { sender.sendMessage("사용법: /teamset <팀장> [팀원1] [팀원2] ..."); return true; }
                Player leader = Bukkit.getPlayerExact(args[0]);
                if (leader == null) { sender.sendMessage("팀장은 온라인 상태여야 합니다."); return true; }
                UUID leaderId = leader.getUniqueId();
                if (!finalOwner(leaderId).equals(leaderId) || permanentlyLost.contains(leaderId)) {
                    sender.sendMessage("다른 팀에 소속되거나 점령당한 플레이어는 팀장이 될 수 없습니다."); return true;
                }
                if (startedAt != -1 && !initialized.isEmpty()) { sender.sendMessage("§c팀 편성은 /startgame 전에 완료해 주세요."); return true; }
                for (int i = 1; i < args.length; i++) {
                    Player member = Bukkit.getPlayerExact(args[i]);
                    if (member == null) { sender.sendMessage("§c접속하지 않은 플레이어: " + args[i]); continue; }
                    UUID memberId = member.getUniqueId();
                    if (memberId.equals(leaderId)) { sender.sendMessage("팀장은 팀원으로 추가할 수 없습니다."); continue; }
                    if (permanentlyLost.contains(memberId)) { sender.sendMessage("§c점령당한 플레이어는 수동 팀 변경 불가: " + member.getName()); continue; }
                    if (beacons.containsKey(memberId)) { sender.sendMessage("§c자신의 신호기가 있는 플레이어는 먼저 신호기를 정리해야 합니다: " + member.getName()); continue; }
                    if (!finalOwner(memberId).equals(memberId) && !manualTeamMembers.contains(memberId)) {
                        sender.sendMessage("§c점령으로 병합된 플레이어는 이동할 수 없습니다: " + member.getName()); continue;
                    }
                    merged.put(memberId, leaderId);
                    manualTeamMembers.add(memberId);
                    sender.sendMessage("§a" + member.getName() + " → " + leader.getName() + " 팀에 추가");
                    member.sendMessage("§a" + leader.getName() + " 팀에 배정되었습니다.");
                }
                saveState(); return true;
            case "teamleader":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                if (args.length != 2) { sender.sendMessage("사용법: /teamleader <현재팀장> <새팀장>"); return true; }
                Player oldLeader = Bukkit.getPlayerExact(args[0]);
                Player newLeader = Bukkit.getPlayerExact(args[1]);
                if (oldLeader == null || newLeader == null) { sender.sendMessage("두 플레이어 모두 접속해야 합니다."); return true; }
                UUID oldId = oldLeader.getUniqueId(), newId = newLeader.getUniqueId();
                if (startedAt != -1 && !initialized.isEmpty()) { sender.sendMessage("§c팀장 변경은 /startgame 전에 완료해 주세요."); return true; }
                if (oldId.equals(newId) || !finalOwner(oldId).equals(oldId) || !finalOwner(newId).equals(oldId) || permanentlyLost.contains(newId)) {
                    sender.sendMessage("같은 팀의 팀원에게만 팀장을 넘길 수 있습니다."); return true;
                }
                for (Map.Entry<UUID, UUID> entry : merged.entrySet()) {
                    if (entry.getValue().equals(oldId)) entry.setValue(newId);
                }
                merged.remove(newId);
                merged.put(oldId, newId);
                manualTeamMembers.remove(newId);
                manualTeamMembers.add(oldId);
                Location previousSpawn = teamSpawns.remove(oldId);
                if (previousSpawn != null) teamSpawns.put(newId, previousSpawn);
                Location previousBeacon = beacons.remove(oldId);
                if (previousBeacon != null) {
                    beacons.put(newId, previousBeacon); beaconOwners.put(key(previousBeacon), newId);
                    Boolean active = previouslyActive.remove(oldId);
                    if (active != null) previouslyActive.put(newId, active);
                    List<Location> columns = barrierColumns.remove(oldId);
                    if (columns != null) barrierColumns.put(newId, columns);
                }
                saveState(); sender.sendMessage("§a새 팀장: " + newLeader.getName()); return true;
            case "teamclear":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                if (args.length != 1) { sender.sendMessage("사용법: /teamclear <팀원>"); return true; }
                Player leaving = Bukkit.getPlayerExact(args[0]);
                if (leaving == null) { sender.sendMessage("온라인 플레이어만 지정할 수 있습니다."); return true; }
                if (startedAt != -1 && !initialized.isEmpty()) { sender.sendMessage("§c팀 변경은 /startgame 전에 완료해 주세요."); return true; }
                if (!manualTeamMembers.remove(leaving.getUniqueId())) { sender.sendMessage("수동 배정된 팀원이 아닙니다."); return true; }
                merged.remove(leaving.getUniqueId());
                saveState(); sender.sendMessage("§a" + leaving.getName() + "의 수동 팀 배정을 해제했습니다."); return true;
            case "startgame":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                cancelAllBreaks();
                for (UUID owner : new HashSet<>(barrierColumns.keySet())) removeBarrierColumn(owner);
                combatTaggedUntil.clear();
                initialized.clear(); everPlaced.clear(); permanentlyLost.clear(); beacons.clear(); starts.clear(); beaconPurchases.clear(); beaconTpLastUsed.clear();
                beaconOwners.clear(); previouslyActive.clear(); alerts.clear(); penaltyApplied.clear(); teamSpawns.clear();
                // Keep teams configured before /startgame. Captured/defeated teams reset.
                merged.entrySet().removeIf(entry -> !manualTeamMembers.contains(entry.getKey()));
                permanentlyLost.clear();
                startedAt = System.currentTimeMillis();
                saveState();
                Bukkit.broadcastMessage("§aSMP 시작! 60분 안에 신호기를 설치하세요.");
                for (Player p : Bukkit.getOnlinePlayers()) initializePlayer(p);
                return true;
            case "beacontp":
                if (!(sender instanceof Player)) { sender.sendMessage("플레이어 전용입니다."); return true; }
                Player p = (Player) sender;
                Location b = beacons.get(finalOwner(p.getUniqueId()));
                if (b == null || b.getBlock().getType() != Material.BEACON) { p.sendMessage("이동할 신호기가 없습니다."); return true; }
                long now = System.currentTimeMillis();
                long lastUsed = beaconTpLastUsed.getOrDefault(p.getUniqueId(), 0L);
                long remaining = BEACON_TP_COOLDOWN_MS - (now - lastUsed);
                if (remaining > 0) {
                    long minutes = (remaining + 59999L) / 60000L;
                    p.sendMessage("§c/beacontp는 30분에 한 번 사용할 수 있습니다. 남은 시간: 약 " + minutes + "분");
                    return true;
                }
                Location safe = safeNear(b);
                if (safe == null) { p.sendMessage("신호기 주변에 안전한 공간이 없습니다."); return true; }
                if (p.teleport(safe)) {
                    beaconTpLastUsed.put(p.getUniqueId(), now);
                    saveState();
                    p.sendMessage("§a신호기 근처로 이동했습니다. 다음 사용은 30분 후 가능합니다.");
                } else p.sendMessage("§c순간이동에 실패했습니다. 재사용 대기시간은 적용되지 않습니다.");
                return true;
            case "buybeacon":
                if (!(sender instanceof Player)) { sender.sendMessage("플레이어 전용입니다."); return true; }
                buy((Player) sender); return true;
            default: return false;
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!p.isOnline()) return;
            if (startedAt != -1 && !initialized.contains(p.getUniqueId())) initializePlayer(p);
            applySword(p); applyArmorBonuses(p); applyBootsSpeed(p); applyPenalty(p);
        }, 20L);
    }
    // A server cannot prevent the client from disconnecting. Combat logging is
    // instead penalized as a death, including the normal death/XP-drop rules.
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        cancelBreak(p.getUniqueId());
        removeBootsSpeed(p);
        Long until = combatTaggedUntil.remove(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis() && !p.isDead() && p.getHealth() > 0) {
            Bukkit.broadcastMessage("§c" + p.getName() + "님이 전투 중 접속 종료하여 사망 처리되었습니다.");
            p.setHealth(0.0);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCombatTag(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player) || e.getFinalDamage() <= 0) return;
        Player victim = (Player) e.getEntity();
        Player attacker = null;
        if (e.getDamager() instanceof Player) attacker = (Player) e.getDamager();
        else if (e.getDamager() instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) e.getDamager()).getShooter();
            if (shooter instanceof Player) attacker = (Player) shooter;
        }
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        long expires = System.currentTimeMillis() + COMBAT_TAG_MS;
        combatTaggedUntil.put(victim.getUniqueId(), expires);
        combatTaggedUntil.put(attacker.getUniqueId(), expires);
        victim.sendMessage("§c전투 중! 마지막 플레이어 피해로부터 3분간 접속 종료하면 사망 처리됩니다.");
        attacker.sendMessage("§e전투 상태: 3분간 접속 종료하면 사망 처리됩니다.");
    }
    private void initializePlayer(Player p) {
        UUID owner = finalOwner(p.getUniqueId());
        Location spawn = teamSpawns.get(owner);
        if (spawn == null) {
            spawn = safeRandomSpawn();
            if (spawn == null) {
                p.sendMessage("§c다른 팀과 2000블록 이상 떨어진 안전한 스폰 위치를 찾지 못했습니다.");
                return;
            }
            teamSpawns.put(owner, spawn.clone());
        }
        if (!p.teleport(spawn.clone())) return;
        initialized.add(p.getUniqueId());
        starts.put(p.getUniqueId(), spawn.clone());
        if (p.getUniqueId().equals(owner)) {
            give(p, new ItemStack(Material.BEACON));
            p.sendMessage("§a팀장에게 신호기 1개가 지급되었습니다.");
        } else {
            p.sendMessage("§a팀원은 신호기를 받지 않습니다. 팀장과 함께 시작합니다.");
        }
        p.sendMessage("§a팀 스폰: " + spawn.getBlockX() + ", " + spawn.getBlockY() + ", " + spawn.getBlockZ());
        saveState();
    }
    private Location safeRandomSpawn() {
        World w = Bukkit.getWorlds().stream().filter(x -> x.getEnvironment() == World.Environment.NORMAL).findFirst().orElse(null);
        if (w == null) return null;
        Location center = w.getSpawnLocation();
        for (int attempt = 0; attempt < 1200; attempt++) {
            int x = random.nextInt(20001) - 10000, z = random.nextInt(20001) - 10000;
            if (Math.hypot(x - center.getX(), z - center.getZ()) < 3000) continue;
            boolean nearTeam = false;
            for (Location other : teamSpawns.values()) {
                if (other.getWorld() != null && other.getWorld().equals(w) &&
                        Math.hypot(x - other.getX(), z - other.getZ()) < 2000) {
                    nearTeam = true; break;
                }
            }
            if (nearTeam) continue;
            if (!w.getWorldBorder().isInside(new Location(w, x, 80, z))) continue;
            int y = w.getHighestBlockYAt(x, z) + 1;
            if (y <= w.getMinHeight() + 2 || y + 2 >= w.getMaxHeight()) continue;
            Block ground = w.getBlockAt(x, y - 1, z);
            if (!ground.getType().isSolid() || ground.isLiquid() || ground.getType().name().contains("LEAVES")
                    || ground.getType() == Material.MAGMA_BLOCK || ground.getType() == Material.CACTUS) continue;
            Location loc = new Location(w, x + .5, y, z + .5);
            if (clearForPlayer(loc)) return loc;
        }
        return null;
    }
    private boolean clearForPlayer(Location l) {
        Block feet = l.getBlock(), head = feet.getRelative(0, 1, 0);
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid();
    }
    private Location safeNear(Location b) {
        World w = b.getWorld();
        if (w == null) return null;
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                for (int dy = 2; dy >= -2; dy--) {
                    Location l = new Location(w, b.getBlockX() + dx + .5, b.getBlockY() + dy, b.getBlockZ() + dz + .5);
                    if (clearForPlayer(l) && l.getBlock().getRelative(0, -1, 0).getType().isSolid()) return l;
                }
            }
        }
        return null;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (e.getBlockPlaced().getType() != Material.BEACON) return;
        Player p = e.getPlayer(); UUID id = p.getUniqueId();
        if (startedAt == -1) { e.setCancelled(true); p.sendMessage("게임이 시작되지 않았습니다."); return; }
        if (permanentlyLost.contains(id) || !finalOwner(id).equals(id)) { e.setCancelled(true); p.sendMessage("병합된 플레이어는 신호기를 설치할 수 없습니다."); return; }
        if (beacons.containsKey(id)) { e.setCancelled(true); p.sendMessage("이미 신호기가 있습니다."); return; }
        Block block = e.getBlockPlaced();
        if (block.getWorld().getEnvironment() != World.Environment.NORMAL || block.getY() >= block.getWorld().getSeaLevel()) {
            e.setCancelled(true); p.sendMessage("오버월드 해수면 아래에만 설치할 수 있습니다."); return;
        }
        Location l = block.getLocation();
        beacons.put(id, l); beaconOwners.put(key(l), id); everPlaced.add(id); previouslyActive.put(id, false);
        penaltyApplied.remove(id); p.removePotionEffect(PotionEffectType.WEAKNESS);
        p.sendMessage("신호기 등록 완료! §b광선 통로 주변에 배리어 기둥을 생성합니다. 성급함 III 범위: 500블록.");
        Bukkit.getScheduler().runTask(this, () -> {
            if (l.getBlock().getType() == Material.BEACON && id.equals(beaconOwners.get(key(l)))) {
                createBarrierColumn(id, l);
                saveState();
            }
        });
        saveState();
    }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) {
        if (isManagedBarrier(e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage("§c신호기 보호용 배리어는 직접 파괴할 수 없습니다.");
            return;
        }
        UUID owner = beaconOwners.get(key(e.getBlock().getLocation()));
        if (owner == null) return;
        if (!finalOwner(e.getPlayer().getUniqueId()).equals(owner)) { e.setCancelled(true); return; }
        unregisterBeacon(owner, false); e.getPlayer().sendMessage("신호기를 회수했습니다.");
    }
    @EventHandler(ignoreCancelled = true) public void onDamageBlock(BlockDamageEvent e) {
        Block block = e.getBlock(); UUID owner = beaconOwners.get(key(block.getLocation()));
        if (owner == null || finalOwner(e.getPlayer().getUniqueId()).equals(owner)) return;
        e.setCancelled(true);
        Player attacker = e.getPlayer(); UUID attackerId = attacker.getUniqueId();
        if (breakTasks.containsKey(attackerId)) { attacker.sendMessage("이미 신호기 파괴를 진행 중입니다."); return; }
        final String targetKey = key(block.getLocation());
        final long start = System.currentTimeMillis();
        attacker.sendMessage("§c신호기 파괴 시작! 120초 동안 4.5블록 이내에 머무르세요.");
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!attacker.isOnline() || attacker.isDead() || !targetKey.equals(key(block.getLocation()))
                    || block.getType() != Material.BEACON || !owner.equals(beaconOwners.get(targetKey))
                    || !attacker.getWorld().equals(block.getWorld()) || attacker.getLocation().distanceSquared(block.getLocation().add(.5,.5,.5)) > 4.5 * 4.5) {
                if (attacker.isOnline()) attacker.sendMessage("신호기 파괴가 취소되었습니다.");
                cancelBreak(attackerId); return;
            }
            long elapsed = (System.currentTimeMillis() - start) / 50L;
            long remain = Math.max(0, (BREAK_TICKS - elapsed + 19) / 20);
            if (remain > 0 && (remain % 10 == 0 || remain <= 5)) attacker.sendMessage("남은 시간: " + remain + "초");
            if (elapsed < BREAK_TICKS) return;
            UUID winner = finalOwner(attackerId);
            block.setType(Material.AIR, false);
            unregisterBeacon(owner, true);
            merged.put(owner, winner); manualTeamMembers.remove(owner); permanentlyLost.add(owner);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (finalOwner(online.getUniqueId()).equals(winner)) online.sendMessage("§a신호기 점령 성공!");
            }
            Player victim = Bukkit.getPlayer(owner);
            if (victim != null) victim.sendMessage("§c신호기가 파괴되어 다른 세력에 병합되었습니다.");
            cancelBreak(attackerId); saveState();
        }, 1L, 20L);
        breakTasks.put(attackerId, task);
    }
    private void cancelBreak(UUID id) { BukkitTask task = breakTasks.remove(id); if (task != null) task.cancel(); }
    private void cancelAllBreaks() { for (BukkitTask task : breakTasks.values()) task.cancel(); breakTasks.clear(); }
    private void unregisterBeacon(UUID owner, boolean destroyedByEnemy) {
        removeBarrierColumn(owner);
        Location l = beacons.remove(owner);
        if (l != null) beaconOwners.remove(key(l));
        previouslyActive.remove(owner);
        if (!destroyedByEnemy) { Player p = Bukkit.getPlayer(owner); if (p != null) p.sendMessage("신호기가 없어졌습니다. /buybeacon으로 재구매할 수 있습니다."); }
        saveState();
    }
    private void tick() {
        if (startedAt == -1) return;
        for (UUID owner : new HashSet<>(beacons.keySet())) {
            Location l = beacons.get(owner); if (l == null) continue;
            Block block = l.getBlock();
            if (block.getType() != Material.BEACON) { unregisterBeacon(owner, false); continue; }
            boolean active = block.getState() instanceof Beacon && ((Beacon) block.getState()).getTier() > 0;
            if (active) previouslyActive.put(owner, true);
            else if (previouslyActive.getOrDefault(owner, false)) {
                block.setType(Material.AIR, false); unregisterBeacon(owner, false);
                Player p = Bukkit.getPlayer(owner); if (p != null) p.sendMessage("§c활성화된 신호기가 비활성화되어 사라졌습니다.");
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPenalty(p); applySword(p); applyArmorBonuses(p); applyBootsSpeed(p); applyZone(p);
            if (System.currentTimeMillis() / 600000L != (System.currentTimeMillis() - 1000L) / 600000L && !beacons.containsKey(finalOwner(p.getUniqueId()))
                    && !permanentlyLost.contains(p.getUniqueId()) && finalOwner(p.getUniqueId()).equals(p.getUniqueId())) {
                sendBuyLink(p);
            }
        }
    }
    private void applyPenalty(Player p) {
        if (startedAt == -1 || System.currentTimeMillis() - startedAt < GRACE_MS) return;
        UUID id = p.getUniqueId();
        if (everPlaced.contains(finalOwner(id))) return;
        if (penaltyApplied.add(id)) p.sendMessage("§c60분 안에 신호기를 설치하지 않아 나약함이 적용되었습니다.");
        p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 0, false, false));
    }
    private void applyZone(Player p) {
        boolean intruder = false, haste = false;
        for (Map.Entry<UUID, Location> entry : beacons.entrySet()) {
            Location b = entry.getValue();
            if (!p.getWorld().equals(b.getWorld())) continue;
            double distanceSquared = p.getLocation().distanceSquared(b);
            boolean sameTeam = finalOwner(p.getUniqueId()).equals(entry.getKey());
            if (sameTeam && distanceSquared <= BUFF_RADIUS * BUFF_RADIUS && isTierOneOrHigher(b)) {
                haste = true;
            }
            if (!sameTeam && distanceSquared <= RADIUS * RADIUS) {
                intruder = true;
                String k = entry.getKey() + ":" + p.getUniqueId(); long now = System.currentTimeMillis();
                if (now - alerts.getOrDefault(k, 0L) > 10000L) {
                    Player owner = Bukkit.getPlayer(entry.getKey());
                    if (owner != null) owner.sendMessage("§c침입자 감지! /beacontp 로 귀환하세요.");
                    alerts.put(k, now);
                }
            }
        }
        if (haste) p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 45, 2, false, false));
        if (intruder) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 40, 0, false, false));
        }
    }
    private boolean isTierOneOrHigher(Location l) {
        Block block = l.getBlock();
        return block.getType() == Material.BEACON
                && block.getState() instanceof Beacon
                && ((Beacon) block.getState()).getTier() >= 1;
    }
    // Barrier is opaque in vanilla Minecraft. Keep the beacon's vertical beam at the
    // center unobstructed; build four adjacent, invisible barrier rails around it.
    // Only replace AIR and remember every block we created for safe restoration.
    private void createBarrierColumn(UUID owner, Location beacon) {
        removeBarrierColumn(owner);
        World w = beacon.getWorld();
        if (w == null) return;
        List<Location> created = new ArrayList<>();
        int[][] offsets = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int y = beacon.getBlockY() + 1; y < w.getMaxHeight(); y++) {
            for (int[] offset : offsets) {
                Block block = w.getBlockAt(beacon.getBlockX() + offset[0], y, beacon.getBlockZ() + offset[1]);
                if (block.getType() != Material.AIR) continue;
                block.setType(Material.BARRIER, false);
                created.add(block.getLocation());
            }
        }
        barrierColumns.put(owner, created);
        // Solid blocks in the central shaft are intentionally left untouched.
        // Players must remove them for the beacon beam to reach the sky.
    }
    private void removeBarrierColumn(UUID owner) {
        List<Location> locations = barrierColumns.remove(owner);
        if (locations == null) return;
        for (Location l : locations) {
            if (l.getWorld() != null && l.getBlock().getType() == Material.BARRIER)
                l.getBlock().setType(Material.AIR, false);
        }
    }
    private boolean isManagedBarrier(Location l) {
        for (List<Location> locations : barrierColumns.values()) {
            if (locations.contains(l)) return true;
        }
        return false;
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        cancelBreak(e.getEntity().getUniqueId());
        combatTaggedUntil.remove(e.getEntity().getUniqueId());
        Player victim = e.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skull = (SkullMeta) head.getItemMeta();
            if (skull != null) {
                skull.setOwningPlayer(victim);
                skull.setDisplayName("§e" + victim.getName() + "의 머리");
                head.setItemMeta(skull);
            }
            e.getDrops().add(head);
        }
        Player p = e.getEntity();
        // Drop the actual XP represented by the current level and XP bar,
        // rather than Bukkit getTotalExperience(), which can be stale.
        e.setDroppedExp(calculateCurrentXp(p));
        e.setKeepLevel(false);
        e.setNewExp(0); e.setNewLevel(0); e.setNewTotalExp(0);
    }
    private int calculateCurrentXp(Player p) {
        int level = Math.max(0, p.getLevel());
        double base;
        int next;
        if (level <= 16) {
            base = level * level + 6.0 * level;
            next = 2 * level + 7;
        } else if (level <= 31) {
            base = 2.5 * level * level - 40.5 * level + 360;
            next = 5 * level - 38;
        } else {
            base = 4.5 * level * level - 162.5 * level + 2220;
            next = 9 * level - 158;
        }
        long xp = Math.round(base) + (long) Math.floor(Math.max(0.0f, Math.min(1.0f, p.getExp())) * next);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, xp));
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        // Always respawn where this player first appeared after /startgame.
        // Beacon ownership and bed spawn points do not override the SMP start location.
        Location target = starts.get(p.getUniqueId());
        if (target != null && target.getWorld() != null) {
            Location safe = clearForPlayer(target) ? target : safeNear(target);
            if (safe != null && clearForPlayer(safe)) e.setRespawnLocation(safe);
        }
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) { applySword(p); applyArmorBonuses(p); applyBootsSpeed(p); } }, 2L);
    }
    private void buy(Player p) {
        UUID id = p.getUniqueId();
        if (startedAt == -1) { p.sendMessage("게임이 시작되지 않았습니다."); return; }
        if (permanentlyLost.contains(id) || !finalOwner(id).equals(id)) { p.sendMessage("병합된 플레이어는 구매할 수 없습니다."); return; }
        if (beacons.containsKey(finalOwner(id))) { p.sendMessage("§c팀에 이미 설치된 신호기가 있어 구매할 수 없습니다."); return; }
        // Prevent duplicate purchases while an unplaced beacon is already held.
        if (p.getInventory().contains(Material.BEACON)) { p.sendMessage("이미 신호기를 보유하고 있습니다."); return; }
        long costLong = (long) DIAMOND_COST * (beaconPurchases.getOrDefault(id, 0) + 1L);
        if (costLong > Integer.MAX_VALUE) { p.sendMessage("신호기 구매 한도를 초과했습니다."); return; }
        int cost = (int) costLong;
        if (!p.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), cost)) {
            p.sendMessage("§c신호기 구매에 다이아몬드 " + cost + "개가 필요합니다."); return;
        }
        removeDiamonds(p.getInventory(), cost);
        beaconPurchases.merge(id, 1, Integer::sum);
        give(p, new ItemStack(Material.BEACON));
        saveState();
        p.sendMessage("§a다이아몬드 " + cost + "개로 신호기를 구매했습니다. 다음 구매 가격: " + ((long) DIAMOND_COST * (beaconPurchases.get(id) + 1L)) + "개");
    }
    private void removeDiamonds(PlayerInventory inv, int amount) {
        for (int i = 0; i < 36 && amount > 0; i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() != Material.DIAMOND) continue;
            int used = Math.min(amount, item.getAmount()); amount -= used;
            if (used == item.getAmount()) inv.setItem(i, null);
            else item.setAmount(item.getAmount() - used);
        }
    }
    private void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
        for (ItemStack remaining : leftover.values()) p.getWorld().dropItemNaturally(p.getLocation(), remaining);
    }
    private void sendBuyLink(Player p) {
        TextComponent link = new TextComponent("여기"); link.setColor(ChatColor.AQUA); link.setBold(true); link.setUnderlined(true);
        link.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/buybeacon"));
        long cost = (long) DIAMOND_COST * (beaconPurchases.getOrDefault(p.getUniqueId(), 0) + 1L);
        link.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("다이아 " + cost + "개로 신호기 구매").create()));
        p.spigot().sendMessage(new TextComponent("§c집도 절도 없어 보이시네요 ㅋㅋ "), link,
                new TextComponent("§c를 클릭해 신호기를 구매하세요."));
    }
    @EventHandler public void onCombat(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player)) return;
        Player p = (Player) e.getDamager();
        if (!specialSword(p.getInventory().getItemInMainHand())) return;
        // Attack-speed attribute handles the rapid cooldown; do not inflate event damage.
        setSpeed(p, SPECIAL_SPEED);
    }
    private boolean protectionX(ItemStack item, String suffix) {
        return item != null && item.getType() != Material.AIR
                && item.getType().name().endsWith(suffix)
                && item.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL) >= 10;
    }
    private boolean fullProtectionXSet(Player p) {
        PlayerInventory inv = p.getInventory();
        return protectionX(inv.getHelmet(), "_HELMET")
                && protectionX(inv.getChestplate(), "_CHESTPLATE")
                && protectionX(inv.getLeggings(), "_LEGGINGS")
                && protectionX(inv.getBoots(), "_BOOTS");
    }
    private void applyArmorBonuses(Player p) {
        AttributeInstance health = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) return;
        boolean fullSet = fullProtectionXSet(p);
        updateHealthModifier(health, PROT10_HEALTH_MODIFIER_ID, "smp_prot10_helmet", 5.0, fullSet);
        updateHealthModifier(health, PROT10_CHEST_MODIFIER_ID, "smp_prot10_chestplate", 20.0, fullSet);
        updateHealthModifier(health, PROT10_LEGS_MODIFIER_ID, "smp_prot10_leggings", 5.0, fullSet);
        if (p.getHealth() > health.getValue()) p.setHealth(Math.max(0.1, health.getValue()));
    }
    private void updateHealthModifier(AttributeInstance attr, UUID id, String name, double amount, boolean enabled) {
        AttributeModifier existing = null;
        for (AttributeModifier modifier : attr.getModifiers()) {
            if (id.equals(modifier.getUniqueId())) { existing = modifier; break; }
        }
        if (enabled && existing == null) {
            attr.addModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADD_NUMBER));
        } else if (!enabled && existing != null) {
            attr.removeModifier(existing);
        }
    }
    private void removeArmorBonuses(Player p) {
        AttributeInstance health = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) return;
        updateHealthModifier(health, PROT10_HEALTH_MODIFIER_ID, "smp_prot10_helmet", 5, false);
        updateHealthModifier(health, PROT10_CHEST_MODIFIER_ID, "smp_prot10_chestplate", 20, false);
        updateHealthModifier(health, PROT10_LEGS_MODIFIER_ID, "smp_prot10_leggings", 5, false);
        if (p.getHealth() > health.getValue()) p.setHealth(Math.max(0.1, health.getValue()));
    }
    private void applyBootsSpeed(Player p) {
        UUID id = p.getUniqueId();
        if (fullProtectionXSet(p)) {
            // Speed II = amplifier 1; refresh every second while boots are equipped.
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 35, 1, false, false));
            speedBootsApplied.add(id);
        } else {
            removeBootsSpeed(p);
        }
    }
    private void removeBootsSpeed(Player p) {
        if (!speedBootsApplied.remove(p.getUniqueId())) return;
        PotionEffect active = p.getPotionEffect(PotionEffectType.SPEED);
        // Avoid removing unrelated higher-level effects.
        if (active != null && active.getAmplifier() == 1 && active.getDuration() <= 35) {
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }
    private void applySword(Player p) { setSpeed(p, specialSword(p.getInventory().getItemInMainHand()) ? SPECIAL_SPEED : BASE_SPEED); }
    private void setSpeed(Player p, double speed) {
        AttributeInstance a = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (a != null && Math.abs(a.getBaseValue() - speed) > .01) a.setBaseValue(speed);
    }
    private boolean specialSword(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_SWORD && item.getEnchantmentLevel(Enchantment.DAMAGE_ALL) >= 10;
    }
    @EventHandler public void onDragonDeath(EntityDeathEvent e) {
        if (!(e.getEntity() instanceof EnderDragon)) return;
        // Allow vanilla to generate the End Gateway to the outer islands.
        // Applies to both the naturally spawned dragon and all respawned dragons.
        // Give the killing player 50 experience LEVELS, not 50 XP points.
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        killer.giveExpLevels(50);
        killer.sendMessage("§d엔더 드래곤 처치 보상: 경험치 50레벨을 획득했습니다!");
        // Prevent additional vanilla dragon XP from changing the fixed reward.
        e.setDroppedExp(0);
    }
    @EventHandler public void onAnvil(PrepareAnvilEvent e) {
        AnvilInventory inv = e.getInventory();
        ItemStack left = inv.getItem(0), right = inv.getItem(1);
        if (left == null || right == null || left.getType() == Material.AIR) return;
        if (containsBanned(left) || containsBanned(right) || containsBanned(e.getResult())) {
            e.setResult(null); return;
        }
        ItemStack result = left.clone();
        boolean changed = false;
        int targetLevel = 0;
        for (Enchantment enchant : new Enchantment[]{Enchantment.DAMAGE_ALL, Enchantment.PROTECTION_ENVIRONMENTAL}) {
            int a = enchantLevel(left, enchant), b = enchantLevel(right, enchant);
            if (a < 1 || a != b || a >= 10) continue;
            if (left.getType() != Material.ENCHANTED_BOOK && !enchant.canEnchantItem(left)) continue;
            if (right.getType() != Material.ENCHANTED_BOOK && left.getType() != right.getType()) continue;
            int next = a + 1;
            setEnchant(result, enchant, next);
            changed = true;
            targetLevel = Math.max(targetLevel, next);
        }
        if (changed) {
            e.setResult(result);
            // 목표 레벨 5=40, 6=50, ... 10=90. 여러 인챈트가 합쳐지면 높은 레벨을 기준으로 계산.
            int cost = (targetLevel - 1) * 10;
            inv.setMaximumRepairCost(Math.max(40, cost + 1));
            inv.setRepairCost(cost);
        } else if (right.getType() == Material.ENCHANTED_BOOK) {
            // 레벨을 올리지 않는 일반 인챈트 책 적용은 기존 비용을 유지.
            inv.setMaximumRepairCost(40);
            inv.setRepairCost(39);
        }
    }
    private int enchantLevel(ItemStack item, Enchantment enchant) {
        if (item.getType() == Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta)
            return ((EnchantmentStorageMeta) item.getItemMeta()).getStoredEnchantLevel(enchant);
        return item.getEnchantmentLevel(enchant);
    }
    private void setEnchant(ItemStack item, Enchantment enchant, int level) {
        ItemMeta meta = item.getItemMeta(); if (meta == null) return;
        if (meta instanceof EnchantmentStorageMeta) {
            ((EnchantmentStorageMeta) meta).addStoredEnchant(enchant, level, true);
        } else meta.addEnchant(enchant, level, true);
        item.setItemMeta(meta);
    }
    private UUID finalOwner(UUID id) {
        UUID current = id; Set<UUID> visited = new HashSet<>();
        while (merged.containsKey(current) && visited.add(current)) current = merged.get(current);
        return current;
    }
    private String key(Location l) { return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ(); }
    private void saveState() {
        if (stateFile == null) return;
        YamlConfiguration y = new YamlConfiguration(); y.set("startedAt", startedAt);
        y.set("tradeDate", tradeDate);
        for (Map.Entry<UUID, Integer> trade : dailyTrades.entrySet()) y.set("dailyTrades." + trade.getKey(), trade.getValue());
        for (Map.Entry<UUID, Long> tp : beaconTpLastUsed.entrySet()) y.set("beaconTpLastUsed." + tp.getKey(), tp.getValue());
        for (Map.Entry<UUID, Integer> purchase : beaconPurchases.entrySet()) y.set("beaconPurchases." + purchase.getKey(), purchase.getValue());
        for (UUID id : initialized) y.set("initialized." + id, true);
        for (UUID id : everPlaced) y.set("everPlaced." + id, true);
        for (UUID id : permanentlyLost) y.set("permanentlyLost." + id, true);
        for (Map.Entry<UUID, UUID> e : merged.entrySet()) y.set("merged." + e.getKey(), e.getValue().toString());
        for (UUID id : manualTeamMembers) y.set("manualTeamMembers." + id, true);
        for (Map.Entry<UUID, Location> e : beacons.entrySet()) {
            y.set("beacons." + e.getKey() + ".location", e.getValue());
            y.set("beacons." + e.getKey() + ".active", previouslyActive.getOrDefault(e.getKey(), false));
        }
        for (Map.Entry<UUID, Location> e : starts.entrySet()) y.set("starts." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Location> e : teamSpawns.entrySet()) y.set("teamSpawns." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, List<Location>> e : barrierColumns.entrySet())
            y.set("barrierColumns." + e.getKey(), e.getValue());
        try { y.save(stateFile); } catch (IOException ex) { getLogger().severe("state.yml 저장 실패: " + ex.getMessage()); }
    }
    private void loadState() {
        if (!stateFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(stateFile); startedAt = y.getLong("startedAt", -1);
        tradeDate = y.getString("tradeDate", LocalDate.now().toString());
        ConfigurationSection tradeSection = y.getConfigurationSection("dailyTrades");
        if (tradeSection != null) for (String id : tradeSection.getKeys(false)) {
            try { dailyTrades.put(UUID.fromString(id), tradeSection.getInt(id)); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection tpSection = y.getConfigurationSection("beaconTpLastUsed");
        if (tpSection != null) for (String id : tpSection.getKeys(false)) {
            try { beaconTpLastUsed.put(UUID.fromString(id), tpSection.getLong(id)); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection purchaseSection = y.getConfigurationSection("beaconPurchases");
        if (purchaseSection != null) for (String id : purchaseSection.getKeys(false)) {
            try { beaconPurchases.put(UUID.fromString(id), Math.max(0, purchaseSection.getInt(id))); } catch (IllegalArgumentException ignored) { }
        }
        resetTradeDay();
        loadIds(y, "initialized", initialized); loadIds(y, "everPlaced", everPlaced); loadIds(y, "permanentlyLost", permanentlyLost);
        loadIds(y, "manualTeamMembers", manualTeamMembers);
        ConfigurationSection merges = y.getConfigurationSection("merged");
        if (merges != null) for (String id : merges.getKeys(false)) {
            try { merged.put(UUID.fromString(id), UUID.fromString(Objects.requireNonNull(y.getString("merged." + id)))); }
            catch (IllegalArgumentException | NullPointerException ex) { getLogger().warning("잘못된 병합 UUID: " + id); }
        }
        ConfigurationSection bs = y.getConfigurationSection("beacons");
        if (bs != null) for (String id : bs.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(id); Location loc = y.getLocation("beacons." + id + ".location");
                if (loc != null && loc.getWorld() != null) {
                    beacons.put(uuid, loc); beaconOwners.put(key(loc), uuid);
                    previouslyActive.put(uuid, y.getBoolean("beacons." + id + ".active"));
                }
            } catch (IllegalArgumentException ex) { getLogger().warning("잘못된 신호기 UUID: " + id); }
        }
        ConfigurationSection columns = y.getConfigurationSection("barrierColumns");
        if (columns != null) for (String id : columns.getKeys(false)) {
            try {
                UUID owner = UUID.fromString(id);
                List<Location> locs = new ArrayList<>();
                for (Object obj : y.getList("barrierColumns." + id, Collections.emptyList())) {
                    if (obj instanceof Location) locs.add((Location) obj);
                }
                barrierColumns.put(owner, locs);
            } catch (IllegalArgumentException ex) { getLogger().warning("잘못된 배리어 UUID: " + id); }
        }
        ConfigurationSection teamSpawnSection = y.getConfigurationSection("teamSpawns");
        if (teamSpawnSection != null) for (String id : teamSpawnSection.getKeys(false)) {
            try { Location loc = y.getLocation("teamSpawns." + id); if (loc != null) teamSpawns.put(UUID.fromString(id), loc); }
            catch (IllegalArgumentException ex) { getLogger().warning("잘못된 팀 스폰 UUID: " + id); }
        }
        ConfigurationSection spawns = y.getConfigurationSection("starts");
        if (spawns != null) for (String id : spawns.getKeys(false)) {
            try { Location loc = y.getLocation("starts." + id); if (loc != null) starts.put(UUID.fromString(id), loc); }
            catch (IllegalArgumentException ex) { getLogger().warning("잘못된 스폰 UUID: " + id); }
        }
    }
    private void loadIds(YamlConfiguration y, String path, Set<UUID> dest) {
        ConfigurationSection sec = y.getConfigurationSection(path);
        if (sec != null) for (String id : sec.getKeys(false)) {
            try { dest.add(UUID.fromString(id)); } catch (IllegalArgumentException ex) { getLogger().warning("잘못된 UUID: " + id); }
        }
    }
}
