package dev.skyblockflipper;

import com.google.gson.JsonObject;
import dev.skyblockflipper.mixin.HoveredSlotAccess;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

public final class MarketLookup {
    private static final Logger LOGGER = LoggerFactory.getLogger(MarketLookup.class);
    private static final long CACHE_MS = Duration.ofMinutes(15).toMillis();
    private static final long RETRY_MS = Duration.ofMinutes(1).toMillis();
    private static MarketLookup instance;

    record ItemInfo(String name, boolean auctionExplicit, boolean auctionBlocked, boolean soulbound) {}
    record Catalog(Set<String> bazaarProducts, Map<String, ItemInfo> items, long expiresAt) {}
    record Query(String itemId, String productId, String hoverName, boolean soulbound, String auctionId) {}
    private record Pending(AbstractContainerScreen<?> screen, Slot slot, Query query, ClientPacketListener connection) {}

    private final KeyMapping key;
    private final SkyBlockLocation location;
    private final HypixelAuctions auctions;
    private final Executor worker;
    private Catalog catalog;
    private Pending pending;
    private long retryAt;
    private boolean loading;

    MarketLookup(KeyMapping key, SkyBlockLocation location, HypixelAuctions auctions, Executor worker) {
        this.key = key;
        this.location = location;
        this.auctions = auctions;
        this.worker = worker;
    }

    void register() {
        instance = this;
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof AbstractContainerScreen<?>) {
                ScreenMouseEvents.allowMouseClick(screen).register(
                        (current, event) -> !key.matchesMouse(event) || !lookup(client));
            }
        });
    }

    public static boolean onKey(Minecraft client, int action, KeyEvent event) {
        if (instance == null || !instance.key.matches(event)) {
            return false;
        }
        if (action == GLFW.GLFW_PRESS) {
            return instance.lookup(client);
        }
        return action == GLFW.GLFW_REPEAT && client.gui.screen() instanceof AbstractContainerScreen<?>
                && instance.location.connection(client) != null;
    }

    private boolean lookup(Minecraft client) {
        var connection = location.connection(client);
        if (connection == null || client.player == null
                || !(client.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            return false;
        }
        Slot slot = ((HoveredSlotAccess) screen).bgm$hoveredSlot();
        if (slot == null || !slot.hasItem()) {
            return false;
        }
        Query query = query(slot.getItem());
        if (query == null || query.soulbound()) {
            return false;
        }
        var request = new Pending(screen, slot, query, connection);
        long now = System.currentTimeMillis();
        if (catalog != null && now < catalog.expiresAt()) {
            return openOrCheckAuctions(client, request, catalog);
        }
        if (!loading && now < retryAt) {
            return false;
        }
        pending = request;
        if (!loading) {
            loading = true;
            retryAt = now + RETRY_MS;
            Thread.startVirtualThread(() -> refresh(client));
        }
        return true;
    }

    private void refresh(Minecraft client) {
        Catalog loaded = null;
        try {
            loaded = parse(auctions.fetchApi("skyblock/bazaar"), auctions.fetchApi("resources/skyblock/items"),
                    System.currentTimeMillis() + CACHE_MS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOGGER.debug("Could not refresh market lookup catalog", failure);
        }
        Catalog result = loaded;
        client.execute(() -> {
            loading = false;
            Pending request = pending;
            pending = null;
            if (result != null) {
                catalog = result;
            }
            if (request == null || !stillHovering(client, request)) {
                return;
            }
            if (result == null) {
                client.player.sendOverlayMessage(Component.literal("Market data unavailable. Try again shortly."));
                return;
            }
            openOrCheckAuctions(client, request, result);
        });
    }

    private boolean stillHovering(Minecraft client, Pending request) {
        return client.player != null && location.connection(client) == request.connection()
                && client.gui.screen() == request.screen()
                && ((HoveredSlotAccess) request.screen()).bgm$hoveredSlot() == request.slot()
                && request.query().equals(query(request.slot().getItem()));
    }

    private boolean openOrCheckAuctions(Minecraft client, Pending request, Catalog data) {
        if (open(client, request, data)) {
            return true;
        }
        Query query = request.query();
        ItemInfo item = data.items().get(query.itemId());
        long now = System.currentTimeMillis();
        if (item == null || item.soulbound() || item.auctionBlocked() || query.auctionId().isEmpty()
                || data.bazaarProducts().contains(query.productId()) || !auctions.needsAuctionEvidence(now)) {
            return false;
        }
        pending = request;
        if (!loading) {
            loading = true;
            worker.execute(() -> {
                try {
                    auctions.refreshListings();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (IOException | RuntimeException failure) {
                    LOGGER.debug("Could not verify whether an item is auctionable", failure);
                }
                client.execute(() -> {
                    loading = false;
                    Pending latest = pending;
                    pending = null;
                    if (latest != null && stillHovering(client, latest)) {
                        open(client, latest, data);
                    }
                });
            });
        }
        return true;
    }

    private boolean open(Minecraft client, Pending request, Catalog data) {
        String command = route(data, request.query(), auctions::hasAuctionEvidence);
        if (command == null || !stillHovering(client, request)) {
            return false;
        }
        client.player.closeContainer();
        request.connection().sendCommand(command);
        return true;
    }

    static String route(Catalog data, Query query, Predicate<String> auctionEvidence) {
        ItemInfo item = data.items().get(query.itemId());
        if (query.soulbound() || item != null && item.soulbound()) {
            return null;
        }
        if (data.bazaarProducts().contains(query.productId())) {
            ItemInfo product = data.items().get(query.productId());
            String name = product == null ? query.hoverName() : product.name();
            return command("bz", query.productId().startsWith("ENCHANTMENT_") ? query.hoverName() : name);
        }
        if (item != null && item.auctionBlocked()) {
            return null;
        }
        if ((item != null && item.auctionExplicit()) || !query.auctionId().isEmpty() && auctionEvidence.test(query.auctionId())) {
            return command("ahsearch", item == null || !query.itemId().equals(query.auctionId()) ? query.hoverName() : item.name());
        }
        return null;
    }

    static Catalog parse(JsonObject bazaar, JsonObject catalog, long expiresAt) {
        Map<String, ItemInfo> items = new HashMap<>();
        for (var element : catalog.getAsJsonArray("items")) {
            JsonObject item = element.getAsJsonObject();
            var auction = item.get("can_auction");
            boolean hasAuction = auction != null && auction.isJsonPrimitive() && auction.getAsJsonPrimitive().isBoolean();
            var soulbound = item.get("soulbound");
            items.put(item.get("id").getAsString(), new ItemInfo(item.get("name").getAsString(),
                    hasAuction && auction.getAsBoolean(), hasAuction && !auction.getAsBoolean(),
                    soulbound != null && soulbound.isJsonPrimitive() && !soulbound.getAsString().equalsIgnoreCase("false")));
        }
        return new Catalog(Set.copyOf(bazaar.getAsJsonObject("products").keySet()), Map.copyOf(items), expiresAt);
    }

    private static Query query(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        var lore = stack.get(DataComponents.LORE);
        boolean bound = lore != null && lore.lines().stream().anyMatch(line -> {
            String label = line.getString().strip();
            return label.equalsIgnoreCase("Soulbound") || label.equalsIgnoreCase("Co-op Soulbound")
                    || label.equalsIgnoreCase("* Soulbound *") || label.equalsIgnoreCase("* Co-op Soulbound *");
        });
        var item = HypixelAuctions.itemOf(stack).orElse(null);
        if (item == null) {
            return vanillaQuery(stack, bound);
        }
        CompoundTag attributes = item.attributes();
        String id = attributes.getStringOr("id", "");
        String name = baseName(stack.getHoverName().getString());
        if (id.equals("ENCHANTED_BOOK")) {
            var enchantments = attributes.getCompoundOrEmpty("enchantments");
            if (enchantments.size() != 1) {
                return null;
            }
            String enchantment = enchantments.keySet().iterator().next();
            int level = enchantments.getIntOr(enchantment, 0);
            if (level < 1) {
                return null;
            }
            String product = "ENCHANTMENT_" + enchantment.toUpperCase(Locale.ROOT) + "_" + level;
            name = enchantmentSearch(enchantment, level, lore == null ? java.util.List.of()
                    : lore.lines().stream().map(Component::getString).toList());
            return new Query(id, product, name, bound, "");
        }
        String marketId = item.marketId();
        if (marketId.startsWith("PET:")) {
            name = marketId.substring(4).replace('_', ' ');
        }
        return new Query(id, id, name, bound, marketId);
    }

    private static Query vanillaQuery(ItemStack stack, boolean bound) {
        if (stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.LORE)) {
            return null;
        }
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!id.getNamespace().equals("minecraft")) {
            return null;
        }
        // Vanilla stacks only use Bazaar membership. Auction evidence needs a SkyBlock ID.
        return new Query("", id.getPath().toUpperCase(Locale.ROOT), stack.getHoverName().getString(), bound, "");
    }

    static String enchantmentSearch(String enchantment, int level, java.util.List<String> lore) {
        String key = enchantment.toLowerCase(Locale.ROOT);
        String words = key.equals("ultimate_wise") ? "ultimate wise" : key.replaceFirst("^ultimate_", "").replace('_', ' ');
        String name = java.util.Arrays.stream(words.split(" "))
                .filter(word -> !word.isEmpty())
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(java.util.stream.Collectors.joining(" "));
        String roman = switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
        String expected = name + " " + roman;
        return lore.stream().map(line -> line.replaceAll("§.", "").strip())
                .filter(line -> line.equalsIgnoreCase(expected)).findFirst().orElse(expected);
    }

    static String baseName(String name) {
        return name.replaceAll("§.", "").replaceFirst("(?i)^\\[lvl \\d+\\]\\s*", "")
                .replaceFirst("[\\s✪✦★☆✯➊➋➌➍➎]+$", "").strip();
    }

    private static String command(String prefix, String name) {
        String clean = name.replaceAll("§.", "").strip();
        return clean.isEmpty() || clean.length() > 128 || clean.codePoints().anyMatch(Character::isISOControl)
                ? null : prefix + " " + clean;
    }
}
