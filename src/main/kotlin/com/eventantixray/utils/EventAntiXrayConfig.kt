package com.eventantixray.utils

import com.everlastingutils.config.ConfigData
import com.everlastingutils.config.ConfigManager
import com.everlastingutils.config.ConfigMetadata
import com.everlastingutils.utils.LogDebug
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.runBlocking
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory

// Database settings for MySQL integration to track player-placed blocks
data class DatabaseSettings(
    // Determines whether the database feature is active to prevent false X-ray alerts for player-placed blocks
    val enabled: Boolean = false,
    // Specifies the database type; currently, only "mysql" is supported
    val type: String = "mysql",
    // Option to use a full JDBC URL instead of individual connection fields (address, databasename, etc.)
    val useFullUrlInstead: Boolean = false,
    // Full JDBC URL for MySQL connection (e.g., "jdbc:mysql://localhost:3306/eventantixray?useSSL=false"); used when useFullUrlInstead is true
    val fullUrl: String = "jdbc:",
    // Name of the MySQL database; used when useFullUrlInstead is false
    val databasename: String = "eventantixray",
    // MySQL server address in "host:port" format (e.g., "localhost:3306"); used when useFullUrlInstead is false
    val address: String = "",
    // Username for MySQL database authentication; used when useFullUrlInstead is false
    val username: String = "",
    // Password for MySQL database authentication; used when useFullUrlInstead is false
    val password: String = "",
    // Enables SSL for the MySQL connection; used when useFullUrlInstead is false
    val useSSL: Boolean = true
)

// Configuration for blocks monitored for potential X-ray activity
data class TrackedBlockData(
    // Minecraft block identifier (e.g., "minecraft:diamond_ore")
    @SerializedName("block_id")
    val blockId: String,
    // Number of blocks broken within timeWindowMinutes to trigger the first alert
    @SerializedName("alert_threshold")
    val alertThreshold: Int,
    // Time period (in minutes) for tracking block breaks toward the alert threshold
    @SerializedName("time_window_minutes")
    val timeWindowMinutes: Int,
    // Number of additional blocks broken after the initial alert to trigger further alerts
    @SerializedName("subsequent_alert_threshold")
    val subsequentAlertThreshold: Int = 5,
    // Minutes of inactivity after which tracking resets (0 = never reset)
    @SerializedName("reset_after_minutes")
    val resetAfterMinutes: Int = 0,
    // Custom alert message with placeholders: {player}, {count}, {block}, {time}, {x}, {y}, {z}
    @SerializedName("alert_message")
    val alertMessage: String
)

// Settings for the sound played when an alert is triggered
data class AlertSoundData(
    // Minecraft sound identifier (e.g., "minecraft:block.note_block.pling")
    @SerializedName("sound_id")
    val soundId: String = "minecraft:block.note_block.pling",
    // Base volume of the alert sound (range: 0.0 to 1.0)
    @SerializedName("base_volume")
    val baseVolume: Float = 1.0f,
    // Base pitch of the alert sound (range: 0.5 to 2.0)
    @SerializedName("base_pitch")
    val basePitch: Float = 1.0f,
    // Volume multiplier applied per consecutive alert
    @SerializedName("volume_multiplier_per_alert")
    val volumeMultiplierPerAlert: Float = 1.0f,
    // Pitch multiplier applied per consecutive alert
    @SerializedName("pitch_multiplier_per_alert")
    val pitchMultiplierPerAlert: Float = 1.0f
)

// Configuration for sending alerts to a Discord webhook
data class WebhookSettings(
    // Enables or disables Discord webhook notifications
    @SerializedName("enabled")
    val enabled: Boolean = false,
    // URL of the Discord webhook for sending alerts
    @SerializedName("url")
    val url: String = ""
)

// Debug logging configuration
data class DebugSettings(
    // Enables or disables detailed debug logging for troubleshooting
    @SerializedName("enabled")
    val enabled: Boolean = false
)

// Settings for staff alerts
data class AlertSettings(
    // Configuration for the alert sound played to staff
    @SerializedName("sound")
    val sound: AlertSoundData = AlertSoundData(),
    // Prefix added to alert messages for subsequent alerts (uses MiniMessage format)
    @SerializedName("continued_alert_prefix")
    val continuedAlertPrefix: String = "<red>[Continued]</red> "
)

// Main configuration for the EventAntiXray mod
data class EventAntiXrayConfigData(
    // Configuration version; do not modify unless you understand the implications
    override val version: String = "1.0.1",
    // Unique identifier for this config; editing this creates a new config file
    override val configId: String = "eventantixray",
    // General mod settings (permissions, etc.)
    @SerializedName("general")
    val general: GeneralSettings = GeneralSettings(),
    // Debug logging settings
    @SerializedName("debug")
    val debug: DebugSettings = DebugSettings(),
    // Alert notification settings
    @SerializedName("alerts")
    val alerts: AlertSettings = AlertSettings(),
    // Discord webhook settings
    @SerializedName("webhook")
    val webhook: WebhookSettings = WebhookSettings(),
    // MySQL database settings for tracking player-placed blocks
    @SerializedName("database")
    val database: DatabaseSettings = DatabaseSettings(),
    // List of blocks to monitor for X-ray detection
    @SerializedName("tracked_blocks")
    val trackedBlocks: List<TrackedBlockData> = defaultTrackedBlocks()
) : ConfigData

// General mod settings, primarily for permissions
data class GeneralSettings(
    // Permission node required to receive X-ray alerts (e.g., "antixray.notify")
    val notifyPermission: String = "antixray.notify",
    // Minimum permission level needed to receive alerts (if using permission levels)
    val permissionLevel: Int = 2,
    // Minimum operator level required to receive alerts (if using op levels)
    val opLevel: Int = 2
)

// Provides default blocks to monitor for X-ray activity
fun defaultTrackedBlocks(): List<TrackedBlockData> {
    return listOf(
        TrackedBlockData("minecraft:diamond_ore", 10, 30, 5, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_diamond_ore", 10, 30, 5, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:ancient_debris", 5, 20, 3, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:emerald_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_emerald_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:nether_gold_ore", 15, 30, 8, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:gold_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_gold_ore", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:lapis_ore", 12, 30, 6, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_lapis_ore", 12, 30, 6, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:redstone_ore", 25, 30, 15, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_redstone_ore", 25, 30, 15, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:iron_ore", 35, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_iron_ore", 35, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:copper_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_copper_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:coal_ore", 60, 30, 30, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:deepslate_coal_ore", 60, 30, 30, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:nether_quartz_ore", 40, 30, 20, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:spawner", 2, 60, 1, 10, "<red>[AntiXray]</red> <white>{player} has found {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:budding_amethyst", 4, 30, 2, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:suspicious_sand", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>"),
        TrackedBlockData("minecraft:suspicious_gravel", 8, 30, 4, 10, "<red>[AntiXray]</red> <white>{player} has mined {count} {block} in {time} at ({x}, {y}, {z})!</white>")
    )
}

object EventAntiXrayConfig {
    private val logger = LoggerFactory.getLogger("EventAntiXray")
    private const val MOD_ID = "eventantixray"
    private const val CURRENT_VERSION = "1.0.1"
    private lateinit var configManager: ConfigManager<EventAntiXrayConfigData>
    private var isInitialized = false

    private val trackedBlocksMap = mutableMapOf<Identifier, TrackedBlockData>()

    private val configMetadata = ConfigMetadata(
        headerComments = listOf(
            "EventAntiXray Configuration File",
            "",
            "This file configures the EventAntiXray mod to detect potential X-ray cheating by monitoring block breaks.",
            "Staff are alerted when suspicious activity is detected based on the settings below.",
            "",
            "Configuration Sections:",
            "",
            "general:",
            "- notify_permission: Permission node required to receive X-ray alerts",
            "- permission_level: Minimum permission level to receive alerts",
            "- op_level: Minimum operator level to receive alerts",
            "",
            "debug:",
            "- enabled: Enable or disable debug logging",
            "",
            "alerts:",
            "- sound: Configuration for the alert sound played to staff",
            "  - sound_id: Minecraft sound ID (e.g., \"minecraft:block.note_block.pling\")",
            "  - base_volume: Base volume (0.0 to 1.0)",
            "  - base_pitch: Base pitch (0.5 to 2.0)",
            "  - volume_multiplier_per_alert: Volume multiplier per consecutive alert",
            "  - pitch_multiplier_per_alert: Pitch multiplier per consecutive alert",
            "- continued_alert_prefix: Prefix for subsequent alerts (MiniMessage format)",
            "",
            "webhook:",
            "- enabled: Enable or disable Discord webhook alerts",
            "- url: Discord webhook URL for sending alerts",
            "",
            "database:",
            "- enabled: Enable MySQL to track player-placed blocks and prevent false X-ray alerts",
            "- type: Database type (only \"mysql\" is supported)",
            "- useFullUrlInstead: Use a full JDBC URL (true) or separate fields (false)",
            "- fullUrl: Full JDBC URL (e.g., \"jdbc:mysql://localhost:3306/eventantixray?useSSL=false\") when useFullUrlInstead is true",
            "- databasename: MySQL database name when useFullUrlInstead is false",
            "- address: MySQL host:port (e.g., \"localhost:3306\") when useFullUrlInstead is false",
            "- username: MySQL username when useFullUrlInstead is false",
            "- password: MySQL password when useFullUrlInstead is false",
            "- useSSL: Enable SSL for MySQL when useFullUrlInstead is false",
            "",
            "tracked_blocks:",
            "- block_id: Minecraft block ID (e.g., \"minecraft:diamond_ore\")",
            "- alert_threshold: Blocks broken to trigger an initial alert",
            "- time_window_minutes: Time window (minutes) for counting blocks",
            "- subsequent_alert_threshold: Additional blocks for subsequent alerts",
            "- reset_after_minutes: Reset tracking after inactivity (minutes, 0 = never)",
            "- alert_message: Alert message with placeholders ({player}, {count}, etc.)"
        ),
        footerComments = listOf("End of EventAntiXray Configuration"),
        sectionComments = mapOf(
            "version" to "WARNING: Do not edit - modifying this may corrupt your config",
            "configId" to "WARNING: Do not edit - changing this creates a new config file",
            "general" to "General mod settings",
            "debug" to "Debug logging configuration",
            "alerts" to "Staff alert settings",
            "webhook" to "Discord webhook integration",
            "database" to "MySQL settings to track player-placed blocks; use 'useFullUrlInstead' to toggle between full URL or separate fields",
            "tracked_blocks" to "Blocks monitored for X-ray detection"
        ),
        includeTimestamp = true,
        includeVersion = true
    )

    // Initializes the configuration system with the specified directory
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

    // Sets up the ConfigManager instance
    private fun initialize() {
        LogDebug.debug("Creating config manager instance", MOD_ID)
        configManager = ConfigManager(
            currentVersion = CURRENT_VERSION,
            defaultConfig = EventAntiXrayConfigData(),
            configClass = EventAntiXrayConfigData::class,
            metadata = configMetadata
        )
    }

    // Loads the configuration asynchronously
    fun loadConfig() {
        runBlocking { load() }
    }

    // Internal async load function
    private suspend fun load() {
        LogDebug.debug("Loading configuration...", MOD_ID)
        configManager.reloadConfig()
        LogDebug.debug("Configuration loaded, updating debug state...", MOD_ID)
        updateDebugState()
        parseTrackedBlocks()
        LogDebug.debug("Blocks parsed", MOD_ID)
    }

    // Reloads the configuration in a blocking manner
    fun reloadBlocking() {
        LogDebug.debug("Starting config reload...", MOD_ID)
        runBlocking {
            configManager.reloadConfig()
            updateDebugState()
            parseTrackedBlocks()
            LogDebug.debug("Reload complete", MOD_ID)
        }
    }

    // Updates the debug logging state based on config
    private fun updateDebugState() {
        val debugEnabled = configManager.getCurrentConfig().debug.enabled
        LogDebug.setDebugEnabledForMod(MOD_ID, debugEnabled)
        LogDebug.debug("Debug state updated to: $debugEnabled", MOD_ID)
    }

    // Retrieves the current configuration
    val config: EventAntiXrayConfigData
        get() = configManager.getCurrentConfig()

    // Parses tracked blocks into a map for quick lookup
    private fun parseTrackedBlocks() {
        trackedBlocksMap.clear()
        config.trackedBlocks.forEach { block ->
            try {
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

    // Gets the tracked block data for a given block ID
    fun getTrackedBlock(blockId: Identifier): TrackedBlockData? = trackedBlocksMap[blockId]

    // Retrieves the alert message for a block, with a fallback if not found
    fun getAlertMessageForBlock(blockId: Identifier): String =
        trackedBlocksMap[blockId]?.alertMessage ?: "<red>[AntiXray]</red> <white>{player} has mined {count} blocks in {time} at ({x}, {y}, {z})!</white>"

    // Formats a block ID into a human-readable name
    fun getFormattedBlockName(blockId: Identifier): String =
        blockId.path.replace("_", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.first().uppercaseChar() + word.substring(1) else ""
        }

    // Cleans up configuration resources
    fun cleanup() {
        if (isInitialized) {
            LogDebug.debug("Cleaning up configuration resources", MOD_ID)
            configManager.cleanup()
            isInitialized = false
        }
    }
}