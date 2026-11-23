package dev.skyblockflipper;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class FlipperClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("skyblock-flipper");
    private static final int DISPLAY_LIMIT = 5;
    private static final long SHORTCUT_AGE_MS = Duration.ofMinutes(3).toMillis();

    private final AuctionDatabase database = new AuctionDatabase(FabricLoader.getInstance().getConfigDir());
    private final HypixelAuctions auctions = new HypixelAuctions(database);
    private final SkyBlockLocation location = new SkyBlockLocation();
    private final Path settingsPath = FabricLoader.getInstance().getConfigDir()
            .resolve("skyblock-flipper.properties");
    private final AtomicBoolean commandBusy = new AtomicBoolean();
    private final AtomicBoolean backgroundBusy = new AtomicBoolean();
    private final Executor worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "BloodyGoodMod");
        thread.setDaemon(true);
        return thread;
    });

    private final AuctionPriceOverlay auctionPriceOverlay = new AuctionPriceOverlay(auctions);

    // Settings, notices, and connection state are owned by the Minecraft client thread.
    private FlipSettings settings = FlipSettings.DEFAULT;
    private KeyMapping auctionKey;
    private boolean auctionKeyHeld;
    private volatile ClientPacketListener activeConnection;
    private long nextScanAt;
    private boolean settingsRequested;
    private boolean historyRequested;
    private final Map<String, Long> alertedUntil = new HashMap<>();
    private FlipMarket.Candidate latestAuction;
    private long latestAuctionUntil;
    private long notificationUntil;
    private String notificationProfit = "";

    @Override
    public void onInitializeClient() {
        try {
            settings = FlipSettings.load(settingsPath);
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not read BloodyGoodMod settings", failure);
        }

        CursorResetSetting.enabled = settings.noCursorReset();
        TooltipScrolling.enabled = settings.scrollableTooltips();
        location.register();

        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skyblock_flipper", "keys"));
        auctionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.skyblock_flipper.latest",
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("bloodygoodmod", "flip_alert"), (graphics, delta) -> {
                    long remaining = notificationUntil - System.currentTimeMillis();
                    if (latestAuction != null && remaining > 0
                            && location.connection(Minecraft.getInstance()) != null) {
                        FlipAlertHud.draw(graphics, Minecraft.getInstance().font, settings.alertPosition(),
                                latestAuction.name(), notificationProfit, remaining);
                    }
                });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (AuctionPriceOverlay.isAuctionSetup(screen)) {
                ScreenEvents.afterExtract(screen).register((current, graphics, mouseX, mouseY, delta) ->
                        {
                            if (location.connection(client) != null) {
                                auctionPriceOverlay.draw(client, current, graphics);
                            }
                        });
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(literal("bgm")
                        .executes(command -> {
                            settingsRequested = true;
                            return 1;
                        })
                        .then(literal("history").executes(command -> {
                            historyRequested = true;
                            return 1;
                        }))
                        .then(literal("status").executes(command -> showStatus(command.getSource())))
                        .then(literal("settings").executes(command -> {
                            settingsRequested = true;
                            return 1;
                        }))
                        .then(literal("scan")
                                .executes(command -> scan(command.getSource(), settings))
                                .then(argument("budget", LongArgumentType.longArg(1))
                                        .executes(command -> scanWithBudget(command.getSource(),
                                                LongArgumentType.getLong(command, "budget"), settings.minProfit()))
                                        .then(argument("minProfit", LongArgumentType.longArg(1))
                                                .executes(command -> scanWithBudget(command.getSource(),
                                                        LongArgumentType.getLong(command, "budget"),
                                                        LongArgumentType.getLong(command, "minProfit"))))))
                        .then(literal("sample")
                                .executes(command -> submit(command.getSource(), "Checking recorded BIN sales…", () -> {
                                    var sample = auctions.sample();
                                    return source -> tell(source::sendFeedback, "+" + sample.added()
                                            + " new sales, " + sample.saleCount() + " recorded sales.");
                                })))));
    }

    private void openSettings(Minecraft client) {
        client.gui.setScreen(new FlipSettingsScreen(settings, KeyMappingHelper.getBoundKeyOf(auctionKey),
                (updated, key) -> {
                    updated.save(settingsPath);
                    settings = updated;
                    CursorResetSetting.enabled = updated.noCursorReset();
                    TooltipScrolling.enabled = updated.scrollableTooltips();
                    nextScanAt = 0;
                    if (!key.equals(KeyMappingHelper.getBoundKeyOf(auctionKey))) {
                        auctionKey.setKey(key);
                        KeyMapping.resetMapping();
                        client.options.save();
                    }
                }, parent -> openHistory(client, parent)));
    }

    private void openHistory(Minecraft client, Screen parent) {
        var history = new FlipHistoryScreen(parent);
        client.gui.setScreen(history);
        worker.execute(() -> {
            try {
                var rows = database.recentFlips(System.currentTimeMillis());
                client.execute(() -> history.loaded(rows, "No flips recorded yet."));
            } catch (IOException failure) {
                LOGGER.warn("Could not load flip history", failure);
                client.execute(() -> history.loaded(List.of(), failure.getMessage()));
            }
        });
    }

    private void recordFlips(List<FlipMarket.Candidate> flips, long now) {
        if (flips.isEmpty()) {
            return;
        }
        worker.execute(() -> {
            try {
                database.recordFlips(flips, now);
            } catch (IOException failure) {
                LOGGER.warn("Could not record flip history", failure);
            }
        });
    }

    private void tick(Minecraft client) {
        if (settingsRequested) {
            settingsRequested = false;
            openSettings(client);
        }
        var connection = location.connection(client);
        if (historyRequested) {
            historyRequested = false;
            openHistory(client, null);
        }
        if (connection != activeConnection) {
            activeConnection = connection;
            latestAuction = null;
            latestAuctionUntil = 0;
            notificationUntil = 0;
            alertedUntil.clear();
            nextScanAt = 0;
        }

        boolean openAuction = auctionKey.consumeClick() && !auctionKeyHeld;
        while (auctionKey.consumeClick()) {
            // Drain repeated clicks so holding the key cannot submit multiple commands.
        }
        auctionKeyHeld = auctionKey.isDown();
        long now = System.currentTimeMillis();
        auctionPriceOverlay.tick(client, connection != null && client.player != null, now);
        if (connection == null || client.player == null) {
            return;
        }
        if (openAuction && client.gui.screen() == null) {
            if (latestAuction != null && now < latestAuctionUntil
                    && auctions.canOpenAuction(latestAuction.id(), now)) {
                connection.sendCommand(auctionCommand(latestAuction));
            } else {
                tell(client.player::sendSystemMessage, "No recent auction is still available in the latest snapshot.");
            }
        }

        boolean autoAlerts = settings.automatic();
        boolean needsListings = autoAlerts || auctionPriceOverlay.hasSelectedItem();
        if (!needsListings) {
            nextScanAt = 0;
        }
        boolean scanDue = needsListings && now >= nextScanAt && auctions.listingRefreshDue(now);
        if (commandBusy.get() || (!scanDue && !auctions.saleSampleDue(now))
                || !backgroundBusy.compareAndSet(false, true)) {
            return;
        }

        FlipSettings scanSettings = settings;
        if (scanDue) {
            nextScanAt = now + scanSettings.seconds() * 1000L;
        }
        worker.execute(() -> {
            try {
                if (activeConnection != connection) {
                    return;
                }
                if (scanDue && autoAlerts) {
                    var results = auctions.scan(scanSettings);
                    client.execute(() -> {
                        if (location.connection(client) == connection && activeConnection == connection
                                && client.player != null && settings == scanSettings) {
                            alert(client.player::sendSystemMessage, results);
                        }
                    });
                } else if (scanDue) {
                    auctions.refreshListings();
                } else {
                    auctions.sample();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException failure) {
                LOGGER.debug("Background auction request failed", failure);
            } finally {
                backgroundBusy.set(false);
            }
        });
    }

    private int showStatus(FabricClientCommandSource source) {
        var status = auctions.status();
        long now = System.currentTimeMillis();
        tell(source::sendFeedback, location.description(Minecraft.getInstance()) + " · Listing snapshot " + age(status.snapshotTime(), now)
                + " · Last sales check " + age(status.salesCheckedAt(), now)
                + ((commandBusy.get() || backgroundBusy.get()) ? " · Updating" : ""));
        if (now < status.backoffUntil()) {
            tell(source::sendFeedback, "Auction API rate limit · Retry in "
                    + HypixelAuctions.secondsUntil(status.backoffUntil(), now) + "s.");
        }
        if (!status.listingError().isEmpty()) {
            tell(source::sendFeedback, "Listings: " + status.listingError());
        }
        if (!status.salesError().isEmpty()) {
            tell(source::sendFeedback, "Sales: " + status.salesError());
        }
        return 1;
    }

    private static String age(long time, long now) {
        return time == 0 ? "unavailable" : Math.max(0, (now - time) / 1_000) + "s ago";
    }

    private int scanWithBudget(FabricClientCommandSource source, long budget, long minProfit) {
        if (budget < settings.minPrice()) {
            tell(source::sendFeedback, "The budget is below your minimum buy price.");
            return 0;
        }
        return scan(source, new FlipSettings(settings.automatic(), settings.seconds(),
                settings.minPrice(), budget, minProfit, settings.alertPosition(), settings.noCursorReset(), settings.scrollableTooltips()));
    }

    private int scan(FabricClientCommandSource source, FlipSettings scanSettings) {
        return submit(source, "Checking Auction House prices…", () -> {
            var results = auctions.scan(scanSettings);
            return recipient -> showResults(recipient::sendFeedback, results);
        });
    }

    private int submit(FabricClientCommandSource source, String status, AuctionTask task) {
        Minecraft client = Minecraft.getInstance();
        var connection = location.connection(client);
        if (connection == null) {
            tell(source::sendFeedback, "Join Hypixel SkyBlock to use this command.");
            return 0;
        }
        if (!commandBusy.compareAndSet(false, true)) {
            tell(source::sendFeedback, "An auction request is already running.");
            return 0;
        }

        tell(source::sendFeedback, status);
        worker.execute(() -> {
            Consumer<FabricClientCommandSource> feedback;
            try {
                feedback = task.run();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                feedback = recipient -> tell(recipient::sendFeedback, "The auction request was interrupted.");
            } catch (IOException failure) {
                feedback = recipient -> tell(recipient::sendFeedback, failure.getMessage());
            } catch (RuntimeException failure) {
                LOGGER.warn("Could not read Hypixel auction data", failure);
                feedback = recipient -> tell(recipient::sendFeedback, "Hypixel returned unexpected auction data.");
            } finally {
                commandBusy.set(false);
            }
            var message = feedback;
            client.execute(() -> {
                if (location.connection(client) == connection) {
                    message.accept(source);
                }
            });
        });
        return 1;
    }

    private void alert(Consumer<Component> feedback, HypixelAuctions.Scan results) {
        long now = System.currentTimeMillis();
        alertedUntil.values().removeIf(endsAt -> endsAt <= now);
        var fresh = results.candidates().stream()
                .filter(candidate -> !alertedUntil.containsKey(candidate.id())
                        && auctions.canOpenAuction(candidate.id(), now))
                .limit(DISPLAY_LIMIT)
                .toList();
        for (var flip : fresh.reversed()) {
            alertedUntil.put(flip.id(), flip.endsAt());
            showFlip(feedback, flip);
        }
        recordFlips(fresh, now);
        if (!fresh.isEmpty()) {
            rememberAuction(fresh.getFirst(), now);
        }
    }

    private void showResults(Consumer<Component> feedback, HypixelAuctions.Scan results) {
        String elapsed = String.format(Locale.US, "%.1fs", results.elapsedMillis() / 1_000.0);
        if (results.pricedItems() == 0) {
            tell(feedback, "Scan took " + elapsed + ". No variants have enough recent sales yet ("
                    + results.saleCount() + " recorded sales).");
            return;
        }

        long now = System.currentTimeMillis();
        long age = Math.max(0, (now - results.snapshotTime()) / 1_000);
        var available = results.candidates().stream().filter(flip -> auctions.canOpenAuction(flip.id(), now)).toList();
        int count = available.size();
        tell(feedback, "Showing " + Math.min(DISPLAY_LIMIT, count) + " of " + count
                + " estimated flips from " + results.pricedItems() + " priced variants. Scan took "
                + elapsed + " · Listing snapshot is " + age + "s old.");
        var displayed = available.stream().limit(DISPLAY_LIMIT).toList();
        displayed.forEach(flip -> {
            alertedUntil.put(flip.id(), flip.endsAt());
            showFlip(feedback, flip);
        });
        recordFlips(displayed, now);
        if (!available.isEmpty()) {
            rememberAuction(available.getFirst(), now);
        }
    }

    private void rememberAuction(FlipMarket.Candidate flip, long now) {
        latestAuction = flip;
        latestAuctionUntil = Math.min(flip.endsAt(), now + SHORTCUT_AGE_MS);
        notificationUntil = now + FlipAlertHud.DURATION_MS;
        notificationProfit = "+" + compactCoins(flip.profit());
    }

    private static String auctionCommand(FlipMarket.Candidate flip) {
        return "viewauction " + flip.id();
    }

    private static void showFlip(Consumer<Component> feedback, FlipMarket.Candidate flip) {
        long returnPercent = Math.round(100.0 * flip.profit() / flip.buy());
        var details = Component.literal("Open auction")
                .append(Component.literal("\nBuy " + coins(flip.buy()) + "  ·  Estimated resale " + coins(flip.resale())))
                .append(Component.literal("\nEstimated after standard fees " + coins(flip.profit()) + "  ·  "
                        + flip.sales() + " comparable sales"));

        var line = Component.literal("FLIP [OPEN] ")
                .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                        .withClickEvent(new ClickEvent.RunCommand("/" + auctionCommand(flip)))
                        .withHoverEvent(new HoverEvent.ShowText(details)))
                .append(Component.literal("+" + compactCoins(flip.profit()) + " ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(returnPercent + "% ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(flip.name()).withStyle(ChatFormatting.WHITE));
        feedback.accept(line);
    }

    private static String compactCoins(long amount) {
        var format = NumberFormat.getCompactNumberInstance(Locale.US, NumberFormat.Style.SHORT);
        format.setMaximumFractionDigits(1);
        return format.format(amount).toLowerCase(Locale.ROOT);
    }

    private static String coins(long amount) {
        return NumberFormat.getIntegerInstance(Locale.US).format(amount);
    }

    private static void tell(Consumer<Component> feedback, String message) {
        feedback.accept(Component.literal("[BloodyGoodMod] " + message));
    }

    @FunctionalInterface
    private interface AuctionTask {
        Consumer<FabricClientCommandSource> run() throws IOException, InterruptedException;
    }
}
