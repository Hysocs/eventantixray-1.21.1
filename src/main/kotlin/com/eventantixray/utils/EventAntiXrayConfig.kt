package com.eventantixray.utils

import com.everlastingutils.config.ConfigData
import com.everlastingutils.config.ConfigManager
import com.everlastingutils.config.ConfigMetadata
import com.everlastingutils.utils.LogDebug
import com.everlastingutils.colors.KyoriHelper
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.runBlocking
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

// Data class for tracked blocks
data class TrackedBlockData(
    @SerializedName("block_id")
    val blockId: String,

    @SerializedName("alert_threshold")
    val alertThreshold: Int,

    @SerializedName("time_window_minutes")
    val timeWindowMinutes: Int,

    @SerializedName("subsequent_alert_threshold")
    val subsequentAlertThreshold: Int = 5,

    @SerializedName("reset_after_minutes")
    val resetAfterMinutes: Int = 0,  // 0 means no automatic reset

    @SerializedName("alert_message")
    val alertMessage: String
)

// Data class for alert sound settings
data class AlertSoundData(
    @SerializedName("sound_id")
    val soundId: String = "minecraft:block.note_block.pling",

    @SerializedName("base_volume")
    val baseVolume: Float = 1.0f,

    @SerializedName("base_pitch")
    val basePitch: Float = 1.0f,

    @SerializedName("volume_multiplier_per_alert")
    val volumeMultiplierPerAlert: Float = 1.0f,

    @SerializedName("pitch_multiplier_per_alert")
    val pitchMultiplierPerAlert: Float = 1.0f
)

// Data class for webhook settings
data class WebhookSettings(
    @SerializedName("enabled")
    val enabled: Boolean = false,

    @SerializedName("url")
    val url: String = ""
)

// Data class for debug settings
data class DebugSettings(
    @SerializedName("enabled")
    val enabled: Boolean = false
)

// Main config data class with reorganized sections
data class EventAntiXrayConfigData(
    override val version: String = "1.0.0",
    override val configId: String = "eventantixray",

    @SerializedName("general")
    val general: GeneralSettings = GeneralSettings(),

    @SerializedName("debug")
    val debug: DebugSettings = DebugSettings(),

    @SerializedName("alerts")
    val alerts: AlertSettings = AlertSettings(),

    @SerializedName("webhook")
    val webhook: WebhookSettings = WebhookSettings(),

    @SerializedName("tracked_blocks")
    val trackedBlocks: List<TrackedBlockData> = defaultTrackedBlocks()
) : ConfigData

// Data class for general settings
data class GeneralSettings(
    val notifyPermission: String = "antixray.notify",
    val permissionLevel: Int = 2,
    val opLevel: Int = 2
)

// Data class for alert settings
data class AlertSettings(
    @SerializedName("sound")
    val sound: AlertSoundData = AlertSoundData(),

    @SerializedName("continued_alert_prefix")
    val continuedAlertPrefix: String = "<red>[Continued]</red> "
)

// Helper function for default tracked blocks (updated to use MiniMessage formatting)
fun defaultTrackedBlocks(): List<TrackedBlockData> {
    return listOf(
        TrackedBlockData("minecraft:diamond_ore", 10, 30, 5, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_diamond_ore", 10, 30, 5, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:ancient_debris", 5, 20, 3, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:emerald_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_emerald_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:nether_gold_ore", 15, 30, 8, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:gold_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_gold_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:lapis_ore", 12, 30, 6, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_lapis_ore", 12, 30, 6, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:redstone_ore", 25, 30, 15, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_redstone_ore", 25, 30, 15, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:iron_ore", 35, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_iron_ore", 35, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:copper_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_copper_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:coal_ore", 60, 30, 30, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:deepslate_coal_ore", 60, 30, 30, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:nether_quartz_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:spawner", 2, 60, 1, 10, "<red>[AntiXray]</red> <white>{player} has found {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:budding_amethyst", 4, 30, 2, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:suspicious_sand", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>"),
        TrackedBlockData("minecraft:suspicious_gravel", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time}!</white>")
    )
}

object EventAntiXrayConfig {
    private val logger = LoggerFactory.getLogger("EventAntiXray")
    private const val MOD_ID = "eventantixray"
    private const val CURRENT_VERSION = "1.0.0"
    private lateinit var configManager: ConfigManager<EventAntiXrayConfigData>
    private var isInitialized = false

    // Use Identifier for faster lookups
    private val trackedBlocksMap = mutableMapOf<Identifier, TrackedBlockData>()

    private val configMetadata = ConfigMetadata(
        headerComments = listOf(
            "EventAntiXray Configuration File",
            "",
            "This file lets you configure the EventAntiXray mod to detect potential X-ray cheating.",
            "The mod monitors when players break specific blocks and alerts staff if suspicious activity is detected.",
            "",
            "Configuration Settings:",
            "",
            "general:",
            "- notify_permission: Permission required to receive notifications",
            "",
            "debug:",
            "- enabled: Enable debug logging",
            "",
            "alerts:",
            "- sound: Settings for the alert sound",
            "  - sound_id: Minecraft sound identifier (e.g., minecraft:block.note_block.pling)",
            "  - base_volume: Base volume for the sound (0.0 to 1.0)",
            "  - base_pitch: Base pitch for the sound (0.5 to 2.0)",
            "  - volume_multiplier_per_alert: Multiplier for volume per consecutive alert",
            "  - pitch_multiplier_per_alert: Multiplier for pitch per consecutive alert",
            "- continued_alert_prefix: Prefix added to continued alerts (MiniMessage format)",
            "",
            "webhook:",
            "- enabled: Enable sending X-ray alerts to a Discord webhook (true/false)",
            "- url: The Discord webhook ID/token format (e.g., 1234567890123456789/abcdefghijklmnopqrstuvwxyz)",
            "  Note: Do NOT include the full URL, just the ID and token part after discord.com/api/webhooks/",
            "",
            "tracked_blocks: List of blocks to monitor for potential x-ray cheating",
            "- block_id: Minecraft block identifier (e.g., minecraft:diamond_ore)",
            "- alert_threshold: Number of blocks broken to trigger an initial alert",
            "- time_window_minutes: Time period to count blocks within",
            "- subsequent_alert_threshold: After initial alert, how many more blocks before next alert",
            "- reset_after_minutes: Automatically reset tracking after this many minutes (0 = never reset)",
            "- alert_message: Message format for alerts with placeholders:",
            "  {player} - Player name",
            "  {count} - Number of blocks mined",
            "  {time} - Time period",
            "  {block} - Formatted block name",
            "  Note: Uses MiniMessage format for colors/styling: https://docs.advntr.dev/minimessage/format.html"
        ),
        footerComments = listOf("End of EventAntiXray Configuration"),
        sectionComments = mapOf(
            "version" to "WARNING: Do not edit this value - doing so may corrupt your configuration",
            "configId" to "WARNING: Do not edit this value - changing this will create a new configuration file",
            "general" to "General settings for the mod",
            "debug" to "Debug logging settings",
            "alerts" to "Alert settings for staff notifications",
            "webhook" to "Discord webhook notification settings",
            "webhook.url" to "Example: 1234567890123456789/abcdefghijklmnopqrstuvwxyz",
            "alerts.sound" to "Settings for the alert sound played to staff",
            "alerts.continued_alert_prefix" to "Prefix added to continued alerts (when multiple alerts for the same player/block occur)",
            "tracked_blocks" to "List of blocks to monitor for potential x-ray cheating",
            "tracked_blocks[].subsequent_alert_threshold" to "After the initial alert, alert again every time this many additional blocks are broken",
            "tracked_blocks[].reset_after_minutes" to "Automatically reset tracking after this many minutes since the last alert (0 = never reset)"
        ),
        includeTimestamp = true,
        includeVersion = true
    )

    fun init(configDir: java.io.File) {
        LogDebug.init(MOD_ID, false)
        LogDebug.debug("Initializing EventAntiXray configuration", MOD_ID)
        if (!isInitialized) {
            initialize()
            runBlocking { load() }
            isInitialized = true
            LogDebug.debug("EventAntiXray configuration initialized", MOD_ID)
        }
    }

    private fun initialize() {
        LogDebug.debug("Creating config manager instance", MOD_ID)
        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = EventAntiXrayConfigData(),
            configClass = EventAntiXrayConfigData::class,
            metadata = configMetadata
        )
    }

    fun loadConfig() {
        runBlocking { load() }
    }

    private suspend fun load() {
        LogDebug.debug("Loading configuration...", MOD_ID)
        configManager.reloadConfig()
        LogDebug.debug("Configuration loaded, updating debug state...", MOD_ID)
        updateDebugState()
        parseTrackedBlocks()
        LogDebug.debug("Blocks parsed", MOD_ID)
    }

    fun reloadBlocking() {
        LogDebug.debug("Starting config reload...", MOD_ID)
        runBlocking {
            configManager.reloadConfig()
            updateDebugState()
            parseTrackedBlocks()
            LogDebug.debug("Reload complete", MOD_ID)
        }
    }

    private fun updateDebugState() {
        val debugEnabled = configManager.getCurrentConfig().debug.enabled
        LogDebug.setDebugEnabledForMod(MOD_ID, debugEnabled)
        LogDebug.debug("Debug state updated to: $debugEnabled", MOD_ID)
    }

    val config: EventAntiXrayConfigData
        get() = configManager.getCurrentConfig()

    private fun parseTrackedBlocks() {
        trackedBlocksMap.clear()
        config.trackedBlocks.forEach { block ->
            try {
                // Use safe method to parse identifiers
                val identifier = Identifier.tryParse(block.blockId)
                if (identifier != null) {
                    trackedBlocksMap[identifier] = block
                    LogDebug.debug(
                        "Tracking block: ${block.blockId}, threshold: ${block.alertThreshold}, " +
                                "window: ${block.timeWindowMinutes}min, subsequent: ${block.subsequentAlertThreshold}, " +
                                "reset after: ${if (block.resetAfterMinutes > 0) "${block.resetAfterMinutes}min" else "never"}",
                        MOD_ID
                    )
                } else {
                    logger.warn("Invalid block ID format: ${block.blockId}")
                }
            } catch (e: Exception) {
                logger.error("Error processing block ${block.blockId}: ${e.message}")
            }
        }
        logger.info("EventAntiXray configured to track ${trackedBlocksMap.size} blocks")
    }

    fun getTrackedBlock(blockId: Identifier): TrackedBlockData? = trackedBlocksMap[blockId]

    /**
     * Gets the original alert message format string
     */
    fun getAlertMessageForBlock(blockId: Identifier): String =
        trackedBlocksMap[blockId]?.alertMessage ?: "<red>[AntiXray]</red> <white>{player} has mined {count} blocks in {time}!</white>"

    fun getFormattedBlockName(blockId: Identifier): String =
        blockId.path.replace("_", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.first().uppercaseChar() + word.substring(1) else ""
        }

    fun cleanup() {
        if (isInitialized) {
            LogDebug.debug("Cleaning up configuration resources", MOD_ID)
            configManager.cleanup()
            isInitialized = false
        }
    }
}