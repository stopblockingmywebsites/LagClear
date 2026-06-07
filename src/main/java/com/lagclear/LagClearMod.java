package com.lagclear;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.lagclear.config.LagClearConfig;

import static net.minecraft.commands.Commands.literal;
import static net.minecraft.commands.Commands.argument;

public class LagClearMod implements ModInitializer {
	public static final String MOD_ID = "lagclearmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	
	private static int tickCounter = 0;
	private static long lastWarningTick1Min = 0;  // 1 minute warning
	private static long lastWarningTick30Sec = 0; // 30 second warning
	private static boolean actionbarShown = false; // Was ActionBar shown?

	@Override
	public void onInitialize() {
		// Load config file
		LagClearConfig.loadConfig();
		
		if (!LagClearConfig.isEnabled()) {
			LOGGER.info("[Lag Clear Mod] Disabled. To enable: /lagclear config enable");
			return;
		}
		
		LOGGER.info("[Lag Clear Mod] Starting...");
		
		// Listen to server tick event
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!LagClearConfig.isEnabled()) {
				return;
			}
			
			tickCounter++;
			int interval = LagClearConfig.getCleanupIntervalTicks();
			int remainingTicks = interval - tickCounter;
			
			// Warn when 1 minute remains (1200 ticks = 60 seconds)
			if (remainingTicks == 1200 && lastWarningTick1Min != tickCounter) {
				broadcastMessage(server, "§e[Lag Clear] Dropped items will be cleared in 1 MINUTE!");
				lastWarningTick1Min = tickCounter;
			}
			
			// Warn when 30 seconds remain (600 ticks = 30 seconds)
			if (remainingTicks == 600 && lastWarningTick30Sec != tickCounter) {
				broadcastMessage(server, "§6[Lag Clear] Dropped items will be cleared in 30 SECONDS!");
				lastWarningTick30Sec = tickCounter;
			}
			
			// Countdown in ActionBar during the last 10 seconds (200 ticks = 10 seconds)
			if (remainingTicks > 0 && remainingTicks <= 200) {
				int secondsRemaining = (remainingTicks + 19) / 20; // Round up
				for (var player : server.getPlayerList().getPlayers()) {
					player.displayClientMessage(
						net.minecraft.network.chat.Component.literal("§c⚠ Cleanup: " + secondsRemaining + " seconds"),
						true // ActionBar
					);
				}
			}
			
			// Cleanup time
			if (tickCounter >= interval) {
				int cleared = clearDroppedItems(server);
				broadcastMessage(server, "§a[Lag Clear] Dropped items have been cleared! (" + cleared + " items)");
				LOGGER.info("[Lag Clear Mod] Automatic cleanup: {} items removed, next cleanup in {} minutes", 
					cleared, LagClearConfig.getCleanupIntervalMinutes());
				tickCounter = 0;
				lastWarningTick1Min = 0;
				lastWarningTick30Sec = 0;
				actionbarShown = false;
			}
		});
		
		// Add /lagclear command
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			dispatcher.register(literal("lagclear")
				.then(literal("config")
					.then(literal("enable")
						.executes(context -> {
							LagClearConfig.setEnabled(true);
							context.getSource().sendSuccess(
								() -> net.minecraft.network.chat.Component.literal("§a[Lag Clear] Mod enabled!"),
								true
							);
							return 1;
						})
					)
					.then(literal("disable")
						.executes(context -> {
							LagClearConfig.setEnabled(false);
							context.getSource().sendSuccess(
								() -> net.minecraft.network.chat.Component.literal("§a[Lag Clear] Mod disabled!"),
								true
							);
							return 1;
						})
					)
					.then(literal("interval")
						.then(argument("minutes", IntegerArgumentType.integer(1, 1440))
							.executes(context -> {
								int minutes = IntegerArgumentType.getInteger(context, "minutes");
								LagClearConfig.setCleanupIntervalMinutes(minutes);
								context.getSource().sendSuccess(
									() -> net.minecraft.network.chat.Component.literal(
										"§a[Lag Clear] Cleanup interval set to " + minutes + " minutes!"
									),
									true
								);
								return 1;
							})
						)
					)
					.then(literal("status")
						.executes(context -> {
							String status = LagClearConfig.isEnabled() ? "§aEnabled" : "§cDisabled";
							String interval = String.valueOf(LagClearConfig.getCleanupIntervalMinutes());
							context.getSource().sendSuccess(
								() -> net.minecraft.network.chat.Component.literal(
									"§e[Lag Clear] Status: " + status + " §e| Interval: " + interval + " minutes"
								),
								true
							);
							return 1;
						})
					)
				)
				.executes(context -> {
					if (!LagClearConfig.isEnabled()) {
						context.getSource().sendFailure(
							net.minecraft.network.chat.Component.literal("§c[Lag Clear] Mod is disabled!")
						);
						return 0;
					}
					MinecraftServer server = context.getSource().getServer();
					int cleared = clearDroppedItems(server);
					context.getSource().sendSuccess(
						() -> net.minecraft.network.chat.Component.literal(
							"§a[Lag Clear] " + cleared + " dropped items cleared!"
						),
						true
					);
					return 1;
				})
			);
		});
		
		LOGGER.info("[Lag Clear Mod] Successfully loaded! Cleanup interval: {} minutes", LagClearConfig.getCleanupIntervalMinutes());
		LOGGER.info("[Lag Clear Mod] Commands: /lagclear (manual cleanup) | /lagclear config (settings)");
	}
	
	private static void broadcastMessage(MinecraftServer server, String message) {
		for (var player : server.getPlayerList().getPlayers()) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
		}
		LOGGER.info(message);
	}
	
	private static int clearDroppedItems(MinecraftServer server) {
		int totalCleared = 0;
		
		// Check all worlds
		for (ServerLevel level : server.getAllLevels()) {
			// Create a very large AABB (like world boundaries)
			AABB searchBox = new AABB(-30000000, -64, -30000000, 30000000, 320, 30000000);
			
			// Find and remove all ItemEntities
			for (ItemEntity itemEntity : level.getEntitiesOfClass(ItemEntity.class, searchBox)) {
				itemEntity.discard();
				totalCleared++;
			}
		}
		
		if (totalCleared > 0) {
			LOGGER.info("[Lag Clear Mod] {} dropped items cleared!", totalCleared);
		}
		
		return totalCleared;
	}
}
