package dev.skyblockflipper;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class SaleHistory {
    private static final long READ_LIMIT_BYTES = 128L * 1024 * 1024;

    private final Path path;

    SaleHistory(Path path) {
        this.path = path;
    }

    List<FlipMarket.Sale<HypixelAuctions.Item>> load() throws IOException {
        if (!Files.exists(path)) {
            return List.of();
        }
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(READ_LIMIT_BYTES));
        var sales = new ArrayList<FlipMarket.Sale<HypixelAuctions.Item>>();
        for (var tag : root.getListOrEmpty("sales")) {
            if (!(tag instanceof CompoundTag row)) {
                continue;
            }
            String id = row.getStringOr("auction", "");
            long price = row.getLongOr("price", 0);
            long time = row.getLongOr("sold_at", 0);
            int count = row.getIntOr("count", 0);
            CompoundTag attributes = row.getCompoundOrEmpty("attributes");
            if (!id.isBlank() && price > 0 && time > 0 && count > 0 && !attributes.isEmpty()) {
                sales.add(new FlipMarket.Sale<>(id, new HypixelAuctions.Item(count, attributes), price, time));
            }
        }
        return sales;
    }

}
