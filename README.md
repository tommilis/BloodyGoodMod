# BloodyGoodMod

A client-side Fabric mod for Minecraft Java Edition 26.2. It uses Hypixel's public Auction House API to compare Buy It Now listings with recent sales. BloodyGoodMod is an independent project.

## Build and install

1. Install JDK 25 and Gradle 9.6.0, then open a terminal in this folder.
2. Run `gradle build`. The mod JAR will be at `build/libs/bloodygoodmod-0.2.7.jar`.
3. Set up a Minecraft 26.2 Fabric profile with Fabric Loader 0.20.1 or later and Fabric API 0.155.2+26.2. Put Fabric API, [Hypixel Mod API](https://modrinth.com/mod/hypixel-mod-api) 1.0.3 or later, and the built mod JAR in that profile's `mods` folder.
4. Join Hypixel SkyBlock and run `/bgm`.

## Automatic alerts and settings

The mod checks for flips at your chosen interval while the Hypixel Mod API reports you are in SkyBlock. The default is 60 seconds. It shows up to five new matching listings per scan in chat, ordered by estimated profit, and skips listings it has already shown on that connection.

Open `/bgm` to turn automatic alerts on or off, set the scan interval (10–3600 seconds), set the minimum and maximum buy price and minimum estimated profit, and bind a key for the latest result. Leave maximum price blank for no cap. Use the Alert position button to cycle through six placements and preview them. Click Save to apply the position. Sales sampling runs about once a minute while connected, including when alerts are off.

Open chat and click a flip to open that listing directly, or press your configured key while playing to open the most recent flip if it still appears available in the latest usable snapshot. The key starts unbound. Each input submits a single `/viewauction` command. You still choose whether to buy. The alert shows estimated profit and return on the buy price. Hover for buy price, estimated resale, and comparable sales. Hypixel's [Allowed Modifications guide](https://support.hypixel.net/hc/en-us/articles/6472550754962-Hypixel-Allowed-Modifications) does not expressly approve auction flip alerts or a direct auction hotkey.

Each new batch of flips also shows a five-second HUD notification for the top result. The HUD leaves movement and controls available, and all reported flips still appear in chat.

## Search a hovered item

Under **Options → Controls → Key Binds → SkyBlock Flipper**, bind **Search hovered item market**. Hover an item in your inventory, a backpack, or a storage menu, then press the key. The shortcut opens the Bazaar for a known product or searches the Auction House when the item catalog or observed auctions confirm it can trade there. A soulbound copy can look up the same market as other copies of that item. NPC-only and unverified items leave the menu open. Standard Hypixel access requirements apply.

## Listing an item

Open **Create BIN Auction** or **Create Auction**, then select the item. The overlay shows the lowest available BIN for the same item variant and stack size in the latest usable snapshot, even if there is no sales history. Selecting an item also refreshes the listing snapshot when needed, including with automatic alerts off. **Suggested price** includes a comparison with a recent active BIN. If that comparison is missing, **Recent sale estimate** shows the historical estimate on its own. If there are too few recent sales, the overlay explains why a price is unavailable.

## No cursor reset

Open `/bgm` and choose **Other → No cursor reset**, then click **Save**. The toggle is off by default. It keeps the pointer in place when moving directly between inventory-style menus, including transitions that briefly close the first menu for up to half a second. Opening a menu after ordinary gameplay still uses Minecraft's usual cursor position.

The behavior was informed by [Better Screens](https://modrinth.com/project/better-screens) and [Firmament's cursor handling](https://git.nea.moe/firmament.git/tree/src/main/java/moe/nea/firmament/mixins/MixinMouse.java?h=1.14.0&id=4d93f475aadc42c4bf83c3a0749af41659235c71).

## Scrollable tooltips and flip history

Open `/bgm`, choose **Other → Scrollable tooltips**, and click **Save**. Hover an item with a tooltip taller than the available screen space and use the wheel to read its remaining lines. This also covers tooltips that other mods reposition through Minecraft's standard tooltip renderer.

The **History** button opens a separate screen with up to 100 recently detected opportunities, including the buy price, estimated resale, estimated profit, and time detected. The mod retains these entries locally for 30 days.

## Rescue Mission guide

In `/bgm` → **Other**, enable **Rescue mission** and click **Save**. Open the Rescue quest at the Crimson Isle Town Board so the mod can read its rank, then talk to the Undercover Agent. Follow the teal route. If a visible guard gets close to an upcoming segment, the guide marks the stopping point in amber and waits for the way to clear. The alert depends on visible, named guards and is not a guarantee that a segment is clear.

Use `/bgm rescue <rank>` after talking to the Agent to choose a route manually. Mage supports A–D and Barbarian supports A–D plus S1 and S2. If your Barbarian S-route starts in the wrong direction, use `/bgm rescue s2`.

Route data and approaches are derived from [SkyHanni-REPO](https://github.com/SkyHanniStudios/SkyHanni-REPO), with the upstream known guard zones excluded from the Cathedral approaches. The MIT notice for the data is bundled with the mod.

## Commands

| Command | Result |
| --- | --- |
| `/bgm` | Open settings. |
| `/bgm history` | Open the latest detected flip opportunities. |
| `/bgm status` | Show the SkyBlock area, listing snapshot age, last successful sales check, any recent API errors, and the rate-limit countdown when applicable. |
| `/bgm scan` | Collect a sales sample, then show up to five BIN flips ranked by estimated profit and pricing evidence. |
| `/bgm scan 5000000` | Limit purchase prices to five million coins. |
| `/bgm scan 5000000 250000` | Limit purchase prices to five million coins and require at least 250,000 coins in estimated profit. |
| `/bgm sample` | Show how many sales are recorded and request a new batch if the last sample was at least a minute ago. |

## How prices are estimated

- While connected to Hypixel, the mod samples completed BIN sales about once a minute. It keeps every observed sale it records in `config/bloodygoodmod-auctions.sqlite` and imports eligible records from the previous `config/skyblock-flipper-sales.nbt` on first use. SQLite supplies recent comparable sales for pricing. A new install starts with the sales it can observe after connecting. The number shown in each result counts observed comparable sales, so it is not a measure of total market volume.
- Sales-based prices need at least three observed sales in the past 24 hours. Pricing uses a lower quartile of up to 64 sales, with newer sales weighted more heavily. Plain items can use per-item pricing across stack sizes. A flip alert for a stack using this fallback requires five observed sales of stacks at least as large in the past 24 hours. Pricing preserves upgrades and unknown attributes. Pet comparisons ignore the pet UUID and the active and tooltip visibility flags.
- Estimated resale is capped just below the lowest equivalent competing BIN in a snapshot at most two minutes old. Estimated profit subtracts the purchase cost, listing fee and standard collection tax. Duration surcharges and special event modifiers are excluded.
- In **Filters**, set minimum ROI and observed sales. Flip alerts require completed sales and a buy price at least 10% below the next available BIN. The resale estimate is capped at 99% of that BIN and at the verified replacement cost where available. Hover an alert for its comparable sales, competing BINs, and replacement cost when used. Fragged Bonzo’s Staff requires a comparable base staff and a fresh Bazaar quote for eight fragments.
- The client reuses sales and active listing data between requests. The scanner downloads additional listing pages only when Hypixel publishes a new snapshot and reuses decoded item data for listing IDs it has seen before. A complete scan can run longer than the configured interval. Manual scans report the time taken and the age of the listing snapshot. If a listing page fails or the snapshot changes mid-scan, the mod cancels the remaining page requests and keeps the last complete snapshot. Listings may already have sold by the time you open them in-game.

[Hypixel Public API](https://api.hypixel.net/#tag/SkyBlock) · [Auction House fees](https://hypixelskyblock.minecraft.wiki/w/Auction_House#Fees_and_Taxes) · [Fabric for Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html)

The minimum-profit filter and use of comparable sale counts were informed by [SkyCofl’s documented filters](https://sky.coflnet.com/wiki/flipper).
