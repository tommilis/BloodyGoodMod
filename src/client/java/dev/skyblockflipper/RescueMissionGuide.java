package dev.skyblockflipper;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

final class RescueMissionGuide {
    private static final Logger LOGGER = LoggerFactory.getLogger(RescueMissionGuide.class);
    private static final Pattern RANK = Pattern.compile("(?i)([ⓈⒶⒷⒸⒹ]|[SABCD])\\s+Rescue Mission");
    private static final Pattern GUARD = Pattern.compile("(?i)\\b(?:mage |barbarian |cathedral |bastion )?guard\\b");
    private static final int ROUTE_COLOR = 0xFF67F1CB;
    private static final int GUARD_COLOR = 0xFFFFB269;
    private static final int LOOK_AHEAD = 3;
    private static final double GUARD_DISTANCE = 6.0;

    private record Faction(Vec3 agent, Map<String, List<Vec3>> routes) {}
    private record Display(List<Vec3> points, int next, int blocked, boolean atHostage) {}

    private final SkyBlockLocation location;
    private final BooleanSupplier enabled;
    private final Map<String, Faction> factions = loadRoutes();

    private ClientLevel level;
    private String rank;
    private boolean missionActive;
    private Faction faction;
    private List<Vec3> route = List.of();
    private int next;
    private volatile Display display;

    RescueMissionGuide(SkyBlockLocation location, BooleanSupplier enabled) {
        this.location = location;
        this.enabled = enabled;
    }

    void register() {
        ClientReceiveMessageEvents.GAME.register(this::onMessage);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> {
            var current = display;
            if (current == null) {
                return;
            }
            try (var collector = context.levelRenderer().collectPerFrameRenderThreadGizmos()) {
                drawRoute(current);
            }
        });
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("bloodygoodmod", "rescue_guide"), (graphics, delta) -> {
                    var current = display;
                    if (current == null) {
                        return;
                    }
                    String hint = current.blocked() >= 0 ? "Guard ahead. Wait for the path to clear."
                            : current.atHostage() ? "Hostage ahead. Talk to them."
                            : "Rescue Mission · Follow the marked route";
                    graphics.centeredText(Minecraft.getInstance().font, hint, graphics.guiWidth() / 2, 27,
                            current.blocked() >= 0 ? GUARD_COLOR : ROUTE_COLOR);
                });
    }

    private void tick(Minecraft client) {
        updateLevel(client);
        if (!enabled.getAsBoolean() || !location.isCrimsonIsle(client) || client.player == null) {
            rank = null;
            stop();
            return;
        }

        if (!missionActive && client.gui.screen() instanceof AbstractContainerScreen<?> menu
                && menu.getTitle().getString().strip().equalsIgnoreCase("Rescue")
                && menu.getMenu().slots.size() > 22) {
            var match = RANK.matcher(menu.getMenu().getSlot(22).getItem().getHoverName().getString());
            if (match.find()) {
                int symbol = Character.toUpperCase(match.group(1).charAt(0));
                rank = String.valueOf((char) (symbol >= 'Ⓐ' && symbol <= 'Ⓩ' ? 'A' + symbol - 'Ⓐ' : symbol));
            }
        }
        if (route.isEmpty()) {
            return;
        }

        Vec3 player = client.player.position();
        while (next < route.size()) {
            Vec3 target = route.get(next);
            if (Math.abs(player.y - target.y) > 2.0 || player.distanceToSqr(target) > 2.6 * 2.6) {
                break;
            }
            next++;
        }
        int blocked = next < route.size() ? guardedSegment(client, player) : -1;
        display = new Display(route, next, blocked, next == route.size());
    }

    private void onMessage(Component message, boolean overlay) {
        var client = Minecraft.getInstance();
        updateLevel(client);
        if (overlay || !enabled.getAsBoolean() || !location.isCrimsonIsle(client) || client.player == null) {
            return;
        }
        String line = message.getString().strip().toLowerCase(Locale.ROOT);
        if (line.startsWith("[npc] undercover agent:") && line.contains("what you need to do")) {
            stop();
            faction = factions.values().stream()
                    .filter(side -> side.agent().distanceToSqr(client.player.position()) <= 28 * 28)
                    .findFirst().orElse(null);
            missionActive = faction != null;
            if (missionActive) {
                if (rank == null) {
                    tell(client, "Open the Rescue quest at the Town Board first, or enter /bgm rescue <rank>.");
                } else {
                    tell(client, selectRoute(rank));
                }
            }
        } else if (missionActive && line.contains("guards captured you")) {
            next = 0;
            display = null;
        } else if (missionActive && (line.contains("failed your rescue mission")
                || line.contains("agents found someone else") || line.contains("too dangerous for you")
                || line.contains("rescue mission complete") || line.contains("rescued the hostage")
                || line.startsWith("[npc] hostage:"))) {
            rank = null;
            stop();
        }
    }

    String selectManualRoute(String requested) {
        if (!enabled.getAsBoolean()) {
            return "Enable Rescue mission under /bgm → Other first.";
        }
        if (!missionActive) {
            return "Talk to the Undercover Agent to start the rescue.";
        }
        return selectRoute(requested.toUpperCase(Locale.ROOT));
    }

    private String selectRoute(String requested) {
        String key = requested.equals("S") ? "S1" : requested;
        var points = faction.routes().get(key);
        if (points == null) {
            return key.startsWith("S") && !faction.routes().containsKey("S1")
                    ? "A Mage S-rank route isn't mapped yet." : "Available rescue ranks: " + String.join(", ", faction.routes().keySet());
        }
        route = points;
        next = 0;
        display = null;
        return "Rescue " + key + " route ready."
                + (requested.equals("S") ? " Use /bgm rescue s2 if your S-rank mission uses the other route." : "");
    }

    private int guardedSegment(Minecraft client, Vec3 player) {
        int end = Math.min(route.size(), next + LOOK_AHEAD);
        int blocked = end;
        for (var entity : client.level.entitiesForRendering()) {
            Vec3 guard = entity.position();
            if (guard.distanceToSqr(player) > 48 * 48 || !GUARD.matcher(entity.getName().getString()).find()
                    || !client.player.hasLineOfSight(entity)) {
                continue;
            }
            for (int i = next; i < blocked; i++) {
                Vec3 from = i == next ? player : route.get(i - 1);
                if (nearGuard(guard, from, route.get(i))) {
                    blocked = i;
                    break;
                }
            }
        }
        return blocked == end ? -1 : blocked;
    }

    static boolean nearGuard(Vec3 guard, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double horizontalLength = delta.x * delta.x + delta.z * delta.z;
        Vec3 closest;
        if (horizontalLength == 0) {
            closest = new Vec3(from.x, Math.clamp(guard.y, Math.min(from.y, to.y), Math.max(from.y, to.y)), from.z);
        } else {
            double fraction = Math.clamp(
                    ((guard.x - from.x) * delta.x + (guard.z - from.z) * delta.z) / horizontalLength, 0, 1);
            closest = from.add(delta.scale(fraction));
        }
        double x = guard.x - closest.x;
        double z = guard.z - closest.z;
        return Math.abs(guard.y - closest.y) <= 3 && x * x + z * z <= GUARD_DISTANCE * GUARD_DISTANCE;
    }

    private static void drawRoute(Display display) {
        var points = display.points();
        if (display.atHostage()) {
            marker(points.getLast(), ROUTE_COLOR);
            return;
        }
        int end = Math.min(points.size(), display.next() + LOOK_AHEAD);
        for (int i = display.next(); i < end; i++) {
            Vec3 point = points.get(i);
            if (i == display.blocked()) {
                marker(i == 0 ? point : points.get(i - 1), GUARD_COLOR);
                break;
            }
            if (i == display.next()) {
                marker(point, ROUTE_COLOR);
            }
            if (i > 0) {
                Gizmos.line(points.get(i - 1).add(0, 0.18, 0), point.add(0, 0.18, 0), ROUTE_COLOR, 3);
            }
        }
    }

    private static void marker(Vec3 point, int color) {
        Gizmos.cuboid(new AABB(point.x - 0.46, point.y - 0.05, point.z - 0.46,
                point.x + 0.46, point.y + 0.14, point.z + 0.46), GizmoStyle.stroke(color, 3));
    }

    private void updateLevel(Minecraft client) {
        if (client.level != level) {
            level = client.level;
            rank = null;
            stop();
        }
    }

    private void stop() {
        missionActive = false;
        faction = null;
        route = List.of();
        display = null;
    }

    private static void tell(Minecraft client, String message) {
        client.player.sendSystemMessage(Component.literal("[BloodyGoodMod] " + message));
    }

    private static Map<String, Faction> loadRoutes() {
        try (var input = RescueMissionGuide.class.getResourceAsStream("/assets/bloodygoodmod/rescue/routes.json")) {
            if (input == null) {
                throw new IOException("Missing bundled rescue routes");
            }
            var payload = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, Faction> result = new LinkedHashMap<>();
            for (var entry : payload.entrySet()) {
                var data = entry.getValue().getAsJsonObject();
                Map<String, List<Vec3>> routes = new LinkedHashMap<>();
                for (var rank : data.getAsJsonObject("ranks").entrySet()) {
                    var points = new ArrayList<Vec3>();
                    for (var point : rank.getValue().getAsJsonArray()) {
                        points.add(position(point.getAsJsonArray()));
                    }
                    routes.put(rank.getKey(), List.copyOf(points));
                }
                result.put(entry.getKey(), new Faction(position(data.getAsJsonArray("agent")), routes));
            }
            return result;
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not read rescue mission routes", failure);
            return Map.of();
        }
    }

    private static Vec3 position(JsonArray point) {
        return new Vec3(point.get(0).getAsDouble(), point.get(1).getAsDouble(), point.get(2).getAsDouble());
    }
}
