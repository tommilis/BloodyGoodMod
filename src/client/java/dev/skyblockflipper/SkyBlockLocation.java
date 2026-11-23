package dev.skyblockflipper;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.hypixel.data.type.GameType;
import net.hypixel.modapi.HypixelModAPI;
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

final class SkyBlockLocation {
    private ClientPacketListener connection;
    private String area;

    void register() {
        var api = HypixelModAPI.getInstance();
        api.subscribeToEventPacket(ClientboundLocationPacket.class);
        api.createHandler(ClientboundLocationPacket.class, packet -> {
            Minecraft client = Minecraft.getInstance();
            ClientPacketListener receivedOn = client.getConnection();
            client.execute(() -> {
                if (receivedOn == null || client.getConnection() != receivedOn) {
                    return;
                }
                connection = packet.getServerType().orElse(null) == GameType.SKYBLOCK
                        && packet.getLobbyName().isEmpty() ? receivedOn : null;
                area = packet.getMap().or(() -> packet.getMode()).orElse("SkyBlock");
            });
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (connection == handler) {
                connection = null;
                area = null;
            }
        });
    }

    ClientPacketListener connection(Minecraft client) {
        return connection == client.getConnection() ? connection : null;
    }

    String description(Minecraft client) {
        return connection(client) == null ? "Waiting for SkyBlock location" : area;
    }
}
