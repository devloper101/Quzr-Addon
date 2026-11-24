package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Keeps the mace in an ender chest. When one of the mace modules is on and an enemy player is close it swaps
 * the mace into a hotbar slot, lets the mace modules use it, and shortly after the last attack swaps it back.
 *
 * Open methods:
 *  - LINKED  : (like Trouser-Streak's RemoteEnderChest) right-click a real ender chest once. The screen is hidden
 *              but the close packet is never sent, so the server keeps that container open and the swaps are plain
 *              click packets - no waiting for a chest to open. Works only as long as the server keeps the container
 *              open (vanilla closes it once you walk out of range; some servers/plugins do not).
 *  - BLOCK   : opens a PLACED ender chest in reach for every swap (a few ticks per open/close).
 *  - COMMAND : uses a server command such as /ec for every swap.
 */
public class EnderChestMace extends Module {
    private enum State { IDLE, OPENING_TAKE, HOLDING, OPENING_RETURN }
    public enum OpenMode { LINKED, BLOCK, COMMAND }

    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<OpenMode> openMode = sg.add(new EnumSetting.Builder<OpenMode>()
        .name("Open method").description("LINKED: right-click an ender chest once and it stays linked (instant swaps). BLOCK: open a placed chest each time. COMMAND: use a server command each time.")
        .defaultValue(OpenMode.LINKED).build());
    private final Setting<String> command = sg.add(new StringSetting.Builder()
        .name("Command").description("Server command that opens your ender chest (without the slash), e.g. ec or enderchest.")
        .defaultValue("ec").visible(() -> openMode.get() == OpenMode.COMMAND).build());
    private final Setting<Double> reach = sg.add(new DoubleSetting.Builder()
        .name("Chest reach").description("Max distance to the ender chest.")
        .defaultValue(4.0).min(1.0).max(4.5).visible(() -> openMode.get() == OpenMode.BLOCK).build());
    private final Setting<Integer> hotbarSlot = sg.add(new IntSetting.Builder()
        .name("Hotbar slot").description("Hotbar slot (1-9) the mace is swapped into. Whatever was there goes into the chest and comes back later.")
        .defaultValue(1).min(1).max(9).build());
    private final Setting<Double> triggerRange = sg.add(new DoubleSetting.Builder()
        .name("Trigger range").description("Fetch the mace when a non-friend player is this close.")
        .defaultValue(10.0).min(2.0).max(40.0).build());
    private final Setting<Integer> returnDelay = sg.add(new IntSetting.Builder()
        .name("Return delay").description("Ticks after the last attack before the mace goes back (20 ticks = 1s).")
        .defaultValue(4).min(0).max(40).build());
    private final Setting<Integer> maxHold = sg.add(new IntSetting.Builder()
        .name("Max hold").description("Put the mace back after this many ticks even if nothing was attacked.")
        .defaultValue(100).min(10).max(600).build());
    private final Setting<Integer> cooldown = sg.add(new IntSetting.Builder()
        .name("Cooldown").description("Ticks to wait after a swap before starting another one.")
        .defaultValue(10).min(0).max(100).build());
    private final Setting<Boolean> chatInfo = sg.add(new BoolSetting.Builder()
        .name("Chat info").description("Print what the module is doing.").defaultValue(true).build());

    private State state = State.IDLE;
    private int timer;          // ticks spent in the current state
    private int wait;           // cooldown ticks
    private int ticksSinceAttack = -1;
    private int enderSlot = -1; // menu slot in the ender chest the mace came from
    private int hotbarIdx = -1; // 0-8 hotbar index the mace sits in
    private int linkedId = -1;  // container id of the hidden, still-open ender chest (LINKED mode)
    private BlockPos linkedChestPos;    // remembered so a dropped link can be re-opened automatically
    private int relinkCooldown;
    private boolean announcedLink;
    private boolean announcedRelinking;
    private boolean externallyHeld; // true while a module is managing an instant pull/return itself (pullMaceNow/returnMaceNow)
    private boolean announcedWrongMenu;
    private boolean announcedWaitingModule;
    private boolean announcedWaitingMace;
    private boolean announcedWaitingEnemy;
    private boolean announcedRightClick;
    // Captured directly from OUR OWN outgoing right-click packet, not from the crosshair - the
    // crosshair can drift off the chest by the very next tick (even a tiny mouse move), which
    // made the old "does mc.hitResult still point at an ender chest" check miss almost every link.
    private BlockPos pendingChestClick;
    private int pendingChestClickTtl;
    private int pendingOpenContainerId = -1;

    public EnderChestMace() {
        super(MaceKillAddon.CATEGORY, "EnderChestMace", "Pulls the mace from an ender chest for the mace modules and puts it back afterwards.");
    }

    @Override
    public void onActivate() {
        reset();
        if (openMode.get() == OpenMode.LINKED) say("Right-click an ender chest once to link it.");
    }

    @Override
    public void onDeactivate() {
        // give the mace back and close a still-open linked chest properly
        if (mc.player != null && linkedId != -1 && linkValid()) {
            ChestMenu menu = (ChestMenu) mc.player.containerMenu;
            if (state == State.HOLDING) swapMaceOut(menu);
            if (remote() == null) mc.player.closeContainer(); // RemoteEnderChest keeps its own link open
        }
        reset();
    }

    private void reset() {
        state = State.IDLE; timer = 0; wait = 0; ticksSinceAttack = -1; enderSlot = -1; hotbarIdx = -1;
        linkedId = -1; linkedChestPos = null; relinkCooldown = 0; announcedLink = false;
        announcedRelinking = false;
        pendingChestClick = null; pendingChestClickTtl = 0; pendingOpenContainerId = -1;
        announcedWrongMenu = false; announcedWaitingModule = false;
        announcedWaitingMace = false; announcedWaitingEnemy = false; announcedRightClick = false;
        externallyHeld = false;
    }

    @EventHandler
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundAttackPacket && state == State.HOLDING) ticksSinceAttack = 0;
        if (openMode.get() == OpenMode.LINKED && linkedId == -1 && remote() == null
            && event.packet instanceof ServerboundUseItemOnPacket p
            && p.hand() == InteractionHand.MAIN_HAND) {
            BlockPos pos = p.hitResult().getBlockPos();
            if (mc.level != null && mc.level.getBlockState(pos).is(Blocks.ENDER_CHEST)) {
                pendingChestClick = pos;
                pendingChestClickTtl = 10; // the open-screen packet should arrive within a couple of ticks
                linkedChestPos = pos; // remembered for auto-relink, even before the link itself succeeds
                if (!announcedRightClick) say("Right-click on an ender chest detected, waiting for the server to open it...");
                announcedRightClick = true;
            }
        }
    }

    // The server answers a right-click that opens a container with this packet, synchronously
    // with the click itself - unlike the crosshair, it cannot drift, so this is the reliable way
    // to know "the screen that is about to open IS the ender chest I just right-clicked".
    @EventHandler
    private void onReceive(PacketEvent.Receive event) {
        if (openMode.get() != OpenMode.LINKED || linkedId != -1 || remote() != null) return;
        if (!(event.packet instanceof ClientboundOpenScreenPacket p)) return;
        if (pendingChestClick == null || pendingChestClickTtl <= 0) return;
        if (p.getType() != MenuType.GENERIC_9x3) {
            if (!announcedWrongMenu) {
                say("The server opened a different kind of menu for that ender chest (title: \""
                    + p.getTitle().getString() + "\") - LINKED mode only understands a plain 27-slot "
                    + "chest menu. If your server wraps /enderchest or the block in a custom plugin GUI, "
                    + "use BLOCK or COMMAND mode instead.");
            }
            announcedWrongMenu = true;
            pendingChestClick = null; pendingChestClickTtl = 0;
            return;
        }
        pendingOpenContainerId = p.getContainerId();
    }

    // HIGHEST so the swap lands before the mace modules look at the hotbar in the same tick.
    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) { reset(); return; }
        if (wait > 0) wait--;
        if (ticksSinceAttack >= 0) ticksSinceAttack++;
        timer++;
        if (pendingChestClickTtl > 0 && --pendingChestClickTtl == 0) pendingChestClick = null;
        if (relinkCooldown > 0) relinkCooldown--;

        if (openMode.get() == OpenMode.LINKED) { tickLinked(); return; }

        switch (state) {
            case IDLE -> tickIdle();
            case OPENING_TAKE -> tickTake();
            case HOLDING -> tickHolding();
            case OPENING_RETURN -> tickReturn();
        }
    }

    // =========================== LINKED mode (instant) ===========================
    /**
     * The vanilla server closes a container once the player is too far from the block
     * (ChestMenu.stillValid, ~8 blocks) - this fires constantly during normal combat, since
     * Trigger Range defaults to 10. Rather than make the player manually right-click again
     * every single time that happens, remember the chest's position and automatically
     * re-open it (the same synthetic right-click BLOCK mode already uses) the moment the
     * player is back within reach; the existing packet-based link detection (onSend/onReceive)
     * then picks up that re-open exactly like a real manual click.
     */
    private void tryAutoRelink() {
        if (linkedChestPos == null) {
            tryLink(); // a real manual right-click is still being waited on normally
            return;
        }
        if (relinkCooldown > 0) return;

        if (!mc.level.getBlockState(linkedChestPos).is(Blocks.ENDER_CHEST)) {
            say("Ender chest link lost and that chest is no longer there - right-click a new one.");
            linkedChestPos = null; announcedRelinking = false;
            return;
        }
        double range = Math.min(reach.get(), mc.player.blockInteractionRange() - 0.3);
        if (Vec3.atCenterOf(linkedChestPos).distanceToSqr(mc.player.getEyePosition()) > range * range) {
            if (!announcedRelinking) say("Ender chest link lost (out of range) - walk back within reach and it relinks automatically.");
            announcedRelinking = true;
            return;
        }

        if (!announcedRelinking) say("Ender chest link lost - relinking automatically...");
        announcedRelinking = true;
        openChest(linkedChestPos);
        relinkCooldown = 10; // give the server a couple of ticks to answer before trying again
        tryLink(); // picks up the resulting open-screen packet the same way a manual click does
    }

    /** The RemoteEnderChest module, when it is turned on - its link is used instead of our own. */
    private RemoteEnderChest remote() {
        RemoteEnderChest r = Modules.get().get(RemoteEnderChest.class);
        return (r != null && r.isActive()) ? r : null;
    }

    private void tickLinked() {
        RemoteEnderChest r = remote();
        if (r != null) {
            // RemoteEnderChest owns the link (it hides the screen and keeps the container open);
            // we just use its container id for the mace swaps.
            int id = r.getLinkedContainerId();
            if (id == -1) {
                if (linkedId != -1) { linkedId = -1; state = State.IDLE; enderSlot = -1; hotbarIdx = -1; }
                if (!announcedLink) { say("RemoteEnderChest is on - right-click an ender chest to link it."); announcedLink = true; }
                return;
            }
            if (linkedId != id) { linkedId = id; say("Using the RemoteEnderChest link for the mace."); }
        } else if (linkedId == -1) {
            tryAutoRelink();
            return;
        }
        if (!linkValid()) {
            linkedId = -1; announcedLink = false; state = State.IDLE; enderSlot = -1; hotbarIdx = -1;
            if (r == null) tryAutoRelink();
            return;
        }
        ChestMenu menu = (ChestMenu) mc.player.containerMenu;

        if (state == State.HOLDING) {
            if (!mc.player.getInventory().getItem(hotbarIdx).is(Items.MACE)) {
                say("Mace is no longer in the hotbar slot, forgetting it");
                state = State.IDLE; wait = cooldown.get(); return;
            }
            boolean attackedAndDone = ticksSinceAttack >= returnDelay.get();
            if (attackedAndDone || timer >= maxHold.get()) {
                if (swapMaceOut(menu)) say("Mace returned to the ender chest");
                else say("Could not return the mace to its original slot");
                state = State.IDLE; wait = cooldown.get(); enderSlot = -1; hotbarIdx = -1;
            }
            return;
        }

        if (externallyHeld || wait > 0) return;
        if (explainNotFetching()) return;
        if (swapMaceIn(menu)) say("Mace is in hotbar slot " + (hotbarIdx + 1));
        else { say("No mace in the ender chest"); wait = 40; }
    }

    /**
     * The player just right-clicked an ender chest and the server opened a matching container -
     * remember it and hide its screen without closing it (removed() only, no close packet, so
     * the server keeps the chest open). Waits for the client's own menu to catch up to the
     * container id the open packet reported, since that can be a tick behind the packet itself.
     */
    private void tryLink() {
        if (pendingOpenContainerId == -1) return;
        AbstractContainerMenu m = mc.player.containerMenu;
        if (m == mc.player.inventoryMenu || m.containerId != pendingOpenContainerId) return;

        linkedId = pendingOpenContainerId;
        pendingOpenContainerId = -1;
        pendingChestClick = null;
        mc.gui.setScreen(null);
        if (!announcedLink) say("Ender chest linked. The mace modules can now pull the mace instantly.");
        announcedLink = true;
        announcedRelinking = false;
        state = State.IDLE; timer = 0;
    }

    private boolean linkValid() {
        AbstractContainerMenu m = mc.player.containerMenu;
        return m != mc.player.inventoryMenu && m instanceof ChestMenu && m.containerId == linkedId;
    }

    // =========================== BLOCK / COMMAND modes ===========================
    private void tickIdle() {
        if (externallyHeld || wait > 0) return;
        if (explainNotFetching()) return;
        BlockPos chest = null;
        if (openMode.get() == OpenMode.BLOCK) {
            chest = findChest();
            if (chest == null) return;
        }
        if (!openChest(chest)) return;
        state = State.OPENING_TAKE; timer = 0;
        say("Opening ender chest to take the mace");
    }

    private void tickTake() {
        if (timer > 20) { abort("Chest never opened"); return; }
        ChestMenu menu = openChestMenu();
        if (menu == null) return;
        if (!swapMaceIn(menu)) { mc.player.closeContainer(); abort("No mace in the ender chest"); wait = 40; return; }
        mc.player.closeContainer();
        say("Mace is in hotbar slot " + (hotbarIdx + 1));
    }

    private void tickHolding() {
        if (!mc.player.getInventory().getItem(hotbarIdx).is(Items.MACE)) {
            say("Mace is no longer in the hotbar slot, forgetting it");
            state = State.IDLE; wait = cooldown.get(); return;
        }
        boolean attackedAndDone = ticksSinceAttack >= returnDelay.get();
        if (!attackedAndDone && timer < maxHold.get()) return;

        BlockPos chest = null;
        if (openMode.get() == OpenMode.BLOCK) {
            chest = findChest();
            if (chest == null) return; // out of reach, try again next tick
        }
        if (!openChest(chest)) return;
        state = State.OPENING_RETURN; timer = 0;
        say("Opening ender chest to return the mace");
    }

    private void tickReturn() {
        if (timer > 20) { state = State.HOLDING; timer = maxHold.get(); return; } // retry later
        ChestMenu menu = openChestMenu();
        if (menu == null) return;
        if (swapMaceOut(menu)) say("Mace returned to the ender chest");
        else say("Could not return the mace to its original slot");
        mc.player.closeContainer();
        state = State.IDLE; wait = cooldown.get(); enderSlot = -1; hotbarIdx = -1;
    }

    // =========================== shared helpers ===========================
    /** Swap the first mace in the chest into the chosen hotbar slot. */
    private boolean swapMaceIn(ChestMenu menu) {
        int chestSlots = menu.slots.size() - 36;
        int found = -1;
        for (int i = 0; i < chestSlots; i++) {
            if (menu.getSlot(i).getItem().is(Items.MACE)) { found = i; break; }
        }
        if (found == -1) return false;
        int hb = hotbarSlot.get() - 1;
        mc.gameMode.handleContainerInput(menu.containerId, found, hb, ContainerInput.SWAP, mc.player);
        enderSlot = found; hotbarIdx = hb;
        state = State.HOLDING; timer = 0; ticksSinceAttack = -1;
        return true;
    }

    /** Swap the mace back to the slot it came from (restores the hotbar item too). */
    private boolean swapMaceOut(ChestMenu menu) {
        int chestSlots = menu.slots.size() - 36;
        boolean ok = hotbarIdx >= 0 && enderSlot >= 0 && enderSlot < chestSlots
            && mc.player.getInventory().getItem(hotbarIdx).is(Items.MACE)
            && !menu.getSlot(enderSlot).getItem().is(Items.MACE);
        if (ok) mc.gameMode.handleContainerInput(menu.containerId, enderSlot, hotbarIdx, ContainerInput.SWAP, mc.player);
        return ok;
    }

    private void abort(String why) {
        say(why);
        state = State.IDLE; if (wait < cooldown.get()) wait = cooldown.get();
    }

    /**
     * The currently valid, already-open ChestMenu this module can swap against right now
     * instantly - either this module's own LINKED link, or RemoteEnderChest's, whichever is
     * active. Null for BLOCK/COMMAND modes or no link: opening a chest there is a server round
     * trip, so those cannot be instant and are not handled by the direct pull/return API below.
     */
    private ChestMenu activeLinkMenu() {
        RemoteEnderChest r = remote();
        if (r != null) {
            int id = r.getLinkedContainerId();
            if (id != -1 && mc.player != null && mc.player.containerMenu instanceof ChestMenu cm && cm.containerId == id) return cm;
            return null;
        }
        if (openMode.get() == OpenMode.LINKED && linkValid()) {
            return (ChestMenu) mc.player.containerMenu;
        }
        return null;
    }

    /**
     * Instantly swaps the mace from a persistently-linked ender chest into the configured
     * hotbar slot, for a caller that needs the mace in hand at an EXACT moment (e.g. right as a
     * totem-bypass burst fires) rather than on this module's own tick-based schedule. The swap
     * is a single container click on an already-open chest, and vanilla applies container
     * clicks to the local inventory immediately (the same client-side prediction that makes
     * chest GUIs feel instant over any latency), so the mace is already in the slot by the time
     * this method returns - no need to wait a tick.
     *
     * Returns true if the mace is in the slot (either already was, or the swap just put it
     * there). Pairs with {@link #returnMaceNow()}, called once every attack packet for this hit
     * has already been sent. Only works with a persistent link (this module's own LINKED mode,
     * or RemoteEnderChest's) - BLOCK/COMMAND modes need a server round trip to open the chest
     * and cannot be made instant, so this returns false for them without side effects.
     */
    public boolean pullMaceNow() {
        if (mc.player == null) return false;
        int hb = hotbarSlot.get() - 1;
        // A mace that is already in that slot is not ours to take back, so nothing is flagged:
        // returnMaceNow() will leave it alone.
        if (mc.player.getInventory().getItem(hb).is(Items.MACE)) return true;
        ChestMenu menu = activeLinkMenu();
        if (menu == null) return false;
        boolean ok = swapMaceIn(menu);
        if (ok) { externallyHeld = true; state = State.HOLDING; }
        return ok;
    }

    /** Swaps the mace back to wherever it came from. Call in the SAME tick as
     * {@link #pullMaceNow()}, after every attack packet for this hit has already been sent. */
    public void returnMaceNow() {
        if (!externallyHeld) return;
        ChestMenu menu = activeLinkMenu();
        if (menu != null) {
            if (!swapMaceOut(menu)) say("Could not return the mace to its original slot");
        }
        externallyHeld = false;
        state = State.IDLE; timer = 0; enderSlot = -1; hotbarIdx = -1; ticksSinceAttack = -1;
    }

    private ChestMenu openChestMenu() {
        AbstractContainerMenu m = mc.player.containerMenu;
        if (m == mc.player.inventoryMenu || !(m instanceof ChestMenu cm)) return null;
        return cm;
    }

    /**
     * True (and blocking the fetch) when one of the three gates isn't met - prints exactly
     * which one, once, so "nothing happens" has an actual answer instead of silence.
     */
    private boolean explainNotFetching() {
        if (!maceModuleActive()) {
            if (!announcedWaitingModule) say("Waiting: no mace module (MaceAttect/TpMace/MaceKillModule/MaceAura/XinTpMace/MaceMissLite/MaceDMG/Blacklist/Hitback) is turned on.");
            announcedWaitingModule = true;
            return true;
        }
        announcedWaitingModule = false;
        if (hasMace()) {
            if (!announcedWaitingMace) say("Waiting: you already have a mace somewhere in your inventory - this module only fetches when you have none.");
            announcedWaitingMace = true;
            return true;
        }
        announcedWaitingMace = false;
        if (!enemyNearby()) {
            if (!announcedWaitingEnemy) say("Waiting: no target yet (no non-friend player in Trigger Range, and Blacklist/Hitback have nothing active).");
            announcedWaitingEnemy = true;
            return true;
        }
        announcedWaitingEnemy = false;
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean maceModuleActive() {
        Modules mods = Modules.get();
        Class<?>[] classes = { MaceAttect.class, TpMace.class, MaceKillModule.class, MaceAura.class,
            XinTpMace.class, MaceMissLite.class, MaceDMG.class, Blacklist.class, Hitback.class };
        boolean instant = activeLinkMenu() != null;
        for (Class<?> c : classes) {
            // With an instant (persistent) link, MaceKillModule calls pullMaceNow()/returnMaceNow()
            // itself at the exact moment its burst fires, so the generic heuristic stays out of its way.
            if (instant && c == MaceKillModule.class) continue;
            Module m = mods.get((Class<? extends Module>) c);
            if (m != null && m.isActive()) return true;
        }
        return false;
    }

    private boolean hasMace() {
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(Items.MACE)) return true;
        return false;
    }

    /**
     * Whether to fetch/keep the mace right now. Blacklist and Hitback each report their own
     * real target directly (a blacklisted player in Blacklist's own reach, a remembered attacker
     * for Hitback), which is more precise than guessing from "someone is nearby". MaceAttect,
     * TpMace, MaceAura, XinTpMace, MaceMissLite and MaceDMG do not expose a target accessor, so
     * for those the generic "non-friend player within Trigger Range" check is used, same as before.
     */
    private boolean enemyNearby() {
        Blacklist bl = Modules.get().get(Blacklist.class);
        if (bl != null && bl.isActive() && bl.hasTargetInRange()) return true;

        Hitback hb = Modules.get().get(Hitback.class);
        if (hb != null && hb.isActive() && hb.hasActiveAttacker()) return true;

        // All living entities, not just players: MaceAttect/TpMace/etc. can target mobs too
        // (their own "Entities" setting), and this previously only scanned players, so testing
        // against a zombie or any other mob left this module linked but never fetching anything.
        double r2 = triggerRange.get() * triggerRange.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
            if (le instanceof Player p && (p.isSpectator() || Friends.get().isFriend(p))) continue;
            if (mc.player.distanceToSqr(le) <= r2) return true;
        }
        return false;
    }

    /** Nearest ender chest within reach whose top is free (a covered ender chest will not open). */
    private BlockPos findChest() {
        double range = Math.min(reach.get(), mc.player.blockInteractionRange() - 0.3);
        int r = (int) Math.ceil(range);
        BlockPos base = mc.player.blockPosition();
        Vec3 eye = mc.player.getEyePosition();
        BlockPos best = null; double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            BlockPos p = base.offset(dx, dy, dz);
            if (!mc.level.getBlockState(p).is(Blocks.ENDER_CHEST)) continue;
            if (mc.level.getBlockState(p.above()).isRedstoneConductor(mc.level, p.above())) continue;
            double d = Vec3.atCenterOf(p).distanceToSqr(eye);
            if (d > range * range) continue;
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private boolean openChest(BlockPos pos) {
        if (openMode.get() == OpenMode.COMMAND) {
            String c = command.get().trim();
            if (c.startsWith("/")) c = c.substring(1);
            if (c.isEmpty() || mc.getConnection() == null) return false;
            mc.getConnection().sendCommand(c);
            return true;
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        return true;
    }

    private void say(String msg) {
        if (chatInfo.get()) info(msg);
    }
}
