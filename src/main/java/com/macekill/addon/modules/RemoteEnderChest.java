package com.macekill.addon.modules;

import com.macekill.addon.MaceKillAddon;
import meteordevelopment.meteorclient.events.meteor.KeyInputEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IMinecraft;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static com.mojang.blaze3d.platform.InputConstants.KEY_LALT;
import static com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE;
import static com.mojang.blaze3d.platform.InputConstants.KEY_RALT;

/**
 * Port of etianl's Trouser-Streak "RemoteEnderChest" (GPL-3.0, like this addon).
 *
 * Right-click an ender chest once while this is on: the screen is hidden WITHOUT sending the
 * close packet, so the server keeps that container open and clicks on it keep working.
 *
 * Note (checked against the 26.3 server code): the server re-validates every open container on
 * every tick (ServerPlayer.tick -> containerMenu.stillValid), and for an ender chest that is the
 * normal block-distance check, so a vanilla server will still close it once you are far from the
 * chest. This module reports that as "link broken" exactly like the original does.
 *
 * EnderChestMace uses this module's link automatically when it is turned on, so the mace modules
 * can pull the mace out of the linked ender chest.
 */
public class RemoteEnderChest extends Module {
    private static RemoteEnderChest INSTANCE;

    private final SettingGroup sgGeneral = settings.createGroup("RemoteEnderChest");
    private final SettingGroup sgItemSaver = settings.createGroup("ItemSaver");

    private final Setting<Keybind> toggleGui = sgGeneral.add(new KeybindSetting.Builder()
            .name("GUI Key (Press it)")
            .description("Key to toggle Ender Chest GUI.")
            .defaultValue(Keybind.fromKey(KEY_LALT))
            .build()
    );
    private final Setting<Boolean> invKeyBlocker = sgGeneral.add(new BoolSetting.Builder()
            .name("inventory-key-modifier")
            .description("Make the Inventory Key open the EnderChest GUI when you have one saved. This helps to prevent you accidentally breaking the link.")
            .defaultValue(true)
            .build()
    );
    private final Setting<Boolean> enableItemSaver = sgItemSaver.add(new BoolSetting.Builder()
            .name("enable-item-saver")
            .description("Automatically moves items in your inventory into the linked Ender Chest when low health.")
            .defaultValue(false)
            .build()
    );
    private final Setting<Double> healthThreshold = sgItemSaver.add(new DoubleSetting.Builder()
            .name("health-threshold")
            .description("Move the items to the open Ender Chest when health is equal to or less than this value (2.0 = 1 heart).")
            .defaultValue(4.0)
            .min(0.0)
            .max(20.0)
            .sliderRange(0.0, 20.0)
            .visible(enableItemSaver::get)
            .build()
    );
    private final Setting<Boolean> totemCheck = sgItemSaver.add(new BoolSetting.Builder()
            .name("check-for-totem")
            .description("If you are low health and are holding a Totem of Undying, do not store the items.")
            .defaultValue(true)
            .visible(enableItemSaver::get)
            .build()
    );
    private final Setting<List<Item>> items = sgItemSaver.add(new ItemListSetting.Builder()
            .name("Items to save")
            .description("These items will be stored.")
            .visible(enableItemSaver::get)
            .build()
    );
    private final Setting<Boolean> keepInv = sgItemSaver.add(new BoolSetting.Builder()
            .name("keep-inventory")
            .description("Try to save the whole inventory. The Items to save list will be prioritized.")
            .defaultValue(true)
            .visible(enableItemSaver::get)
            .build()
    );
    private final Setting<Keybind> itemSaverHotkey = sgGeneral.add(new KeybindSetting.Builder()
            .name("Item Saver HotKey")
            .description("You can quickly store items when pressing this button.")
            .defaultValue(Keybind.fromKey(KEY_RALT))
            .build()
    );

    public RemoteEnderChest() {
        super(MaceKillAddon.CATEGORY, "RemoteEnderChest", "Keep an ender chest open after right-clicking it and use it while it stays open. EnderChestMace uses this link for the mace.");
        INSTANCE = this;
    }

    private boolean guiHidden = false;
    private boolean guiWasOpen = false;
    private boolean lastKeyState = false;
    private ContainerScreen savedScreen = null;
    private int savedSyncId = -1;
    private Level lastWorld = null;
    private BlockPos potentialEChestPos = null;
    private BlockPos linkedChestPos = null;

    public static RemoteEnderChest get() {
        return INSTANCE;
    }

    /** Container id of the still-open, hidden ender chest, or -1 when there is no link. */
    public int getLinkedContainerId() {
        return savedScreen != null ? savedSyncId : -1;
    }

    @Override
    public void onDeactivate() {
        resetStuff();
    }

    @EventHandler
    private void onKey(KeyInputEvent event) {
        if (savedScreen == null) return;
        if (event.action != KeyAction.Press) return;

        int inventoryKeyCode = mc.options.keyInventory.getDefaultKey().getValue();

        if (!guiHidden) {
            if (event.key() == inventoryKeyCode) {
                event.setCancelled(true);
                mc.gui.setScreen(null);
                guiHidden = true;
                return;
            }
            // Escape would normally run the screen's own onClose(), which sends the real close
            // packet and ends the server's container session - the link is lost for good, same
            // as the inventory key would do without the block above. Treat it the same way: hide
            // instead of actually closing, exactly like pressing the GUI toggle key would.
            if (event.key() == KEY_ESCAPE && mc.gui.screen() == savedScreen) {
                event.setCancelled(true);
                mc.gui.setScreen(null);
                guiHidden = true;
                return;
            }
        }

        if (guiHidden && invKeyBlocker.get()) {
            if (event.key() == inventoryKeyCode) {
                event.setCancelled(true);
                mc.gui.setScreen(savedScreen);
                guiHidden = false;
            }
        }
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null) return;

        if (mc.hitResult instanceof BlockHitResult bhr) {
            potentialEChestPos = bhr.getBlockPos();
            if (mc.level.getBlockState(bhr.getBlockPos()).getBlock() == Blocks.ENDER_CHEST
                    && mc.options.keyUse.isDown() && !isEnderChestScreen(potentialEChestPos) && !guiHidden) {
                // startUseItem() is private in 26.3; Meteor exposes it through IMinecraft. Called once per tick
                // (the original calls it twice) - the use key is held, so it repeats every tick anyway.
                ((IMinecraft) mc).meteor$rightClick();
            }
        } else potentialEChestPos = null;

        if (isEnderChestScreen(potentialEChestPos) && savedScreen == null && !guiWasOpen && !guiHidden) {
            savedScreen = (ContainerScreen) mc.gui.screen();
            savedSyncId = mc.player.containerMenu.containerId;
            linkedChestPos = potentialEChestPos;
            mc.gui.setScreen(null);
            guiHidden = true;
            guiWasOpen = true;
            lastWorld = mc.level;
            if (chatFeedback) info("EChest link created! Press " + toggleGui.get().toString() + " to toggle the Ender Chest GUI.");
        }

        boolean keyDown = toggleGui.get().isPressed();
        boolean keyJustPressed = keyDown && !lastKeyState;
        lastKeyState = keyDown;

        if (keyJustPressed && savedScreen != null) {
            if (guiHidden) {
                mc.gui.setScreen(savedScreen);
                guiHidden = false;
            } else {
                mc.gui.setScreen(null);
                guiHidden = true;
            }
        }

        if (savedScreen != null) {
            if (mc.gui.screen() == null && !guiHidden && guiWasOpen) {
                String why = breakInfo();
                resetStuff();
                if (chatFeedback) error("Ender Chest GUI closed. EChest link broken." + why);
                return;
            }

            if (savedSyncId != -1) {
                boolean handlerValid = mc.player.containerMenu != null &&
                        mc.player.containerMenu.containerId == savedSyncId;
                if (!handlerValid) {
                    String why = breakInfo();
                    resetStuff();
                    if (chatFeedback) error("Ender chest handler invalid (the server closed it). EChest link broken." + why);
                    return;
                }
            }

            if (mc.level != lastWorld) {
                resetStuff();
                lastWorld = mc.level;
                if (chatFeedback) error("World changed. EChest link broken.");
                return;
            }

            checkAndSaveItems();
        }

        lastWorld = mc.level;
    }

    /**
     * Diagnostic appended to every "link broken" message: how far you were from the chest and whether
     * the chest block is still there. The server re-validates an open container every tick, so this
     * shows exactly what it was reacting to (distance vs. chest gone vs. you closed it yourself).
     */
    private String breakInfo() {
        if (linkedChestPos == null || mc.player == null || mc.level == null) return "";
        double d = Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(linkedChestPos)));
        boolean there = mc.level.getBlockState(linkedChestPos).getBlock() == Blocks.ENDER_CHEST;
        return String.format(" [%.1f blocks from the chest, chest block %s]", d, there ? "still there" : "gone or unloaded");
    }

    private void checkAndSaveItems() {
        if (!enableItemSaver.get()) return;

        boolean hasTotem = mc.player.getMainHandItem().is(Items.TOTEM_OF_UNDYING) ||
                mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

        boolean shouldCheckTotem = totemCheck.get() && hasTotem;

        boolean triggerAuto = mc.player.getHealth() <= healthThreshold.get() && !shouldCheckTotem;
        boolean triggerManual = itemSaverHotkey.get().isPressed();

        if (!triggerAuto && !triggerManual) return;

        if (!(mc.player.containerMenu instanceof ChestMenu handler)) return;
        if (handler.containerId != savedSyncId) return;

        List<Item> targetItems = items.get();
        if (targetItems.isEmpty()) return;

        boolean movedAny = false;

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.slots.get(i);

            if (slot.container != mc.player.getInventory()) continue;

            ItemStack stack = handler.slots.get(i).getItem();
            if (stack == null || stack.isEmpty()) continue;

            if (targetItems.contains(stack.getItem())) {
                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                movedAny = true;
            }
        }
        if (keepInv.get()) {
            for (int i = 0; i < handler.slots.size(); i++) {
                Slot slot = handler.slots.get(i);

                if (slot.container != mc.player.getInventory()) continue;

                ItemStack stack = handler.slots.get(i).getItem();
                if (stack == null || stack.isEmpty()) continue;

                mc.gameMode.handleContainerInput(handler.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
                movedAny = true;
            }
        }

        if (movedAny) {
            if (chatFeedback) warning("Items saved into Ender Chest!");
        }
    }

    private boolean isEnderChestScreen(BlockPos echest) {
        if (echest == null || mc.level == null) return false;
        return mc.gui.screen() instanceof ContainerScreen &&
                mc.level.getBlockState(echest).getBlock() == Blocks.ENDER_CHEST;
    }

    private void resetStuff() {
        guiHidden = false;
        guiWasOpen = false;
        lastKeyState = false;
        potentialEChestPos = null;
        if (savedSyncId != -1) {
            if (mc.getConnection() != null) mc.getConnection().send(new ServerboundContainerClosePacket(savedSyncId));
            savedSyncId = -1;
        }
        savedScreen = null;
        linkedChestPos = null;
    }
}
