package com.macekill.addon.commands;

import com.macekill.addon.modules.Hitback;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * .protect — manage Hitback's Protect list (teammates to auto-avenge).
 *
 * <p>.protect add &lt;player&gt; — protect a player by name.
 * <p>.protect remove &lt;player&gt; — unprotect a name.
 * <p>.protect list — entries and module state.
 */
public class ProtectCommand extends Command {
    public ProtectCommand() {
        super("protect", "Manage Hitback's Protect list (add/remove/list).");
    }

    @Override
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
        builder
            .then(literal("add")
                .then(argument("player", StringArgumentType.word())
                    .suggests((context, suggestions) ->
                        SharedSuggestionProvider.suggest(onlineNames(), suggestions))
                    .executes(context -> {
                        Hitback module = Hitback.get();
                        if (module == null) { error("Hitback module is not registered."); return 0; }
                        String stored = module.addProtect(StringArgumentType.getString(context, "player"));
                        if (stored == null) { error("That name is already protected."); return 0; }
                        info("%s is now protected. (%d name(s))", stored, module.protectSnapshot().size());
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("remove")
                .then(argument("player", StringArgumentType.word())
                    .suggests((context, suggestions) ->
                        SharedSuggestionProvider.suggest(protectNames(), suggestions))
                    .executes(context -> {
                        Hitback module = Hitback.get();
                        if (module == null) { error("Hitback module is not registered."); return 0; }
                        String removed = module.removeProtect(StringArgumentType.getString(context, "player"));
                        if (removed == null) { error("That name is not protected."); return 0; }
                        info("%s removed from the protect list. (%d name(s) left)", removed, module.protectSnapshot().size());
                        return SINGLE_SUCCESS;
                    })))
            .then(literal("list")
                .executes(context -> { printStatus(); return SINGLE_SUCCESS; }))
            .executes(context -> {
                printStatus();
                warning("Usage: .protect add/remove <player> | list");
                return SINGLE_SUCCESS;
            });
    }

    private void printStatus() {
        Hitback module = Hitback.get();
        if (module == null) { error("Hitback module is not registered."); return; }
        List<String> entries = module.protectSnapshot();
        if (entries.isEmpty()) info("Protect list is empty.");
        else info("Protect list (%d): %s", entries.size(), String.join(", ", entries));
        info("Hitback module is %s.", module.isActive() ? "ON" : "off");
    }

    private List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        if (mc != null && mc.level != null) {
            for (Player player : mc.level.players()) {
                if (player == mc.player) continue;
                names.add(player.getName().getString());
            }
        }
        return names;
    }

    private List<String> protectNames() {
        Hitback module = Hitback.get();
        return module == null ? List.of() : module.protectSnapshot();
    }
}
