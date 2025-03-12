package com.eventantixray

import com.everlastingutils.command.CommandManager
import com.eventantixray.utils.EventAntiXrayCommands
import com.eventantixray.utils.EventAntiXrayConfig
import com.eventantixray.utils.EventAntiXrayWebhook
import com.everlastingutils.utils.LogDebug
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.registry.Registries
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import net.minecraft.block.BlockState
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.time.temporal.ChronoUnit
import net.minecraft.util.Identifier
import net.minecraft.sound.SoundCategory
import kotlin.math.pow

class EventAntiXray : ModInitializer {
	private val logger = LoggerFactory.getLogger("EventAntiXray")
	private val MOD_ID = "eventantixray"

	// Optimized data structure using a queue for sliding window
	private data class BlockBreakData(
		val queue: ArrayDeque<Instant> = ArrayDeque(),
		var continuousTrackingEnabled: Boolean = false,
		var lastAlertTime: Instant = Instant.now(),
		var lastAlertCount: Int = 0,
		var consecutiveAlertCount: Int = 0  // Track consecutive alerts
	)
	private val alertCache = mutableMapOf<String, Pair<Int, Int>>()
	// Player UUID -> Block ID (Identifier) -> Break Data
	private val playerBlockBreaks = ConcurrentHashMap<UUID, MutableMap<Identifier, BlockBreakData>>()

	// Permission cache: UUID -> hasPermission
	private val permissionCache = ConcurrentHashMap<UUID, Boolean>()

	// Thread pool for webhook requests
	private val webhookExecutor = Executors.newFixedThreadPool(2) // Adjust size based on server load

	private val commandManager = CommandManager("eventantixray")

	override fun onInitialize() {
		logger.info("EventAntiXray initializing...")
		LogDebug.debug(MOD_ID, "EventAntiXray initializing...")

		val configDir = FabricLoader.getInstance().configDir.resolve("eventantixray").toFile()
		EventAntiXrayConfig.init(configDir)

		EventAntiXrayCommands.registerCommands(commandManager)
		commandManager.register()

		registerEvents()
		scheduleCleanupTask()

		logger.info("EventAntiXray initialized")
		LogDebug.debug(MOD_ID, "EventAntiXray initialization complete")
	}

	private fun registerEvents() {
		LogDebug.debug(MOD_ID, "Registering event handlers")

		PlayerBlockBreakEvents.AFTER.register { world, player, pos, state, _ ->
			if (!world.isClient && player is ServerPlayerEntity) {
				processBlockBreak(player, state)
			}
			true
		}

		ServerLifecycleEvents.SERVER_STARTING.register {
			LogDebug.debug(MOD_ID, "Server starting, reloading configuration")
			EventAntiXrayConfig.reloadBlocking()
		}

		ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
			val player = handler.player
			val permissionNode = EventAntiXrayConfig.config.general.notifyPermission
			permissionCache[player.uuid] = player.hasPermission(permissionNode) || player.hasPermissionLevel(3)
			LogDebug.debug(MOD_ID, "Updated permission cache for ${player.name.string}")
		}

		ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
			LogDebug.debug(MOD_ID, "Player ${handler.player.name.string} disconnected, cleaning up")
			playerBlockBreaks.remove(handler.player.uuid)
			permissionCache.remove(handler.player.uuid)
		}
	}

	private fun processBlockBreak(player: ServerPlayerEntity, state: BlockState) {
		val blockId = Registries.BLOCK.getId(state.block)
		val trackedBlock = EventAntiXrayConfig.getTrackedBlock(blockId) ?: return
		LogDebug.debug(MOD_ID, "Player ${player.name.string} broke tracked block $blockId")
		incrementBlockBreaks(player, blockId)
	}

	private fun incrementBlockBreaks(player: ServerPlayerEntity, blockId: Identifier) {
		val trackedBlock = EventAntiXrayConfig.getTrackedBlock(blockId) ?: return
		val timeWindowMinutes = trackedBlock.timeWindowMinutes
		val initialThreshold = trackedBlock.alertThreshold
		val subsequentThreshold = trackedBlock.subsequentAlertThreshold
		val resetAfterMinutes = trackedBlock.resetAfterMinutes

		val playerBlocks = playerBlockBreaks.computeIfAbsent(player.uuid) { ConcurrentHashMap() }
		val blockData = playerBlocks.computeIfAbsent(blockId) { BlockBreakData() }

		val now = Instant.now()
		blockData.queue.addLast(now)

		val cutoff = now.minus(timeWindowMinutes.toLong(), ChronoUnit.MINUTES)
		while (blockData.queue.isNotEmpty() && blockData.queue.first() < cutoff) {
			blockData.queue.removeFirst()
		}
		val currentCount = blockData.queue.size

		if (blockData.continuousTrackingEnabled && resetAfterMinutes > 0) {
			val resetCutoffTime = blockData.lastAlertTime.plus(resetAfterMinutes.toLong(), ChronoUnit.MINUTES)
			if (now.isAfter(resetCutoffTime)) {
				LogDebug.debug(MOD_ID, "Resetting tracking for ${player.name.string} on $blockId after $resetAfterMinutes minutes")
				blockData.queue.clear()
				blockData.queue.addLast(now)
				blockData.continuousTrackingEnabled = false
				blockData.lastAlertTime = now
				blockData.lastAlertCount = 0
				blockData.consecutiveAlertCount = 0
				return
			}
		}

		val timeWindow = Duration.ofMinutes(timeWindowMinutes.toLong())
		if (!blockData.continuousTrackingEnabled && currentCount >= initialThreshold) {
			blockData.consecutiveAlertCount = 1
			alertStaff(player, blockId, currentCount, timeWindow, blockData.consecutiveAlertCount)
			if (EventAntiXrayConfig.config.debug.enabled) {
				val blockName = EventAntiXrayConfig.getFormattedBlockName(blockId)
				LogDebug.debug("Possible X-ray detected: ${player.name.string} broke $currentCount $blockName in $timeWindowMinutes minutes", MOD_ID)
			}
			blockData.continuousTrackingEnabled = true
			blockData.lastAlertTime = now
			blockData.lastAlertCount = currentCount
		} else if (blockData.continuousTrackingEnabled && currentCount >= blockData.lastAlertCount + subsequentThreshold) {
			blockData.consecutiveAlertCount++
			alertStaff(player, blockId, currentCount, timeWindow, blockData.consecutiveAlertCount)
			if (EventAntiXrayConfig.config.debug.enabled) {
				val blockName = EventAntiXrayConfig.getFormattedBlockName(blockId)
				val newBlocks = currentCount - blockData.lastAlertCount
				LogDebug.debug("Continued X-ray activity: ${player.name.string} broke $newBlocks additional $blockName (total: $currentCount) in $timeWindowMinutes minutes", MOD_ID)
			}
			blockData.lastAlertTime = now
			blockData.lastAlertCount = currentCount
		}
	}

	private fun alertStaff(
		player: ServerPlayerEntity,
		blockId: Identifier,
		count: Int,
		timeWindow: Duration,
		consecutiveAlertCount: Int
	) {
		val server = player.server
		val blockName = EventAntiXrayConfig.getFormattedBlockName(blockId)
		val messageTemplate = EventAntiXrayConfig.getParsedAlertMessage(blockId)
		val messageText = messageTemplate.copy()
		val messageStr = messageText.string
			.replace("%%PLAYER%%", player.name.string)
			.replace("%%COUNT%%", count.toString())
			.replace("%%TIME%%", "${timeWindow.toMinutes()} minutes")
			.replace("%%BLOCK%%", blockName)
		val finalMessage = Text.literal(messageStr)
		val finalText = if (consecutiveAlertCount > 1) {
			EventAntiXrayConfig.getParsedContinuedPrefix().copy().append(finalMessage)
		} else {
			finalMessage
		}

		LogDebug.debug(MOD_ID, "Sending alert to staff for ${player.name.string}, count: $count")

		val soundConfig = EventAntiXrayConfig.config.alerts.sound
		val soundId = try {
			Identifier.tryParse(soundConfig.soundId)
		} catch (e: Exception) {
			logger.warn("Error parsing sound ID: ${e.message}")
			null
		}

		if (soundId != null) {
			val soundEvent = Registries.SOUND_EVENT.get(soundId)
			if (soundEvent != null) {
				val volume = soundConfig.baseVolume * (soundConfig.volumeMultiplierPerAlert.pow(consecutiveAlertCount - 1))
				val pitch = soundConfig.basePitch * (soundConfig.pitchMultiplierPerAlert.pow(consecutiveAlertCount - 1))
				broadcastToStaff(server, finalText, soundEvent, volume, pitch)
			} else {
				logger.warn("Sound event not found: ${soundConfig.soundId}")
				broadcastToStaff(server, finalText, null, 0f, 0f)
			}
		} else {
			logger.warn("Invalid sound ID: ${soundConfig.soundId}")
			broadcastToStaff(server, finalText, null, 0f, 0f)
		}

		// Asynchronous webhook handling
		if (EventAntiXrayConfig.config.webhook.enabled && EventAntiXrayConfig.config.webhook.url.isNotEmpty()) {
			val webhookUrl = EventAntiXrayConfig.config.webhook.url
			webhookExecutor.submit {
				EventAntiXrayWebhook.sendXrayAlertToWebhook(
					webhookUrl,
					player,
					blockId,
					count,
					timeWindow,
					consecutiveAlertCount
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

	private fun ServerPlayerEntity.hasPermission(permission: String): Boolean {
		val source = server.commandSource.withEntity(this).withPosition(pos)
		return CommandManager.hasPermissionOrOp(source, permission, 2, 2)
	}

	private fun scheduleCleanupTask() {
		LogDebug.debug(MOD_ID, "Scheduling cleanup task to run every 5 minutes")
		Thread {
			while (true) {
				try {
					TimeUnit.MINUTES.sleep(5)
					LogDebug.debug(MOD_ID, "Running scheduled cleanup task")
					cleanupStaleData()
				} catch (e: InterruptedException) {
					LogDebug.debug(MOD_ID, "Cleanup task interrupted")
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
			val iterator = blocks.entries.iterator()
			while (iterator.hasNext()) {
				val (blockId, data) = iterator.next()
				val timeWindowMinutes = EventAntiXrayConfig.getTrackedBlock(blockId)?.timeWindowMinutes ?: 30
				val cutoff = now.minus(timeWindowMinutes.toLong(), ChronoUnit.MINUTES)
				while (data.queue.isNotEmpty() && data.queue.first() < cutoff) {
					data.queue.removeFirst()
				}
				if (data.queue.isEmpty() && !data.continuousTrackingEnabled) {
					iterator.remove()
				}
			}
			if (blocks.isEmpty()) playerBlockBreaks.remove(playerId)
		}
		alertCache.clear()
		LogDebug.debug(MOD_ID, "Cleanup complete")
	}

	companion object {
		lateinit var INSTANCE: EventAntiXray
			private set
	}

	init {
		INSTANCE = this
	}
}