package org.dhamaal.tpaforms;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

/**
 * /dhamaalmenu (alias /dmenu) -> main menu for everyone:
 *     Bedrock players: the BedrockGUI form (runs /dhamaal)
 *     Java players:    a chest menu with the same four buttons
 * /tpamenu     -> online player list; click a player to send a TPA request
 * /playersmenu -> online player list (display only)
 *
 * The target of a TPA request gets an ACCEPT / DENY panel automatically
 * (a form for Bedrock, a chest menu for Java). All teleporting is done by the
 * existing TPA plugin: this plugin only runs tpa, tpaccept and tpdeny.
 */
public class DhamaalTpa extends JavaPlugin implements Listener {

    /** Marks an inventory as one of ours and stores what each slot does. */
    private static final class Gui implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DhamaalTPA enabled.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        boolean bedrock = isBedrock(player.getUniqueId());

        switch (cmd.getName().toLowerCase()) {
            case "dhamaalmenu":
                if (bedrock) {
                    // The BedrockGUI main menu form (registered with "command: dhamaal").
                    player.performCommand("dhamaal");
                } else {
                    openJavaMain(player);
                }
                break;
            case "tpamenu":
                if (bedrock) {
                    openTpaSelect(player);
                } else {
                    openJavaTpaSelect(player);
                }
                break;
            case "playersmenu":
                if (bedrock) {
                    openPlayersList(player);
                } else {
                    openJavaPlayers(player);
                }
                break;
            default:
                break;
        }
        return true;
    }

    private boolean isBedrock(UUID uuid) {
        return FloodgateApi.getInstance().isFloodgatePlayer(uuid);
    }

    /** Online players except the viewer, sorted by name. Built fresh on every open. */
    private List<Player> others(Player viewer) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> !p.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(p -> p.getName().toLowerCase()))
                .collect(Collectors.toList());
    }

    // ================================================================ Request flow (shared)

    private void sendRequest(Player requester, Player target) {
        if (!requester.isOnline()) {
            return;
        }
        if (!target.isOnline()) {
            requester.sendMessage("That player just went offline.");
            return;
        }

        // Runs as the requester, exactly like typing /tpa <name>.
        requester.performCommand("tpa " + target.getName());

        // Only the target sees the Accept/Deny panel.
        if (isBedrock(target.getUniqueId())) {
            openAcceptDeny(target, requester);
        } else {
            openJavaAcceptDeny(target, requester);
        }
    }

    // ================================================================ Bedrock forms

    /** Adds a button with a head icon for Java players; Bedrock players have no Java skin, so no icon. */
    private void addPlayerButton(SimpleForm.Builder form, Player p) {
        if (isBedrock(p.getUniqueId())) {
            form.button(p.getName());
        } else {
            form.button(p.getName(), FormImage.Type.URL, "https://mc-heads.net/avatar/" + p.getName());
        }
    }

    private void openTpaSelect(Player requester) {
        List<Player> targets = others(requester);

        SimpleForm.Builder form = SimpleForm.builder()
                .title("TPA - SELECT PLAYER")
                .content(targets.isEmpty()
                        ? "No other players are online right now."
                        : "Tap the player you want to teleport to.");

        for (Player t : targets) {
            addPlayerButton(form, t);
        }

        form.validResultHandler(response -> {
            int index = response.clickedButtonId();
            if (index < 0 || index >= targets.size()) {
                return;
            }
            Player target = targets.get(index);
            // Form callbacks are not on the main thread; Bukkit calls must be.
            Bukkit.getScheduler().runTask(this, () -> sendRequest(requester, target));
        });

        FloodgateApi.getInstance().sendForm(requester.getUniqueId(), form);
    }

    private void openAcceptDeny(Player target, Player requester) {
        ModalForm.Builder form = ModalForm.builder()
                .title("TELEPORT REQUEST")
                .content(requester.getName() + " wants to teleport to you.")
                .button1("ACCEPT")
                .button2("DENY");

        form.validResultHandler(response -> {
            boolean accepted = response.clickedButtonId() == 0;
            Bukkit.getScheduler().runTask(this, () -> {
                if (!target.isOnline()) {
                    return;
                }
                // Runs as the target, exactly like typing /tpaccept or /tpdeny.
                target.performCommand(accepted ? "tpaccept" : "tpdeny");
            });
        });

        FloodgateApi.getInstance().sendForm(target.getUniqueId(), form);
    }

    private void openPlayersList(Player viewer) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparing(p -> p.getName().toLowerCase()))
                .collect(Collectors.toList());

        SimpleForm.Builder form = SimpleForm.builder()
                .title("DHAMAAL SMP - PLAYERS")
                .content("Players online: " + online.size());

        for (Player p : online) {
            addPlayerButton(form, p);
        }

        FloodgateApi.getInstance().sendForm(viewer.getUniqueId(), form);
    }

    // ================================================================ Java chest menus

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plain(name, color));
        if (lore.length > 0) {
            meta.lore(Arrays.stream(lore)
                    .map(line -> plain(line, NamedTextColor.GRAY))
                    .collect(Collectors.toList()));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack head(Player owner, String... lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        meta.setOwningPlayer(owner);
        meta.displayName(plain(owner.getName(), NamedTextColor.YELLOW));
        if (lore.length > 0) {
            meta.lore(Arrays.stream(lore)
                    .map(line -> plain(line, NamedTextColor.GRAY))
                    .collect(Collectors.toList()));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Gui newGui(String title, int size) {
        Gui gui = new Gui();
        gui.inventory = Bukkit.createInventory(gui, size, Component.text(title, NamedTextColor.DARK_GRAY));
        return gui;
    }

    private void fill(Gui gui) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);
        for (int i = 0; i < gui.inventory.getSize(); i++) {
            gui.inventory.setItem(i, pane);
        }
    }

    private void set(Gui gui, int slot, ItemStack stack, Consumer<Player> action) {
        gui.inventory.setItem(slot, stack);
        if (action != null) {
            gui.actions.put(slot, action);
        }
    }

    private void openJavaMain(Player player) {
        Gui gui = newGui("DHAMAAL SMP", 27);
        fill(gui);
        set(gui, 4, item(Material.NETHER_STAR, "DHAMAAL SMP", NamedTextColor.GOLD,
                "Survive > Build > Dominate"), null);
        set(gui, 10, item(Material.ENDER_PEARL, "TPA", NamedTextColor.AQUA,
                "Teleport to another player"), this::openJavaTpaSelect);
        set(gui, 12, item(Material.NAME_TAG, "Players", NamedTextColor.YELLOW,
                "See who is online"), this::openJavaPlayers);
        set(gui, 14, item(Material.RED_BED, "Spawn", NamedTextColor.RED,
                "Teleport to spawn"), p -> {
                    p.closeInventory();
                    p.performCommand("spawn");
                });
        set(gui, 16, item(Material.ENDER_CHEST, "Ender Chest", NamedTextColor.DARK_PURPLE,
                "Open your Ender Chest"), p -> {
                    p.closeInventory();
                    p.openInventory(p.getEnderChest());
                });
        player.openInventory(gui.inventory);
    }

    private void openJavaTpaSelect(Player requester) {
        List<Player> targets = others(requester);
        if (targets.size() > 53) {
            targets = new ArrayList<>(targets.subList(0, 53));
        }
        int size = Math.max(9, (int) Math.ceil((targets.size() + 1) / 9.0) * 9);
        Gui gui = newGui("TPA - SELECT PLAYER", size);

        if (targets.isEmpty()) {
            set(gui, 0, item(Material.BARRIER, "No other players online", NamedTextColor.RED), null);
        }
        int slot = 0;
        for (Player target : targets) {
            set(gui, slot++, head(target, "Click to send a TPA request"), p -> {
                p.closeInventory();
                sendRequest(p, target);
            });
        }
        set(gui, size - 1, item(Material.ARROW, "Back", NamedTextColor.WHITE), this::openJavaMain);
        requester.openInventory(gui.inventory);
    }

    private void openJavaPlayers(Player viewer) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparing(p -> p.getName().toLowerCase()))
                .limit(53)
                .collect(Collectors.toList());
        int size = Math.max(9, (int) Math.ceil((online.size() + 1) / 9.0) * 9);
        Gui gui = newGui("PLAYERS ONLINE: " + online.size(), size);

        int slot = 0;
        for (Player p : online) {
            set(gui, slot++, head(p), null);
        }
        set(gui, size - 1, item(Material.ARROW, "Back", NamedTextColor.WHITE), this::openJavaMain);
        viewer.openInventory(gui.inventory);
    }

    private void openJavaAcceptDeny(Player target, Player requester) {
        Gui gui = newGui("TELEPORT REQUEST", 9);
        fill(gui);
        set(gui, 2, item(Material.LIME_WOOL, "ACCEPT", NamedTextColor.GREEN,
                "Let " + requester.getName() + " teleport to you"), p -> {
                    p.closeInventory();
                    p.performCommand("tpaccept");
                });
        set(gui, 4, head(requester, "wants to teleport to you"), null);
        set(gui, 6, item(Material.RED_WOOL, "DENY", NamedTextColor.RED,
                "Reject the request"), p -> {
                    p.closeInventory();
                    p.performCommand("tpdeny");
                });
        target.openInventory(gui.inventory);
    }

    // ================================================================ Chest menu clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Gui gui)) {
            return;
        }
        // Nothing can be taken out of, or moved around in, our menus.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null
                || event.getClickedInventory() != event.getInventory()) {
            return;
        }
        Consumer<Player> action = gui.actions.get(event.getRawSlot());
        if (action != null) {
            // Close/open inventories on the next tick, not inside the click event.
            Bukkit.getScheduler().runTask(this, () -> action.accept(player));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
        }
    }
}
