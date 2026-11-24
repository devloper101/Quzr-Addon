package com.macekill.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.macekill.addon.MaceKillAddon.CATEGORY;

/**
 * Hits back everyone who damages you, AND everyone who damages a player on the Protect list
 * (teammates). Several attackers are tracked and hit at once, each with its own cooldown.
 */
public class Hitback extends Module {
    private static Hitback INSTANCE;

    private final SettingGroup sg = settings.getDefaultGroup();
    private final SettingGroup sgProtect = settings.createGroup("Protect");

    private final Setting<Boolean> rotate = sg.add(new BoolSetting.Builder()
        .name("Rotate")
        .description("Rotate to face the attacker before hitting back.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> ignoreFriends = sg.add(new BoolSetting.Builder()
        .name("Ignore Friends")
        .description("Do not hit back friends.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> onlyPlayers = sg.add(new BoolSetting.Builder()
        .name("Only Players")
        .description("Only hit back players, not mobs/entities.")
        .defaultValue(true)
        .build());

    private final Setting<Integer> cooldown = sg.add(new IntSetting.Builder()
        .name("Cooldown")
        .description("Minimum ticks between hitbacks on the SAME attacker (each attacker has its own timer).")
        .defaultValue(10)
        .min(0)
        .max(200)
        .sliderMax(200)
        .build());
    private final Setting<Integer> memory = sg.add(new IntSetting.Builder()
        .name("Memory")
        .description("Ticks an attacker stays remembered after damaging you/a protected player (20 ticks = 1s).")
        .defaultValue(60)
        .min(1)
        .max(400)
        .sliderMax(200)
        .build());
    private final Setting<Boolean> keepHitting = sg.add(new BoolSetting.Builder()
        .name("Keep Hitting")
        .description("Keep hitting every remembered attacker in reach, not just when damage is dealt.")
        .defaultValue(true)
        .build());
    private final Setting<Integer> maxTargets = sg.add(new IntSetting.Builder()
        .name("Max Targets")
        .description("Maximum attackers remembered and hit back at once.")
        .defaultValue(5)
        .min(1)
        .max(20)
        .sliderMax(10)
        .build());

    private final Setting<Boolean> teleportToAttacker = sg.add(new BoolSetting.Builder()
        .name("Teleport To Attacker")
        .description("ON (default): reach the attacker with the same teleport-attack-return move UltraAura/Blacklist use, so protecting a teammate who is far from YOU actually works, not just attackers already next to you. OFF: only hit attackers already within your own normal melee reach.")
        .defaultValue(true)
        .build());
    private final Setting<Double> maxRange = sg.add(new DoubleSetting.Builder()
        .name("Max Range")
        .description("With Teleport To Attacker on, the furthest an attacker can be before Hitback gives up on them.")
        .defaultValue(30.0)
        .min(5.0)
        .max(60.0)
        .visible(teleportToAttacker::get)
        .build());

    private final Setting<Boolean> protectOthers = sgProtect.add(new BoolSetting.Builder()
        .name("Protect Teammates")
        .description("Also hit back whoever damages a player on the Protect list below.")
        .defaultValue(true)
        .build());
    private final Setting<List<String>> protectedNames = sgProtect.add(new StringListSetting.Builder()
        .name("Protect List")
        .description("Player names (case-insensitive) to protect. Manage with .protect add/remove <player>.")
        .defaultValue()
        .visible(protectOthers::get)
        .build());

    // entity id -> ticks left in memory / ticks left on that attacker's own cooldown
    private final LinkedHashMap<Integer, Integer> remembered = new LinkedHashMap<>();
    private final HashMap<Integer, Integer> cooldowns = new HashMap<>();

    public Hitback() {
        super(CATEGORY, "Hitback", "Hits back everyone who damages you or a protected teammate (several attackers at once).");
        INSTANCE = this;
    }

    public static Hitback get() {
        return INSTANCE;
    }

    /** True when at least one remembered attacker is currently valid (alive, in reach, not a friend). */
    public boolean hasActiveAttacker() {
        if (mc == null || mc.player == null || mc.level == null) return false;
        for (int id : remembered.keySet()) {
            if (isValid(mc.level.getEntity(id))) return true;
        }
        return false;
    }

    @Override
    public void onActivate() {
        remembered.clear();
        cooldowns.clear();
    }

    @EventHandler
    private void onDamagePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundDamageEventPacket packet)) return;
        if (mc.player == null || mc.level == null) return;

        Entity victim = mc.level.getEntity(packet.entityId());
        boolean isSelf = victim == mc.player;
        boolean isProtected = !isSelf && protectOthers.get() && victim instanceof Player vp && isProtectedName(vp);
        if (!isSelf && !isProtected) return;

        int attackerId = packet.sourceCauseId();
        if (attackerId <= 0) return;
        Entity attacker = mc.level.getEntity(attackerId);
        if (attacker == victim) return; // self-damage (fall, fire, etc.), not an attacker
        if (!isValid(attacker)) return;

        remember(attackerId);
        // hit back right away if this attacker's own cooldown is ready
        if (cooldowns.getOrDefault(attackerId, 0) <= 0) {
            cooldowns.put(attackerId, cooldown.get());
            hitBack(attacker);
        }
    }

    private void remember(int attackerId) {
        remembered.put(attackerId, memory.get());
        // too many attackers: forget the oldest one
        while (remembered.size() > maxTargets.get()) {
            Integer oldest = remembered.keySet().iterator().next();
            remembered.remove(oldest);
            cooldowns.remove(oldest);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        // count every timer down, drop attackers that are gone, dead or forgotten
        Iterator<Map.Entry<Integer, Integer>> it = remembered.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Entity ent = mc.level.getEntity(e.getKey());
            int left = e.getValue() - 1;
            if (left <= 0 || !isAlive(ent)) {
                cooldowns.remove(e.getKey());
                it.remove();
            } else {
                e.setValue(left);
            }
        }
        for (var e : cooldowns.entrySet()) if (e.getValue() > 0) e.setValue(e.getValue() - 1);

        if (!keepHitting.get()) return;

        // With rotation only one target per tick (a rotation is a single look direction),
        // without rotation every ready attacker in reach is hit this tick.
        for (int id : remembered.keySet()) {
            if (cooldowns.getOrDefault(id, 0) > 0) continue;
            Entity attacker = mc.level.getEntity(id);
            if (!isValid(attacker)) continue;
            cooldowns.put(id, cooldown.get());
            hitBack(attacker);
            if (rotate.get()) break;
        }
    }

    private boolean isAlive(Entity e) {
        return e != null && e.isAlive() && !e.isRemoved();
    }

    private boolean isValid(Entity attacker) {
        if (attacker == null || attacker == mc.player || !isAlive(attacker)) return false;
        if (onlyPlayers.get() && !(attacker instanceof Player)) return false;
        if (ignoreFriends.get() && attacker instanceof Player p && Friends.get().isFriend(p)) return false;
        // With Teleport To Attacker, the whole point is reaching an attacker far from YOU (the
        // common case when protecting a teammate), so the range is Max Range, not melee reach.
        // Without it, the server drops a plain attack beyond survival reach anyway.
        double range = teleportToAttacker.get() ? maxRange.get() : 4.5;
        return mc.player.distanceToSqr(attacker) <= range * range;
    }

    private boolean isProtectedName(Player p) {
        String name = p.getName().getString();
        for (String n : protectedNames.get()) if (n.equalsIgnoreCase(name)) return true;
        return false;
    }

    private void hitBack(Entity attacker) {
        if (mc.player == null || mc.getConnection() == null || attacker == null) return;

        if (teleportToAttacker.get()) {
            // Same teleport-attack-return move Blacklist uses on UltraAura - this is what lets
            // Hitback reach an attacker far from the player, not just one already adjacent.
            UltraAura ultra = Modules.get().get(UltraAura.class);
            if (ultra != null) {
                ultra.smash(attacker);
                return;
            }
            // Fall through to a plain attack if UltraAura isn't present at all, rather than
            // silently doing nothing.
        }

        Runnable attack = () -> mc.getConnection().send(
            new ServerboundAttackPacket(attacker.getId())
        );

        if (rotate.get()) {
            double dx = attacker.getX() - mc.player.getX();
            double dy = attacker.getEyeY() - mc.player.getEyeY();
            double dz = attacker.getZ() - mc.player.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
            Rotations.rotate(yaw, pitch, attack);
        } else {
            attack.run();
        }
    }

    // ==================== Command API (.protect) ====================
    public List<String> protectSnapshot() {
        return new ArrayList<>(protectedNames.get());
    }

    public String addProtect(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;
        String name = canonical(request);
        List<String> updated = new ArrayList<>(protectedNames.get());
        for (String entry : updated) if (entry.equalsIgnoreCase(name)) return null;
        updated.add(name);
        return protectedNames.set(updated) ? name : null;
    }

    public String removeProtect(String raw) {
        String request = raw == null ? "" : raw.trim();
        if (request.isEmpty()) return null;
        List<String> updated = new ArrayList<>(protectedNames.get());
        String removed = null;
        for (String entry : updated) {
            if (entry.equalsIgnoreCase(request)) { removed = entry; break; }
        }
        if (removed == null) return null;
        updated.remove(removed);
        return protectedNames.set(updated) ? removed : null;
    }

    private String canonical(String request) {
        if (mc != null && mc.level != null) {
            for (Player player : mc.level.players()) {
                String name = player.getName().getString();
                if (name.equalsIgnoreCase(request)) return name;
            }
        }
        return request;
    }
}
