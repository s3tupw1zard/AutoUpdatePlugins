package spigot;

import common.ListEntryLoader;
import common.PluginUpdater;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Spigot inventory front end for list.yml and manual update actions. */
final class AupGui implements Listener {
    static final int ENTRIES_PER_PAGE = 45;
    private static final int INVENTORY_SIZE = 54;
    private static final int PREVIOUS_SLOT = 45;
    private static final int STATUS_SLOT = 49;
    private static final int NEXT_SLOT = 53;
    private static final String PERMISSION = "autoupdateplugins.manage";

    private final Plugin plugin;
    private final PluginUpdater pluginUpdater;
    private final File listFile;
    private final Supplier<String> keySupplier;
    private final Supplier<String> platformSupplier;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    AupGui(Plugin plugin, PluginUpdater pluginUpdater, File listFile,
           Supplier<String> keySupplier, Supplier<String> platformSupplier) {
        this.plugin = plugin;
        this.pluginUpdater = pluginUpdater;
        this.listFile = listFile;
        this.keySupplier = keySupplier;
        this.platformSupplier = platformSupplier;
    }

    void open(CommandSender sender, int requestedPage) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "The inventory GUI can only be opened by a player.");
            return;
        }
        Player player = (Player) sender;
        if (!player.hasPermission(PERMISSION)) {
            player.sendMessage(ChatColor.RED + "You don't have permission to use this GUI.");
            return;
        }
        openPage(player, requestedPage, Collections.<String, PluginUpdater.EntryResult>emptyMap());
    }

    void close() {
        HandlerList.unregisterAll(this);
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && isSessionInventory(player.getOpenInventory().getTopInventory(), entry.getValue())) {
                player.closeInventory();
            }
        }
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof GuiHolder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Session session = sessions.get(player.getUniqueId());
        if (!isSessionInventory(top, session) || !player.hasPermission(PERMISSION)) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        if (slot == PREVIOUS_SLOT && session.page > 1) {
            openPage(player, session.page - 1, session.results);
            return;
        }
        if (slot == NEXT_SLOT && session.page < session.totalPages) {
            openPage(player, session.page + 1, session.results);
            return;
        }
        if (slot == STATUS_SLOT) {
            openPage(player, session.page, session.results);
            return;
        }

        String entryName = session.entryBySlot.get(slot);
        if (entryName == null) {
            return;
        }
        if (pluginUpdater.isUpdating()) {
            player.sendMessage(ChatColor.YELLOW + "An update operation is already running. Try again when it finishes.");
            return;
        }

        if (event.isRightClick()) {
            toggle(player, session, entryName);
        } else if (event.isShiftClick() && event.isLeftClick()) {
            install(player, entryName);
        } else if (event.isLeftClick()) {
            check(player, entryName);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Inventory inventory = event.getInventory();
        if (!(inventory.getHolder() instanceof GuiHolder)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Session current = sessions.get(player.getUniqueId());
        if (isSessionInventory(inventory, current)) {
            sessions.remove(player.getUniqueId(), current);
        }
    }

    private void toggle(Player player, Session session, String entryName) {
        ListEntryLoader.LoadedEntry entry = findEntry(ListEntryLoader.loadList(listFile), entryName);
        if (entry == null) {
            player.sendMessage(ChatColor.RED + "That entry no longer exists in list.yml.");
            openPage(player, session.page, session.results);
            return;
        }
        boolean enable = !entry.enabled;
        try {
            AupListToggle.Result result = AupListToggle.setEnabled(listFile,
                    Collections.singleton(entry.name), enable);
            if (!enable) {
                pluginUpdater.clearPendingUpdates(Collections.singleton(entry.name));
                session.results.remove(normalize(entry.name));
            }
            if (result.changed()) {
                String groupNote = enable && !result.enabledGroups.isEmpty()
                        ? " (also enabled group " + join(result.enabledGroups) + ")" : "";
                player.sendMessage((enable ? ChatColor.GREEN : ChatColor.RED)
                        + (enable ? "Enabled " : "Disabled ") + entry.name + groupNote + ".");
            } else {
                player.sendMessage(ChatColor.YELLOW + entry.name + " is already "
                        + (enable ? "enabled" : "disabled") + ".");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Could not change list.yml state for " + entry.name + ": " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Could not update list.yml. See the server log for details.");
        }
        openPage(player, session.page, session.results);
    }

    private void check(final Player player, String entryName) {
        ListEntryLoader.LoadedEntry entry = enabledEntry(player, entryName);
        if (entry == null) {
            return;
        }
        Map<String, String> target = singletonEntry(entry);
        pluginUpdater.checkEntries(target, platform(), key(), summary -> runForPlayer(player, () -> {
            Session current = sessions.get(player.getUniqueId());
            PluginUpdater.EntryResult result = summary == null ? PluginUpdater.EntryResult.FAILED
                    : summary.entries.get(entry.name);
            if (current != null) {
                if (result == null) {
                    current.results.remove(normalize(entry.name));
                } else {
                    current.results.put(normalize(entry.name), result);
                }
            }
            if (result == PluginUpdater.EntryResult.AVAILABLE) {
                player.sendMessage(ChatColor.GOLD + "An update is available for " + entry.name + ". Shift-left-click to install it.");
            } else if (result == PluginUpdater.EntryResult.UNCHANGED) {
                player.sendMessage(ChatColor.GREEN + entry.name + " is up to date.");
            } else {
                player.sendMessage(ChatColor.RED + "The update check for " + entry.name + " failed. See the server log.");
            }
            refreshCurrent(player);
        }));
        player.sendMessage(ChatColor.AQUA + "Checking " + entry.name + " for updates...");
        refreshCurrent(player);
    }

    private void install(final Player player, String entryName) {
        ListEntryLoader.LoadedEntry entry = enabledEntry(player, entryName);
        if (entry == null) {
            return;
        }
        pluginUpdater.updateEntries(singletonEntry(entry), platform(), key(), anyApplied ->
                runForPlayer(player, () -> {
                    Session current = sessions.get(player.getUniqueId());
                    if (current != null) {
                        if (anyApplied) {
                            current.results.put(normalize(entry.name), PluginUpdater.EntryResult.APPLIED);
                        } else {
                            current.results.remove(normalize(entry.name));
                        }
                    }
                    player.sendMessage(anyApplied
                            ? ChatColor.GREEN + "Installed the update for " + entry.name + "."
                            : ChatColor.YELLOW + "No update was installed for " + entry.name + "; see the server log for details.");
                    refreshCurrent(player);
                }));
        player.sendMessage(ChatColor.AQUA + "Installing the latest eligible version of " + entry.name + "...");
        refreshCurrent(player);
    }

    private ListEntryLoader.LoadedEntry enabledEntry(Player player, String entryName) {
        ListEntryLoader.LoadedEntry entry = findEntry(ListEntryLoader.loadList(listFile), entryName);
        if (entry == null) {
            player.sendMessage(ChatColor.RED + "That entry no longer exists in list.yml.");
            refreshCurrent(player);
            return null;
        }
        if (!entry.enabled) {
            player.sendMessage(ChatColor.YELLOW + "Enable " + entry.name + " before checking or installing it.");
            return null;
        }
        return entry;
    }

    private void refreshCurrent(Player player) {
        Session current = sessions.get(player.getUniqueId());
        if (current != null && isSessionInventory(player.getOpenInventory().getTopInventory(), current)) {
            openPage(player, current.page, current.results);
        }
    }

    private void openPage(Player player, int requestedPage, Map<String, PluginUpdater.EntryResult> previousResults) {
        ListEntryLoader.LoadedList loaded = ListEntryLoader.loadList(listFile);
        List<ListEntryLoader.LoadedEntry> entries = new ArrayList<>(loaded.entries.values());
        int totalPages = totalPages(entries.size());
        int page = clampPage(requestedPage, totalPages);

        Set<String> enabledNames = new LinkedHashSet<>();
        for (ListEntryLoader.LoadedEntry entry : entries) {
            if (entry.enabled) {
                enabledNames.add(entry.name);
            }
        }
        pluginUpdater.retainPendingUpdates(enabledNames);
        Map<String, PluginUpdater.PendingUpdate> pending = pluginUpdater.getPendingUpdates();

        Session session = new Session(player.getUniqueId(), page, totalPages, previousResults);
        GuiHolder holder = new GuiHolder(session);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE,
                "AUP Plugins " + page + "/" + totalPages);
        holder.inventory = inventory;
        session.inventory = inventory;

        int start = (page - 1) * ENTRIES_PER_PAGE;
        int end = Math.min(entries.size(), start + ENTRIES_PER_PAGE);
        for (int index = start; index < end; index++) {
            int slot = index - start;
            ListEntryLoader.LoadedEntry entry = entries.get(index);
            PluginUpdater.PendingUpdate pendingUpdate = findPending(pending, entry.name);
            PluginUpdater.EntryResult lastResult = session.results.get(normalize(entry.name));
            inventory.setItem(slot, entryItem(entry, pendingUpdate, lastResult));
            session.entryBySlot.put(slot, entry.name);
        }

        if (page > 1) {
            inventory.setItem(PREVIOUS_SLOT, simpleItem(Material.ARROW, (short) 0,
                    ChatColor.AQUA + "Previous page", Collections.singletonList(ChatColor.GRAY + "Page " + (page - 1))));
        }
        inventory.setItem(STATUS_SLOT, statusItem(entries.size(), pending.size(), page, totalPages));
        if (page < totalPages) {
            inventory.setItem(NEXT_SLOT, simpleItem(Material.ARROW, (short) 0,
                    ChatColor.AQUA + "Next page", Collections.singletonList(ChatColor.GRAY + "Page " + (page + 1))));
        }

        sessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
    }

    private ItemStack entryItem(ListEntryLoader.LoadedEntry entry, PluginUpdater.PendingUpdate pending,
                                PluginUpdater.EntryResult lastResult) {
        short color = entry.enabled ? (short) 5 : (short) 14;
        if (entry.enabled && pending != null) {
            color = 4;
        } else if (entry.enabled && lastResult == PluginUpdater.EntryResult.FAILED) {
            color = 1;
        } else if (entry.enabled && lastResult == PluginUpdater.EntryResult.APPLIED) {
            color = 3;
        }

        List<String> lore = new ArrayList<>();
        lore.add((entry.enabled ? ChatColor.GREEN : ChatColor.RED)
                + (entry.enabled ? "Enabled" : "Disabled"));
        if (entry.group != null && !entry.group.trim().isEmpty()) {
            lore.add(ChatColor.GRAY + "Group: " + ChatColor.WHITE + safe(entry.group, 48));
        }
        lore.add(ChatColor.GRAY + "Source: " + ChatColor.DARK_GRAY + safe(entry.link, 68));
        if (pending != null) {
            String details = pending.versionAndProvider();
            lore.add(ChatColor.GOLD + "Update available"
                    + (details.isEmpty() ? "" : ChatColor.GRAY + ": " + safe(details, 55)));
            String changelog = pending.changelogPreview();
            if (changelog != null) {
                lore.add(ChatColor.DARK_GRAY + "Changelog: " + safe(changelog, 65));
            }
            if (pending.requiresManualAction()) {
                lore.add(ChatColor.RED + "Manual download required");
                lore.add(ChatColor.GRAY + safe(pending.manualReason, 65));
                lore.add(ChatColor.AQUA + safe(pending.actionUrl, 65));
            }
        } else if (lastResult == PluginUpdater.EntryResult.UNCHANGED) {
            lore.add(ChatColor.GREEN + "Last check: up to date");
        } else if (lastResult == PluginUpdater.EntryResult.FAILED) {
            lore.add(ChatColor.RED + "Last operation failed");
        } else if (lastResult == PluginUpdater.EntryResult.APPLIED) {
            lore.add(ChatColor.AQUA + "Update installed");
        }
        lore.add("");
        lore.add(ChatColor.YELLOW + "Left-click" + ChatColor.GRAY + " to check");
        lore.add(ChatColor.YELLOW + "Shift-left-click" + ChatColor.GRAY + " to install");
        lore.add(ChatColor.YELLOW + "Right-click" + ChatColor.GRAY + " to "
                + (entry.enabled ? "disable" : "enable"));

        Material wool = woolMaterial(color);
        short data = "WOOL".equals(wool.name()) ? legacyWoolData(color) : 0;
        return simpleItem(wool, data,
                (entry.enabled ? ChatColor.GREEN : ChatColor.RED) + safe(entry.name, 48), lore);
    }

    private ItemStack statusItem(int entries, int pending, int page, int totalPages) {
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Configured: " + ChatColor.WHITE + entries);
        lore.add(ChatColor.GRAY + "Pending: " + (pending > 0 ? ChatColor.GOLD : ChatColor.GREEN) + pending);
        lore.add(ChatColor.GRAY + "Page: " + ChatColor.WHITE + page + "/" + totalPages);
        lore.add("");
        lore.add(ChatColor.YELLOW + "Click to refresh");
        return simpleItem(pluginUpdater.isUpdating() ? material("CLOCK", "WATCH") : Material.BOOK, (short) 0,
                pluginUpdater.isUpdating() ? ChatColor.AQUA + "Update operation running" : ChatColor.AQUA + "AutoUpdatePlugins",
                lore);
    }

    private static Material woolMaterial(short color) {
        return material(modernWoolName(color), "WOOL");
    }

    static String modernWoolName(short color) {
        if (color == 1 || color == 14) return "RED_WOOL";
        if (color == 3) return "LIGHT_BLUE_WOOL";
        if (color == 4) return "YELLOW_WOOL";
        return "LIME_WOOL";
    }

    static short legacyWoolData(short color) {
        return color == 1 ? (short) 14 : color;
    }

    private static Material material(String... names) {
        for (String name : names) {
            Material found = Material.matchMaterial(name);
            if (found != null) return found;
        }
        throw new IllegalStateException("No compatible Bukkit material found: " + java.util.Arrays.toString(names));
    }

    private static ItemStack simpleItem(Material material, short data, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material, 1, data);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void runForPlayer(Player player, Runnable action) {
        if (player == null || action == null || !plugin.isEnabled()) {
            return;
        }
        try {
            Object scheduler = player.getClass().getMethod("getScheduler").invoke(player);
            java.lang.reflect.Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            Consumer<Object> consumer = ignored -> {
                if (player.isOnline() && plugin.isEnabled()) {
                    action.run();
                }
            };
            run.invoke(scheduler, plugin, consumer, null);
            return;
        } catch (Throwable ignored) {
            // Folia's entity scheduler is optional; Spigot/Paper use the Bukkit scheduler.
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && plugin.isEnabled()) {
                action.run();
            }
        });
    }

    private static ListEntryLoader.LoadedEntry findEntry(ListEntryLoader.LoadedList loaded, String name) {
        if (loaded == null || name == null) {
            return null;
        }
        ListEntryLoader.LoadedEntry direct = loaded.entries.get(name);
        if (direct != null) {
            return direct;
        }
        for (ListEntryLoader.LoadedEntry entry : loaded.entries.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    private static PluginUpdater.PendingUpdate findPending(Map<String, PluginUpdater.PendingUpdate> pending, String name) {
        PluginUpdater.PendingUpdate direct = pending.get(name);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, PluginUpdater.PendingUpdate> entry : pending.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> singletonEntry(ListEntryLoader.LoadedEntry entry) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put(entry.name, entry.link);
        return result;
    }

    private String key() {
        String key = keySupplier == null ? null : keySupplier.get();
        return key == null ? "" : key;
    }

    private String platform() {
        String platform = platformSupplier == null ? null : platformSupplier.get();
        return platform == null || platform.trim().isEmpty() ? "spigot" : platform;
    }

    static int totalPages(int entryCount) {
        return Math.max(1, (Math.max(0, entryCount) + ENTRIES_PER_PAGE - 1) / ENTRIES_PER_PAGE);
    }

    static int clampPage(int page, int totalPages) {
        return Math.max(1, Math.min(Math.max(1, totalPages), page));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String safe(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\u00a7', '?').replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        while (compact.contains("  ")) {
            compact = compact.replace("  ", " ");
        }
        return compact.length() > maxLength ? compact.substring(0, Math.max(0, maxLength - 3)) + "..." : compact;
    }

    private static String join(Collection<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(value);
        }
        return result.toString();
    }

    private static boolean isSessionInventory(Inventory inventory, Session session) {
        return inventory != null && session != null && session.inventory == inventory
                && inventory.getHolder() instanceof GuiHolder
                && ((GuiHolder) inventory.getHolder()).session == session;
    }

    private static final class Session {
        final UUID playerId;
        final int page;
        final int totalPages;
        final Map<Integer, String> entryBySlot = new HashMap<>();
        final Map<String, PluginUpdater.EntryResult> results = new HashMap<>();
        Inventory inventory;

        Session(UUID playerId, int page, int totalPages, Map<String, PluginUpdater.EntryResult> previousResults) {
            this.playerId = playerId;
            this.page = page;
            this.totalPages = totalPages;
            if (previousResults != null) {
                this.results.putAll(previousResults);
            }
        }
    }

    private static final class GuiHolder implements InventoryHolder {
        final Session session;
        Inventory inventory;

        GuiHolder(Session session) {
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
