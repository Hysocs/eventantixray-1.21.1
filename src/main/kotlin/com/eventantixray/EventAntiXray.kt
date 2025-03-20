package com.eventantixray

import com.eventantixray.utils.DatabaseManager
import com.eventantixray.utils.EventAntiXrayCommands
import com.eventantixray.utils.EventAntiXrayConfig
import com.eventantixray.utils.EventAntiXrayWebhook
import com.everlastingutils.command.CommandManager
import com.everlastingutils.colors.KyoriHelper
import com.everlastingutils.utils.LogDebug
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.item.BlockItem
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.registry.Registries
import net.minecraft.util.ActionResult
import net.minecraft.util.Identifier
import net.minecraft.sound.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.server.world.ServerWorld
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class EventAntiXray : ModInitializer {
	private val logger = LoggerFactory.getLogger("EventAntiXray")
	private val MOD_ID = "eventantixray"

	private data class BlockBreakEvent(val timestamp: Instant, val position: BlockPos)

	private data class BlockBreakData(
		val queue: ArrayDeque<BlockBreakEvent> = ArrayDeque(),
		var continuousTrackingEnabled: Boolean = false,
		var lastAlertTime: Instant = Instant.now(),
		var lastAlertCount: Int = 0,
		var consecutiveAlertCount: Int = 0
	)

	private data class AlertData(
		val count: Int,
		val timeWindow: Duration,
		val consecutiveAlertCount: Int,
		val recentPos: BlockPos
	)

	private val alertCache = mutableMapOf<String, Pair<Int, Int>>()
	private val playerBlockBreaks = ConcurrentHashMap<UUID, MutableMap<Identifier, BlockBreakData>>()
	private val permissionCache = ConcurrentHashMap<UUID, Boolean>()
	private val webhookExecutor = Executors.newFixedThreadPool(2)
	private val commandManager = CommandManager("eventantixray")

	override fun onInitialize() {
		logger.info("EventAntiXray initializing...")
		LogDebug.debug("EventAntiXray initializing...", MOD_ID)

		val configDir = FabricLoader.getInstance().configDir.resolve("eventantixray").toFile()
		EventAntiXrayConfig.init(configDir)

		EventAntiXrayCommands.registerCommands(commandManager)
		commandManager.register()

		registerEvents()
		scheduleCleanupTask()

		logger.info("EventAntiXray initialized")
		LogDebug.debug("EventAntiXray initialization complete", MOD_ID)
	}

	private fun registerEvents() {
		LogDebug.debug("Registering event handlers", MOD_ID)

		ServerLifecycleEvents.SERVER_STARTING.register { server ->
			LogDebug.debug("Server starting, initializing database and reloading configuration", MOD_ID)
			DatabaseManager.init()
			EventAntiXrayConfig.reloadBlocking()
		}

		ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
			LogDebug.debug("Server stopping, closing database", MOD_ID)
			DatabaseManager.close()
			DatabaseManager.executor.shutdown()
			try {
				if (!DatabaseManager.executor.awaitTermination(10, TimeUnit.SECONDS)) {
					DatabaseManager.executor.shutdownNow()
					logger.warn("Forcing shutdown of database executor")
				}
			} catch (e: InterruptedException) {
				DatabaseManager.executor.shutdownNow()
				logger.warn("Database executor shutdown interrupted: ${e.message}")
			}
		}

		UseBlockCallback.EVENT.register { player, world, hand, hitResult ->
			if (!world.isClient && player is ServerPlayerEntity) {
				val stack = player.getStackInHand(hand)
				if (stack.item is BlockItem) {
					val block = (stack.item as BlockItem).block
					val blockId = Registries.BLOCK.getId(block)
					if (EventAntiXrayConfig.getTrackedBlock(blockId) != null) {
						val pos = hitResult.blockPos.offset(hitResult.side)
						val worldKey = world.registryKey.value.toString()
						LogDebug.debug("Adding placed block to cache: $worldKey, (${pos.x}, ${pos.y}, ${pos.z}), $blockId", MOD_ID)
						DatabaseManager.addPlacedBlock(worldKey, pos.x, pos.y, pos.z, blockId.toString())
					}
				}
			}
			ActionResult.PASS
		}

		PlayerBlockBreakEvents.AFTER.register { world, player, pos, state, _ ->
			if (!world.isClient && player is ServerPlayerEntity) {
				val server = player.server
				DatabaseManager.executor.submit {
					processBlockBreakAsync(player, state, pos, world as ServerWorld, server)
				}
			}
			true
		}

		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val permissionNode = EventAntiXrayConfig.config.general.notifyPermission
			val source = player.server.commandSource.withEntity(player).withPosition(player.pos)
			permissionCache[player.uuid] = CommandManager.hasPermissionOrOp(
				source,
				permissionNode,
				EventAntiXrayConfig.config.general.permissionLevel ?: 2,
				EventAntiXrayConfig.config.general.opLevel ?: 2
			)
			LogDebug.debug("Updated permission cache for ${player.name.string}: ${permissionCache[player.uuid]}", MOD_ID)
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			LogDebug.debug("Player ${handler.player.name.string} disconnected, cleaning up", MOD_ID)
			playerBlockBreaks.remove(handler.player.uuid)
			permissionCache.remove(handler.player.uuid)
		}
	}

	private fun processBlockBreakAsync(player: ServerPlayerEntity, state: net.minecraft.block.BlockState, pos: BlockPos, world: ServerWorld, server: MinecraftServer) {
		val blockId = Registries.BLOCK.getId(state.block)
		val trackedBlock = EventAntiXrayConfig.getTrackedBlock(blockId) ?: return

		val worldId = world.registryKey.value.toString()
		LogDebug.debug("Checking if block at $pos in $worldId is player-placed for player ${player.name.string}", MOD_ID)
		val isPlaced = DatabaseManager.isPlayerPlacedSync(worldId, pos)
		if (isPlaced) {
			LogDebug.debug("Block at $pos in $worldId is player-placed, removing from cache", MOD_ID)
			DatabaseManager.removeBlockFromCache(worldId, pos)
			if (EventAntiXrayConfig.config.database.enabled) {
				DatabaseManager.removeBlock(worldId, pos)
			}
			LogDebug.debug("Player ${player.name.string} broke player-placed block at $pos in $worldId, skipping X-ray detection", MOD_ID)
			return
		}

		LogDebug.debug("Player ${player.name.string} broke tracked block $blockId at $pos (confirmed not player-placed)", MOD_ID)
		val alertData = incrementBlockBreaks(player, blockId, pos)
		if (alertData != null) {
			server.execute {
				alertStaff(player, blockId, alertData.count, alertData.timeWindow, alertData.consecutiveAlertCount, alertData.recentPos)
			}
		}
	}

	private fun incrementBlockBreaks(player: ServerPlayerEntity, blockId: Identifier, pos: BlockPos): AlertData? {
		val trackedBlock = EventAntiXrayConfig.getTrackedBlock(blockId) ?: return null
		val timeWindowMinutes = trackedBlock.timeWindowMinutes
		val initialThreshold = trackedBlock.alertThreshold
		val subsequentThreshold = trackedBlock.subsequentAlertThreshold
		val resetAfterMinutes = trackedBlock.resetAfterMinutes

		val playerBlocks = playerBlockBreaks.computeIfAbsent(player.uuid) { ConcurrentHashMap() }
		synchronized(playerBlocks) {
			val blockData = playerBlocks.computeIfAbsent(blockId) { BlockBreakData() }

			val now = Instant.now()
			val breakEvent = BlockBreakEvent(now, pos)
			blockData.queue.addLast(breakEvent)

			val cutoff = now.minus(timeWindowMinutes.toLong(), ChronoUnit.MINUTES)
			while (blockData.queue.isNotEmpty() && blockData.queue.first().timestamp < cutoff) {
				blockData.queue.removeFirst()
			}
			val currentCount = blockData.queue.size

			if (blockData.continuousTrackingEnabled && resetAfterMinutes > 0) {
				val resetCutoffTime = blockData.lastAlertTime.plus(resetAfterMinutes.toLong(), ChronoUnit.MINUTES)
				if (now.isAfter(resetCutoffTime)) {
					LogDebug.debug("Resetting tracking for ${player.name.string} on $blockId after $resetAfterMinutes minutes", MOD_ID)
					blockData.queue.clear()
					blockData.queue.addLast(BlockBreakEvent(now, pos))
					blockData.continuousTrackingEnabled = false
					blockData.lastAlertTime = now
					blockData.lastAlertCount = 0
					blockData.consecutiveAlertCount = 0
					return null
				}
			}

			val timeWindow = Duration.ofMinutes(timeWindowMinutes.toLong())
			if (!blockData.continuousTrackingEnabled && currentCount >= initialThreshold) {
				blockData.consecutiveAlertCount = 1
				val recentPos = blockData.queue.last().position
				val alertData = AlertData(currentCount, timeWindow, blockData.consecutiveAlertCount, recentPos)
				blockData.continuousTrackingEnabled = true
				blockData.lastAlertTime = now
				blockData.lastAlertCount = currentCount
				return alertData
			} else if (blockData.continuousTrackingEnabled && currentCount >= blockData.lastAlertCount + subsequentThreshold) {
				blockData.consecutiveAlertCount++
				val recentPos = blockData.queue.last().position
				val alertData = AlertData(currentCount, timeWindow, blockData.consecutiveAlertCount, recentPos)
				blockData.lastAlertTime = now
				blockData.lastAlertCount = currentCount
				return alertData
			}
			return null
		}
	}

	private fun alertStaff(
		player: ServerPlayerEntity,
		blockId: Identifier,
		count: Int,
		timeWindow: Duration,
		consecutiveAlertCount: Int,
		recentPos: BlockPos
	) {
		val server = player.server
		val blockName = EventAntiXrayConfig.getFormattedBlockName(blockId)
		val messageTemplate = EventAntiXrayConfig.getAlertMessageForBlock(blockId)
		val messageStr = messageTemplate
			.replace("{player}", player.name.string)
			.replace("{count}", count.toString())
			.replace("{time}", "${timeWindow.toMinutes()} minutes")
			.replace("{block}", blockName)
			.replace("{x}", recentPos.x.toString())
			.replace("{y}", recentPos.y.toString())
			.replace("{z}", recentPos.z.toString())
		val finalMessageStr = if (consecutiveAlertCount > 1) {
			EventAntiXrayConfig.config.alerts.continuedAlertPrefix + messageStr
		} else {
			messageStr
		}
		val finalMessage = KyoriHelper.parseToMinecraft(finalMessageStr, server.registryManager)
		LogDebug.debug("Sending alert to staff for ${player.name.string}, count: $count at $recentPos", MOD_ID)

		val soundConfig = EventAntiXrayConfig.config.alerts.sound
		val soundId = Identifier.tryParse(soundConfig.soundId)
		val soundEvent = soundId?.let { Registries.SOUND_EVENT.get(it) }

		if (soundEvent != null) {
			val volume = soundConfig.baseVolume * (soundConfig.volumeMultiplierPerAlert.pow(consecutiveAlertCount - 1))
			val pitch = soundConfig.basePitch * (soundConfig.pitchMultiplierPerAlert.pow(consecutiveAlertCount - 1))
			broadcastToStaff(server, finalMessage, soundEvent, volume, pitch)
		} else {
			logger.warn("Invalid or missing sound event: ${soundConfig.soundId}")
			broadcastToStaff(server, finalMessage, null, 0f, 0f)
		}

		if (EventAntiXrayConfig.config.webhook.enabled && EventAntiXrayConfig.config.webhook.url.isNotEmpty()) {
			val webhookUrl = EventAntiXrayConfig.config.webhook.url
			webhookExecutor.submit {
				EventAntiXrayWebhook.sendXrayAlertToWebhook(
					webhookUrl,
					player,
					blockId,
					count,
					timeWindow,
					consecutiveAlertCount,
					recentPos
				)
			}
		}
	}

	private fun broadcastToStaff(
		server: MinecraftServer,
		message: Text,
		soundEvent: net.minecraft.sound.SoundEvent?,
		volume: Float,
		pitch: Float
	) {
		server.playerManager.playerList.forEach { serverPlayer ->
			val hasPermission = permissionCache[serverPlayer.uuid] ?: false
			if (hasPermission) {
				serverPlayer.sendMessage(message)
				if (soundEvent != null) {
					serverPlayer.world.playSound(
						null,
						serverPlayer.blockPos,
						soundEvent,
						SoundCategory.PLAYERS,
						volume,
						pitch
					)
				}
			}
		}
	}

	private fun scheduleCleanupTask() {
		LogDebug.debug("Scheduling cleanup task to run every 5 minutes", MOD_ID)
		Thread {
			while (true) {
				try {
					TimeUnit.MINUTES.sleep(5)
					LogDebug.debug("Running scheduled cleanup task", MOD_ID)
					cleanupStaleData()
					DatabaseManager.syncCacheToDatabase() // Always clean cache, sync if database enabled
				} catch (e: InterruptedException) {
					LogDebug.debug("Cleanup task interrupted", MOD_ID)
					break
				}
			}
		}.apply {
			isDaemon = true
			start()
		}
	}

	private fun cleanupStaleData() {
		val now = Instant.now()
		playerBlockBreaks.forEach { (playerId, blocks) ->
			synchronized(blocks) {
				val iterator = blocks.entries.iterator()
				while (iterator.hasNext()) {
					val (blockId, data) = iterator.next()
					val timeWindowMinutes = EventAntiXrayConfig.getTrackedBlock(blockId)?.timeWindowMinutes ?: 30
					val cutoff = now.minus(timeWindowMinutes.toLong(), ChronoUnit.MINUTES)
					while (data.queue.isNotEmpty() && data.queue.first().timestamp < cutoff) {
						data.queue.removeFirst()
					}
					if (data.queue.isEmpty() && !data.continuousTrackingEnabled) {
						iterator.remove()
					}
				}
			}
		}
		alertCache.clear()
		LogDebug.debug("Cleanup complete", MOD_ID)
	}

	companion object {
		lateinit var INSTANCE: EventAntiXray
			private set
	}

	init {
		INSTANCE = this
	}
}