package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * Blacklist — automatically smashes every blacklisted player inside the scan range
 * with the UltraAura smash code (approach packets, weapon switch, attack, return).
 *
 * <p>The scan only runs while the module is enabled, once per {@code Attack Delay}
 * ticks: it looks for a living, non-spectator, non-creative player within
 * {@code Scan Distance} (default 60 blocks) whose name (case-insensitive) is in the
 * {@code Names} list, and skips Meteor friends while {@code Ignore Friends} is on.
 * The hit itself is {@link UltraAura#smash}, so the movement style (PAPER single
 * position vs SPIGOT stepped path), the weapon switch, and the silent swap come
 * entirely from the UltraAura module's settings — this module has no duplicated
 * combat logic.
 *
 * <p>{@code Names} and {@code Scan Distance} are plain module settings, so changes
 * made through the {@code .blacklist} command persist in Meteor's module save.
 */
public class Blacklist extends Module {
    private static Blacklist INSTANCE;

    /** Upper bound accepted by {@code .blacklist dis} (the slider only shows 0-128). */
    public static final int MAX_DISTANCE = 256;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<String>> names = sgGeneral.add(new StringListSetting.Builder()
        .name("Names")
        .description("Blacklisted player names (case-insensitive). Manage with .blacklist add/remove <player>.")
        .defaultValue()
        .build()
    );

    private final Setting<Boolean> useRenderDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("Use Render Distance")
        .description("Reach is your current render distance (chunks x 16 blocks) instead of a fixed number. Using .blacklist dis switches this off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> distance = sgGeneral.add(new IntSetting.Builder()
        .name("Scan Distance")
        .description("Radius in blocks where blacklisted players get smashed (.blacklist dis <value>). Only used while Use Render Distance is off.")
        .defaultValue(60)
        .min(0)
        .max(MAX_DISTANCE)
        .sliderRange(0, 128)
        .visible(() -> !useRenderDistance.get())
        .build()
    );

    private final Setting<Integer> attackDelay = sgGeneral.add(new IntSetting.Builder()
        .name("Attack Delay")
        .description("Ticks between smashes (1 = every tick, 0 = no wait). Low values send packets very fast.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 60)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgGeneral.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Do not attack players on the Meteor friends list even when blacklisted.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> targetsPerCycle = sgGeneral.add(new IntSetting.Builder()
        .name("Targets Per Cycle")
        .description("How many blacklisted players are smashed in one cycle (nearest first).")
        .defaultValue(1)
        .min(1)
        .sliderRange(1, 5)
        .build()
    );
    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("Chat Feedback")
        .description("Print a chat line for every smash. Off by default because it is slow at high speed.")
        .defaultValue(false)
        .build()
    );
    private int timer;

    public Blacklist() {
        super(MaceKillAddon.CATEGORY, "Blacklist", "Auto-smashes blacklisted players within Scan Distance using the UltraAura smash code. Manage the list with .blacklist");
        INSTANCE = this;
    }

    @Override
    public void onActivate() {
        // First smash fires immediately after enabling (timer counts up to the delay).
        this.timer = this.attackDelay.get();

        if (this.names.get().isEmpty()) {
            this.info("Watching nothing yet — add a name with .blacklist add <player>.");
        } else {
            this.info("Watching %d name(s) within %d blocks.", this.names.get().size(), this.effectiveDistance());
        }
    }

    // Pre-tick so the smash goes out at the very start of the tick instead of after everything else ran.
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        this.timer++;
        if (this.timer < this.attackDelay.get()) return;
        if (this.mc.player == null || this.mc.level == null || this.mc.getConnection() == null) return;
        List<Player> victims = this.findBlacklisted();
        if (victims.isEmpty()) return;
        UltraAura ultra = Modules.get().get(UltraAura.class);
        if (ultra == null) {
            this.error("UltraAura module missing — Blacklist cannot smash.");
            return;
        }
        int hits = 0;
        for (Player victim : victims) {
            if (hits >= this.targetsPerCycle.get()) break;
            if (!ultra.smash(victim)) continue;
            hits++;
            if (this.chatFeedback.get()) {
                this.info("Blacklist: smashed %s (%.1f blocks away).",
                    victim.getName().getString(), this.mc.player.distanceTo(victim));
            }
        }
        if (hits > 0) this.timer = 0;
    }

    /** All blacklisted, attackable, in-range players, nearest first. */
    private List<Player> findBlacklisted() {
        List<String> recorded = this.names.get();
        List<Player> found = new ArrayList<>();
        if (recorded.isEmpty()) return found;
        java.util.Set<String> lower = new java.util.HashSet<>();
        for (String n : recorded) lower.add(n.toLowerCase(java.util.Locale.ROOT));
        for (Player player : this.mc.level.players()) {
            if (player == this.mc.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isCreative()) continue;
            if (!lower.contains(player.getName().getString().toLowerCase(java.util.Locale.ROOT))) continue;
            if (this.ignoreFriends.get() && !Friends.get().shouldAttack(player)) continue;
            if (!this.withinScanRange(player)) continue;
            found.add(player);
        }
        found.sort(java.util.Comparator.comparingDouble(p -> this.mc.player.distanceToSqr(p)));
        return found;
    }

    /** Same box-clamped distance concept as UltraAura's isValidTarget. */
    private boolean withinScanRange(Player player) {
        AABB box = player.getBoundingBox();
        double x = Mth.clamp(this.mc.player.getX(), box.minX, box.maxX);
        double y = Mth.clamp(this.mc.player.getY(), box.minY, box.maxY);
        double z = Mth.clamp(this.mc.player.getZ(), box.minZ, box.maxZ);
        return PlayerUtils.isWithin(x, y, z, this.effectiveDistance());
    }

    /**
     * The reach actually used right now: the client's effective render distance (the server's
     * own view-distance cap already applied) converted to blocks, or the manual Scan Distance
     * when Use Render Distance is off. A player the client has no entity data for at all (far
     * enough that the server never sent it) cannot be targeted either way - this cannot see
     * further than the client's own loaded world.
     */
    private int effectiveDistance() {
        if (this.useRenderDistance.get() && this.mc != null && this.mc.options != null) {
            return Math.min(MAX_DISTANCE, this.mc.options.getEffectiveRenderDistance() * 16);
        }
        return this.distance.get();
    }

    // ==================== Command API (.blacklist) ====================

    /** Current entries as a copy. */
    public List<String> nameSnapshot() {
        return new ArrayList<>(this.names.get());
    }

    /**
     * Adds a name (case-insensitive duplicate check; the entry is stored with an online
     * player's exact spelling whenever one matches). Returns the stored name, or null
     * when it is already blacklisted.
     */
    public String addName(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;

        String name = this.canonical(request);
        List<String> updated = new ArrayList<>(this.names.get());
        for (String entry : updated) {
            if (entry.equalsIgnoreCase(name)) return null;
        }
        updated.add(name);
        return this.names.set(updated) ? name : null;
    }

    /** Removes by case-insensitive match. Returns the removed entry, or null when absent. */
    public String removeName(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;

        List<String> updated = new ArrayList<>(this.names.get());
        String removed = null;
        for (String entry : updated) {
            if (entry.equalsIgnoreCase(request)) {
                removed = entry;
                break;
            }
        }
        if (removed == null) return null;
        updated.remove(removed);
        return this.names.set(updated) ? removed : null;
    }

    public int getDistance() {
        return this.effectiveDistance();
    }

    /** Called by .blacklist dis; clamped by the setting's builder (0..MAX_DISTANCE). Switches
     * off Use Render Distance, since the command is an explicit request for a manual value. */
    public void setDistance(int value) {
        this.useRenderDistance.set(false);
        this.distance.set(Mth.clamp(value, 0, MAX_DISTANCE));
    }

    /** Normalizes to an online player's spelling when one matches (ignoring case). */
    private String canonical(String request) {
        if (this.mc != null && this.mc.level != null) {
            for (Player player : this.mc.level.players()) {
                String name = player.getName().getString();
                if (name.equalsIgnoreCase(request)) return name;
            }
        }
        return request;
    }

    /** True when at least one blacklisted, attackable player is within the current reach. */
    public boolean hasTargetInRange() {
        if (this.mc == null || this.mc.player == null || this.mc.level == null) return false;
        return !this.findBlacklisted().isEmpty();
    }

    public static Blacklist get() {
        return INSTANCE;
    }

    @Override
    public String getInfoString() {
        List<String> recorded = this.names.get();
        return recorded.isEmpty() ? null : recorded.size() + " names";
    }
}
