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
import org.bukkit.event.enchantment.EnchantItemEvent;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
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
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class Main extends JavaPlugin implements Listener {
    private static final int RADIUS = 50, BUFF_RADIUS = 500, DIAMOND_COST = 10;
    private static final long COMBAT_TAG_MS = 3 * 60 * 1000L;
    private final Map<UUID, Long> combatTaggedUntil = new HashMap<>();
    private static final long BEACON_TP_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final long GRACE_MS = 24L * 60 * 60 * 1000L, BREAK_TICKS = 120 * 20L;
    private static final double BASE_SPEED = 4.0, SPECIAL_SPEED = 1024.0;
    private static final UUID SWORD_SPEED_MODIFIER_ID = UUID.fromString("5c27b0f4-fc1d-4e2a-9436-7d4b6d5e1040");
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
    private final Map<UUID, String> knownNames = new HashMap<>();
    private final Map<UUID, Integer> teamColors = new HashMap<>();
    private static final ChatColor[] TEAM_PALETTE = {ChatColor.RED, ChatColor.BLUE, ChatColor.GREEN, ChatColor.YELLOW, ChatColor.LIGHT_PURPLE, ChatColor.AQUA, ChatColor.GOLD, ChatColor.DARK_RED, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN, ChatColor.DARK_PURPLE, ChatColor.DARK_AQUA};
    private final Map<String, UUID> beaconOwners = new HashMap<>();
    private final Map<UUID, List<Location>> barrierColumns = new HashMap<>();
    private final Map<UUID, UUID> merged = new HashMap<>();
    private final Set<UUID> manualTeamMembers = new HashSet<>();
    // Offline team assignments are teleported exactly once at their next login.
    private final Set<UUID> pendingTeamTeleports = new HashSet<>();
    private final Map<UUID, Boolean> previouslyActive = new HashMap<>();
    private final Map<UUID, BukkitTask> breakTasks = new HashMap<>();
    private final Map<String, Long> alerts = new HashMap<>();
    private final Set<UUID> penaltyApplied = new HashSet<>();
    // Each player has their own 24-hour grace period starting at their first game entry.
    private final Map<UUID, Long> playerGraceStartedAt = new HashMap<>();
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
        refreshTeamColors();
        // Migrate old four-sided barrier columns to the single central column.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Map.Entry<UUID, Location> entry : new HashMap<>(beacons).entrySet()) {
                Location location = entry.getValue();
                if (location.getWorld() != null && location.getBlock().getType() == Material.BEACON)
                    createBarrierColumn(entry.getKey(), location);
            }
            saveState();
        });
        getLogger().info("SMP 1.19.4 enabled");
    }
    @Override public void onDisable() {
        cancelAllBreaks();
        for (Player p : Bukkit.getOnlinePlayers()) { removeSwordSpeed(p); removeArmorBonuses(p); removeBootsSpeed(p); }
        saveState();
    }
    private void sendSmpHelp(CommandSender sender) {
        sender.sendMessage("§6========== SMP 게임 안내 (1/3) ==========");
        sender.sendMessage("§e게임 시작: §f관리자 /startgame, 난이도 HARD, 팀별 랜덤 시작 위치");
        sender.sendMessage("§e팀 설정: §f/teamset <팀장> <팀원...> (관리자), 같은 팀은 닉네임 색상 공유");
        sender.sendMessage("§e팀 관리: §f/teamleader, /teamclear (관리자)");
        sender.sendMessage("§e재접속: §f로그아웃 위치 유지, 새 팀 합류 시 한 번 팀 위치로 이동");
        sender.sendMessage("§6========== SMP 게임 안내 (2/3) ==========");
        sender.sendMessage("§e신호기 제한: §f참여 후 현실 시간 24시간 안에 팀 신호기 설치, 미설치 시 나약함");
        sender.sendMessage("§e신호기 설치: §f오버월드 해수면 아래, 팀당 하나, 활성 상태 유지 필요");
        sender.sendMessage("§e신호기 효과: §f활성화 시 같은 차원의 반경 500블록 팀원에게 성급함 III");
        sender.sendMessage("§e신호기 구매: §f/buybeacon, 다이아 10개부터 구매마다 10개씩 증가");
        sender.sendMessage("§e신호기 귀환: §f/beacontp, 30분 쿨타임");
        sender.sendMessage("§e침입 감지: §f신호기 반경 50블록 내 적 접근 시 경보 및 디버프");
        sender.sendMessage("§6========== SMP 게임 안내 (3/3) ==========");
        sender.sendMessage("§e특수 장비: §f날카로움 X 검 공격 속도 증가");
        sender.sendMessage("§e보호 X 방어구: §f투구 체력 +5, 흉갑 +20, 레깅스 +5, 부츠 신속 I");
        sender.sendMessage("§e기타 규칙: §f사망 시 경험치 전량 드롭, 포션·거래·인챈트 제한");
        sender.sendMessage("§7팀 창고·망원경 추적·커스텀 리스폰 기능은 제거됨");
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
            case "smphelp":
                sendSmpHelp(sender);
                return true;
            case "teamset":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                if (args.length < 2) { sender.sendMessage("사용법: /teamset <팀장> <팀원1> [팀원2] ... (오프라인 가능)"); return true; }
                UUID leaderId = resolvePlayerId(args[0]);
                if (leaderId == null) { sender.sendMessage("§c팀장 닉네임을 확인할 수 없습니다: " + args[0]); return true; }
                if (!finalOwner(leaderId).equals(leaderId) || permanentlyLost.contains(leaderId)) {
                    sender.sendMessage("§c다른 팀에 소속되거나 점령당한 플레이어는 팀장이 될 수 없습니다."); return true;
                }
                knownNames.put(leaderId, args[0]);
                teamColors.putIfAbsent(leaderId, nextTeamColor());
                for (int i = 1; i < args.length; i++) {
                    UUID memberId = resolvePlayerId(args[i]);
                    if (memberId == null) { sender.sendMessage("§c닉네임 확인 실패: " + args[i]); continue; }
                    if (memberId.equals(leaderId)) continue;
                    if (permanentlyLost.contains(memberId) || beacons.containsKey(memberId)) {
                        sender.sendMessage("§c점령당했거나 신호기를 보유한 플레이어는 변경할 수 없습니다: " + args[i]); continue;
                    }
                    if (!finalOwner(memberId).equals(memberId) && !manualTeamMembers.contains(memberId)) {
                        sender.sendMessage("§c점령으로 병합된 플레이어는 이동할 수 없습니다: " + args[i]); continue;
                    }
                    merged.put(memberId, leaderId);
                    manualTeamMembers.add(memberId);
                    knownNames.put(memberId, args[i]);
                    sender.sendMessage("§a" + args[i] + " → " + args[0] + " 팀에 추가");
                    Player member = Bukkit.getPlayer(memberId);
                    if (startedAt != -1) {
                        if (member != null) {
                            member.sendMessage("§a" + args[0] + " 팀에 배정되었습니다.");
                            teleportToTeam(member, true);
                        } else {
                            pendingTeamTeleports.add(memberId);
                        }
                    }
                }
                refreshTeamColors(); saveState(); return true;
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
                Integer color = teamColors.remove(oldId); if (color != null) teamColors.put(newId, color);
                refreshTeamColors(); saveState(); sender.sendMessage("§a새 팀장: " + newLeader.getName()); return true;
            case "teamclear":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                if (args.length != 1) { sender.sendMessage("사용법: /teamclear <팀원>"); return true; }
                Player leaving = Bukkit.getPlayerExact(args[0]);
                if (leaving == null) { sender.sendMessage("온라인 플레이어만 지정할 수 있습니다."); return true; }
                if (startedAt != -1 && !initialized.isEmpty()) { sender.sendMessage("§c팀 변경은 /startgame 전에 완료해 주세요."); return true; }
                if (!manualTeamMembers.remove(leaving.getUniqueId())) { sender.sendMessage("수동 배정된 팀원이 아닙니다."); return true; }
                merged.remove(leaving.getUniqueId());
                refreshTeamColors(); saveState(); sender.sendMessage("§a" + leaving.getName() + "의 수동 팀 배정을 해제했습니다."); return true;
            case "startgame":
                if (!sender.hasPermission("smp.admin")) { sender.sendMessage("권한이 없습니다."); return true; }
                cancelAllBreaks();
                for (UUID owner : new HashSet<>(barrierColumns.keySet())) removeBarrierColumn(owner);
                combatTaggedUntil.clear();
                pendingTeamTeleports.clear(); initialized.clear(); everPlaced.clear(); permanentlyLost.clear(); beacons.clear(); starts.clear(); beaconPurchases.clear(); beaconTpLastUsed.clear();
                beaconOwners.clear(); previouslyActive.clear(); alerts.clear(); penaltyApplied.clear(); playerGraceStartedAt.clear(); teamSpawns.clear();
                // Do not erase shared team storage during a new match; it persists unless reset manually.
                // Keep teams configured before /startgame. Captured/defeated teams reset.
                merged.entrySet().removeIf(entry -> !manualTeamMembers.contains(entry.getKey()));
                permanentlyLost.clear();
                startedAt = System.currentTimeMillis();
                saveState();
                Bukkit.broadcastMessage("§aSMP 시작! 각 플레이어는 게임 참여 후 24시간 안에 팀 신호기를 설치해야 합니다.");
                for (Player p : Bukkit.getOnlinePlayers()) initializePlayer(p);
                refreshTeamColors();
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
            knownNames.put(p.getUniqueId(), p.getName());
            if (startedAt != -1) {
                if (!initialized.contains(p.getUniqueId())) initializePlayer(p);
                else if (pendingTeamTeleports.contains(p.getUniqueId())) teleportToTeam(p, true);
                // Returning players otherwise keep their vanilla logout location.
            }
            refreshTeamColors();
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
        Location destination = teamDestination(owner);
        if (destination == null) destination = spawn.clone();
        if (!p.teleport(destination)) return;
        initialized.add(p.getUniqueId());
        playerGraceStartedAt.putIfAbsent(p.getUniqueId(), System.currentTimeMillis());
        pendingTeamTeleports.remove(p.getUniqueId());
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
    @SuppressWarnings("deprecation")
    private UUID resolvePlayerId(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> entry : knownNames.entrySet())
            if (entry.getValue().equalsIgnoreCase(name)) return entry.getKey();
        org.bukkit.OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.getUniqueId();
    }
    private int nextTeamColor() {
        Set<Integer> used = new HashSet<>(teamColors.values());
        for (int i = 0; i < TEAM_PALETTE.length; i++) if (!used.contains(i)) return i;
        return teamColors.size() % TEAM_PALETTE.length;
    }
    private void refreshTeamColors() {
        Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
        for (Team t : new ArrayList<>(board.getTeams())) if (t.getName().startsWith("smp_")) t.unregister();
        Set<UUID> owners = new HashSet<>();
        owners.addAll(teamColors.keySet());
        for (UUID id : merged.keySet()) owners.add(finalOwner(id));
        for (UUID owner : owners) {
            teamColors.putIfAbsent(owner, nextTeamColor());
            String id = "smp_" + owner.toString().replace("-", "").substring(0, 12);
            Team team = board.registerNewTeam(id);
            org.bukkit.ChatColor color = org.bukkit.ChatColor.valueOf(
                TEAM_PALETTE[teamColors.get(owner) % TEAM_PALETTE.length].getName().toUpperCase(Locale.ROOT));
            team.setColor(color); team.setPrefix(color.toString());
            for (Map.Entry<UUID, String> entry : knownNames.entrySet())
                if (finalOwner(entry.getKey()).equals(owner)) team.addEntry(entry.getValue());
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(board);
    }
    private Location teamDestination(UUID owner) {
        Location beacon = beacons.get(owner);
        if (beacon != null && beacon.getWorld() != null && beacon.getBlock().getType() == Material.BEACON) {
            Location near = safeNear(beacon);
            if (near != null) return near;
        }
        return teamSpawns.get(owner);
    }
    private void teleportToTeam(Player p, boolean newlyAdded) {
        UUID owner = finalOwner(p.getUniqueId());
        Location destination = teamDestination(owner);
        if (destination == null) {
            // The team leader has not logged in yet: establish one team spawn now.
            destination = safeRandomSpawn();
            if (destination == null) { p.sendMessage("§c팀 스폰을 찾지 못했습니다."); return; }
            teamSpawns.put(owner, destination.clone());
        }
        if (p.teleport(destination.clone())) {
            initialized.add(p.getUniqueId());
            pendingTeamTeleports.remove(p.getUniqueId());
            starts.put(p.getUniqueId(), teamSpawns.getOrDefault(owner, destination).clone());
            p.sendMessage(newlyAdded ? "§a새 팀 위치로 이동했습니다." : "§a팀 신호기 또는 팀 스폰으로 이동했습니다.");
            saveState();
        }
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
        p.sendMessage("신호기 등록 완료! §b신호기 바로 위에만 방벽 기둥을 생성합니다. 성급함 III 범위: 500블록.");
        Bukkit.getScheduler().runTask(this, () -> {
            if (l.getBlock().getType() == Material.BEACON && id.equals(beaconOwners.get(key(l)))) {
                createBarrierColumn(id, l);
                saveState();
            }
        });
        // Teleport after Bukkit has finished placing the beacon block.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player teammate : Bukkit.getOnlinePlayers()) {
                if (finalOwner(teammate.getUniqueId()).equals(id)) teleportToTeam(teammate, false);
            }
            saveState();
        });
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
        // Equipment effects must work even before /startgame.
        for (Player p : Bukkit.getOnlinePlayers()) {
            applySword(p);
            applyArmorBonuses(p);
            applyBootsSpeed(p);
        }
        if (startedAt == -1) return;
        for (UUID owner : new HashSet<>(beacons.keySet())) {
            Location l = beacons.get(owner); if (l == null) continue;
            Block block = l.getBlock();
            if (block.getType() != Material.BEACON) { unregisterBeacon(owner, false); continue; }
            boolean active = block.getState() instanceof Beacon && ((Beacon) block.getState()).getTier() > 0;
            if (active) {
                if (!previouslyActive.getOrDefault(owner, false)) {
                    previouslyActive.put(owner, true);
                    for (Player teammate : Bukkit.getOnlinePlayers()) {
                        if (finalOwner(teammate.getUniqueId()).equals(owner)) {
                            teammate.sendMessage("§a[신호기] 팀 신호기가 활성화되었습니다! 같은 차원에서 신호기 반경 500블록 안의 팀원에게 성급함 III이 적용됩니다.");
                        }
                    }
                    saveState();
                }
            } else if (previouslyActive.getOrDefault(owner, false)) {
                block.setType(Material.AIR, false); unregisterBeacon(owner, false);
                Player p = Bukkit.getPlayer(owner); if (p != null) p.sendMessage("§c활성화된 신호기가 비활성화되어 사라졌습니다.");
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyPenalty(p); applyZone(p);
            if (System.currentTimeMillis() / 600000L != (System.currentTimeMillis() - 1000L) / 600000L && !beacons.containsKey(finalOwner(p.getUniqueId()))
                    && !permanentlyLost.contains(p.getUniqueId()) && finalOwner(p.getUniqueId()).equals(p.getUniqueId())) {
                sendBuyLink(p);
            }
        }
    }
    private void applyPenalty(Player p) {
        if (startedAt == -1 || !initialized.contains(p.getUniqueId())) return;
        UUID id = p.getUniqueId();
        UUID owner = finalOwner(id);
        // A placed team beacon protects all members, even late joiners.
        if (everPlaced.contains(owner) || beacons.containsKey(owner)) {
            if (penaltyApplied.remove(id)) p.removePotionEffect(PotionEffectType.WEAKNESS);
            return;
        }
        // Legacy players without a saved entry time use the original match start.
        long joinedAt = playerGraceStartedAt.getOrDefault(id, startedAt);
        if (System.currentTimeMillis() - joinedAt < GRACE_MS) return;
        if (penaltyApplied.add(id)) p.sendMessage("§c참여 후 24시간 안에 팀 신호기를 설치하지 않아 나약함이 적용되었습니다.");
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
    // One vertical column directly over the beacon, no adjacent columns.
    // NOTE: barriers are opaque, so the vanilla visible beacon beam is blocked.
    private void createBarrierColumn(UUID owner, Location beacon) {
        removeBarrierColumn(owner);
        World w = beacon.getWorld();
        if (w == null) return;
        List<Location> created = new ArrayList<>();
        for (int y = beacon.getBlockY() + 1; y < w.getMaxHeight(); y++) {
            Block block = w.getBlockAt(beacon.getBlockX(), y, beacon.getBlockZ());
            if (block.getType() != Material.AIR) continue;
            block.setType(Material.BARRIER, false);
            created.add(block.getLocation());
        }
        barrierColumns.put(owner, created);
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
        applySword(p);
    }
    private boolean protectionX(ItemStack item, String suffix) {
        return item != null && item.getType() != Material.AIR
                && item.getType().name().endsWith(suffix)
                && item.getEnchantmentLevel(Enchantment.PROTECTION_ENVIRONMENTAL) >= 10;
    }
    private void applyArmorBonuses(Player p) {
        AttributeInstance health = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (health == null) return;
        PlayerInventory inv = p.getInventory();
        updateHealthModifier(health, PROT10_HEALTH_MODIFIER_ID, "smp_prot10_helmet", 5.0,
                protectionX(inv.getHelmet(), "_HELMET"));
        updateHealthModifier(health, PROT10_CHEST_MODIFIER_ID, "smp_prot10_chestplate", 20.0,
                protectionX(inv.getChestplate(), "_CHESTPLATE"));
        updateHealthModifier(health, PROT10_LEGS_MODIFIER_ID, "smp_prot10_leggings", 5.0,
                protectionX(inv.getLeggings(), "_LEGGINGS"));
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
        if (protectionX(p.getInventory().getBoots(), "_BOOTS")) {
            // Speed I = amplifier 0; boots alone grant this effect.
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 35, 0, false, false));
            speedBootsApplied.add(id);
        } else {
            removeBootsSpeed(p);
        }
    }
    private void removeBootsSpeed(Player p) {
        if (!speedBootsApplied.remove(p.getUniqueId())) return;
        PotionEffect active = p.getPotionEffect(PotionEffectType.SPEED);
        // Avoid removing unrelated higher-level effects.
        if (active != null && active.getAmplifier() == 0 && active.getDuration() <= 35) {
            p.removePotionEffect(PotionEffectType.SPEED);
        }
    }
    // Attribute modifier, rather than base speed, survives vanilla sword's -2.4 speed modifier.
    // Keep the player's original base value untouched so other plugins/attributes are respected.
    private void applySword(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (a == null) return;
        boolean enabled = specialSword(p.getInventory().getItemInMainHand());
        AttributeModifier current = null;
        for (AttributeModifier modifier : a.getModifiers()) {
            if (SWORD_SPEED_MODIFIER_ID.equals(modifier.getUniqueId())) { current = modifier; break; }
        }
        if (enabled && current == null) {
            a.addModifier(new AttributeModifier(SWORD_SPEED_MODIFIER_ID, "smp_sharp10_attack_speed",
                    SPECIAL_SPEED, AttributeModifier.Operation.ADD_NUMBER));
        } else if (!enabled && current != null) {
            a.removeModifier(current);
        }
    }
    private void removeSwordSpeed(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (a == null) return;
        for (AttributeModifier modifier : new ArrayList<>(a.getModifiers())) {
            if (SWORD_SPEED_MODIFIER_ID.equals(modifier.getUniqueId())) a.removeModifier(modifier);
        }
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
        for (Map.Entry<UUID, Long> entry : playerGraceStartedAt.entrySet()) y.set("playerGraceStartedAt." + entry.getKey(), entry.getValue());
        y.set("tradeDate", tradeDate);
        for (Map.Entry<UUID, Integer> trade : dailyTrades.entrySet()) y.set("dailyTrades." + trade.getKey(), trade.getValue());
        for (Map.Entry<UUID, Long> tp : beaconTpLastUsed.entrySet()) y.set("beaconTpLastUsed." + tp.getKey(), tp.getValue());
        for (Map.Entry<UUID, Integer> purchase : beaconPurchases.entrySet()) y.set("beaconPurchases." + purchase.getKey(), purchase.getValue());
        for (UUID id : initialized) y.set("initialized." + id, true);
        for (UUID id : everPlaced) y.set("everPlaced." + id, true);
        for (UUID id : permanentlyLost) y.set("permanentlyLost." + id, true);
        for (Map.Entry<UUID, UUID> e : merged.entrySet()) y.set("merged." + e.getKey(), e.getValue().toString());
        for (Map.Entry<UUID, String> e : knownNames.entrySet()) y.set("knownNames." + e.getKey(), e.getValue());
        for (Map.Entry<UUID, Integer> e : teamColors.entrySet()) y.set("teamColors." + e.getKey(), e.getValue());
        for (UUID id : manualTeamMembers) y.set("manualTeamMembers." + id, true);
        for (UUID id : pendingTeamTeleports) y.set("pendingTeamTeleports." + id, true);
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
        ConfigurationSection graceSection = y.getConfigurationSection("playerGraceStartedAt");
        if (graceSection != null) for (String id : graceSection.getKeys(false)) {
            try { playerGraceStartedAt.put(UUID.fromString(id), graceSection.getLong(id)); } catch (IllegalArgumentException ignored) { }
        }
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
        loadIds(y, "pendingTeamTeleports", pendingTeamTeleports);
        ConfigurationSection namesSection = y.getConfigurationSection("knownNames");
        if (namesSection != null) for (String id : namesSection.getKeys(false)) {
            try { knownNames.put(UUID.fromString(id), namesSection.getString(id)); } catch (IllegalArgumentException ignored) { }
        }
        ConfigurationSection colorsSection = y.getConfigurationSection("teamColors");
        if (colorsSection != null) for (String id : colorsSection.getKeys(false)) {
            try { teamColors.put(UUID.fromString(id), colorsSection.getInt(id)); } catch (IllegalArgumentException ignored) { }
        }
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
