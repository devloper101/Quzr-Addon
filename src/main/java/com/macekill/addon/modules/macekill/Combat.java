package com.macekill.addon.modules.macekill;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class Combat {
    private Combat() {}

    public static boolean executeAttack(Minecraft mc, Config config,
                                        LivingEntity target, Vec3 targetPos) {
        if (target == null || mc.getConnection() == null) {
            return true;
        }

        List<String> rawHeights;
        boolean armorDestroyed;
        if (config.enableArmorDestroy() && needsArmorDestroy(target, config.ignoreArmorValue())) {
            rawHeights = config.destroyHeights();
            armorDestroyed = false;
        } else {
            rawHeights = config.killHeights();
            armorDestroyed = true;
        }

        List<Double> heights = parseHeights(rawHeights);
        if (heights.isEmpty()) {
            heights.add(20.0);
        }

        int oldSlot = InvHelper.switchToMace(mc);
        if (oldSlot == -1) {
            return armorDestroyed;
        }

        try {
            Vec3 basePos = new Vec3(mc.player.getX(), mc.player.getY(), mc.player.getZ());

            for (double h : heights) {
                BlockPos vclipHole = findVclipHole(mc,
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), h);
                Vec3 vclipPos = Vec3.atBottomCenterOf(vclipHole);

                Movement.doTpTo(mc, vclipPos, config.moveDistance(), config.syncClientPos());
                Movement.sendMovePacket(mc, targetPos.x, targetPos.y + 0.5, targetPos.z);
                Movement.sendMovePacket(mc, basePos.x, basePos.y, basePos.z);

                if (config.swingHand()) {
                    mc.player.swing(InteractionHand.MAIN_HAND, mc.player.getMainHandItem().getAttackAnimation(), true);
                }
                Movement.attackEntity(mc, target);
            }
        } finally {
            if (config.autoTotem()) {
                int totemSlot = InvHelper.findTotemSlot(mc);
                if (totemSlot != -1) {
                    InvHelper.setSelectedSlot(mc.player.getInventory(), totemSlot);
                    Movement.sendSlotPacket(mc, totemSlot);
                } else {
                    InvHelper.switchBack(mc, oldSlot);
                }
            } else {
                InvHelper.switchBack(mc, oldSlot);
            }
        }
        return armorDestroyed;
    }

    public static int worldTop(Minecraft mc) {
        if (mc != null && mc.level != null) {
            try {
                return mc.level.getMaxY();
            } catch (Exception ignored) {}
        }
        return 319;
    }

    /**
     * Highest safe 2-tall destination above the player, up to {@code vclip} blocks. The server
     * validates the ASCENT packet by its endpoint hitbox only, not the path crossed to get there
     * (same reasoning as MaceAttect's Cave Safe), so an air pocket above a thin cave roof is a
     * valid ascent target even though a straight contiguous scan from the player's feet would
     * stop at the roof. The contiguous scan is the fallback for when no higher pocket exists.
     */
    public static BlockPos findVclipHole(Minecraft mc,
                                         double x, double y, double z, double vclip) {
        BlockPos base = BlockPos.containing(x, y, z);
        if (vclip <= 0.0) {
            return base;
        }

        int bx = base.getX();
        int by = base.getY();
        int bz = base.getZ();
        int top = by + (int) vclip;
        top = Math.min(top, worldTop(mc));

        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        // Highest clear 2-tall pocket first - this is what makes a low cave roof with open
        // space above it usable instead of capping headroom at the roof.
        for (int cy = top; cy > by; cy--) {
            if (isTwoTallPocket(mc, bx, cy, bz, mutable)) return new BlockPos(bx, cy, bz);
        }

        // Fallback: contiguous clearance straight up from the player (open sky / no roof).
        for (int cy = by + 1; cy <= top; cy++) {
            mutable.set(bx, cy, bz);
            if (!vclipSafe(mc, mutable)) {
                if (cy - 1 <= by) return base;
                mutable.set(bx, cy - 1, bz);
                if (!vclipSafe(mc, mutable)) return base;
                return new BlockPos(bx, cy - 1, bz);
            }
        }
        return new BlockPos(bx, top, bz);
    }

    private static boolean isTwoTallPocket(Minecraft mc, int bx, int cy, int bz, BlockPos.MutableBlockPos mutable) {
        mutable.set(bx, cy, bz);
        if (!vclipSafe(mc, mutable)) return false;
        mutable.set(bx, cy - 1, bz);
        return vclipSafe(mc, mutable);
    }

    // A vclip destination must be an air block (or leaves: we can teleport straight through
    // foliage to reach clear sky above a tree canopy, or water: this is only ever used as a
    // scratch waypoint to ascend to - not a final attack/landing position - so water doesn't
    // need to be excluded the way it does for MaceAttect's fall-damage-sensitive Cave Safe
    // landing spot). Lava and cobwebs stay unsafe.
    private static boolean vclipSafe(Minecraft mc, BlockPos pos) {
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        if (state.is(Blocks.COBWEB)) return false;
        if (state.isAir()) return true;
        if (state.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return true;
        if (!state.getFluidState().isEmpty()) return false; // lava etc.
        return state.getBlock() instanceof LeavesBlock;
    }

    public static boolean needsArmorDestroy(LivingEntity entity, int ignoreArmorValue) {
        if (!(entity instanceof Player player)) {
            return false;
        }
        int count = 0;
        for (int i = 36; i <= 39; i++) {
            if (player.getInventory().getItem(i).isEmpty()) continue;
            count++;
        }
        return count > ignoreArmorValue;
    }

    public static List<Double> parseHeights(List<String> raw) {
        List<Double> list = new ArrayList<>();
        for (String s : raw) {
            try {
                double v = Double.parseDouble(s.trim());
                if (v > 0.0) {
                    list.add(v);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return list;
    }

    // Available vertical headroom (in blocks) directly above the target before a solid
    // roof is hit. In a cave this is small; in open sky it is large.
    public static int getVclipClearance(Minecraft mc, LivingEntity target) {
        if (mc == null || target == null) return 0;
        BlockPos hole = findVclipHole(mc, target.getX(), target.getY(), target.getZ(), worldTop(mc));
        return hole.getY() - (int) Math.floor(target.getY());
    }

    // Builds the totem-bypass height list: (totemCount + 1) hits at STRICTLY INCREASING
    // heights, all within the available headroom above the target. Each successive mace
    // hit deals strictly more damage, which bypasses the server's hurtResistantTime
    // invulnerability check (amount <= lastDamage is ignored), popping every held totem.
    //
    // Crucially the heights are spread across the REAL clearance (not a fixed 170), so
    // under a roof / in a cave the hits stay distinct instead of all collapsing onto the
    // ceiling height (which would make them deal identical damage and only pop one totem).
    public static final int MAX_TOTEM_HITS = 198;
    public static List<String> totemBypassHeights(int totemCount, int maxHeight) {
        List<String> all = new ArrayList<>();
        int avail = Math.max(0, maxHeight);
        int minLethal = 6; // ~24 mace damage - safely lethal for a 20 HP target (attack lands
                           // ~0.5 blocks above the target, so effective fall is a touch less)
        if (avail < minLethal) return all; // no headroom for even one lethal hit

        int kills = totemCount + 1;
        int span = avail - minLethal;
        int maxKills = span + 1;        // distinct integer heights available in [minLethal, avail]
        kills = Math.min(kills, maxKills);

        if (kills < 1) return all;
        int denom = Math.max(1, kills - 1);
        for (int i = 0; i < kills; i++) {
            int off = minLethal + (int) Math.round((double) i * span / denom);
            if (off > avail) off = avail;
            if (off < minLethal) off = minLethal;
            all.add(String.valueOf(off));
        }
        return all;
    }

    // Drives the totem bypass across multiple ticks. Each tick we hand out a small
    // slice of the (strictly increasing) height list; the server recomputes the mace
    // fall distance once per tick, so spacing the hits out lets every totem pop.
    public static final class BypassRunner {
        private final List<Double> heights;
        private int idx;

        public BypassRunner(List<Double> heights) {
            this.heights = heights;
            this.idx = 0;
        }

        public boolean hasMore() {
            return idx < heights.size();
        }

        public int remaining() {
            return heights.size() - idx;
        }

        public List<Double> next(int count) {
            List<Double> out = new ArrayList<>();
            while (idx < heights.size() && out.size() < count) {
                out.add(heights.get(idx++));
            }
            return out;
        }
    }
}
