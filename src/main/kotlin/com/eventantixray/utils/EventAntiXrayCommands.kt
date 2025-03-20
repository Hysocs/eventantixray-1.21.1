package com.eventantixray.utils

import com.everlastingutils.command.CommandManager
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.ServerCommandSource
import org.slf4j.LoggerFactory
import com.eventantixray.utils.DatabaseManager
import com.eventantixray.utils.EventAntiXrayConfig

object EventAntiXrayCommands {
    private val logger = LoggerFactory.getLogger("EventAntiXray")

    fun registerCommands(commandManager: CommandManager) {
        commandManager.command("antixray", permission = "antixray.command") {
            // Base command info
            executes { context ->
                displayInfo(context)
            }

            // Reload subcommand
            subcommand("reload", permission = "antixray.reload") {
                executes { context ->
                    reloadConfig(context)
                }
            }

            subcommand("viewinventory", permission = "antixray.playerinv") {
                executes { context ->
                    CommandManager.sendSuccess(context.source, "Usage: /antixray viewinventory <player>", false)
                    0
                }
                then(
                    argument("player", EntityArgumentType.player())
                        .executes { context ->
                            viewPlayerInventory(context)
                        }
                )
            }

            // New forcesync subcommand
            subcommand("forcesync", permission = "antixray.forcesync") {
                executes { context ->
                    forceSync(context)
                }
            }
        }
    }

    private fun displayInfo(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source

        CommandManager.sendSuccess(
            source,
            "§6=== EventAntiXray ===",
            false
        )

        val trackedBlockCount = EventAntiXrayConfig.config.trackedBlocks.size

        CommandManager.sendSuccess(
            source,
            "§7Monitoring §f$trackedBlockCount §7blocks for potential X-ray activity",
            false
        )
        CommandManager.sendSuccess(
            source,
            "§7Use §f/antixray reload §7to reload the configuration",
            false
        )
        CommandManager.sendSuccess(
            source,
            "§7Use §f/antixray status §7to view current monitoring status",
            false
        )

        return 1
    }

    private fun reloadConfig(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val server = source.server

        try {
            // Check if database was enabled before reload
            val wasDatabaseEnabled = EventAntiXrayConfig.config.database.enabled

            // Reload the configuration on the main thread
            EventAntiXrayConfig.reloadBlocking()

            // If database was enabled, schedule async task to sync and close
            if (wasDatabaseEnabled) {
                DatabaseManager.executor.submit {
                    try {
                        DatabaseManager.syncCacheToDatabase()
                        DatabaseManager.close()
                    } catch (e: Exception) {
                        logger.error("Failed to sync cache or close database during reload: ${e.message}")
                    }
                }
            }

            // If database is enabled in the new config, schedule async task to init
            if (EventAntiXrayConfig.config.database.enabled) {
                DatabaseManager.executor.submit {
                    try {
                        DatabaseManager.init()
                    } catch (e: Exception) {
                        logger.error("Failed to initialize database after reload: ${e.message}")
                    }
                }
            }

            val trackedBlockCount = EventAntiXrayConfig.config.trackedBlocks.size

            CommandManager.sendSuccess(
                source,
                "§aEventAntiXray configuration reloaded successfully!",
                true
            )
            CommandManager.sendSuccess(
                source,
                "§7Now tracking §f$trackedBlockCount §7blocks",
                true
            )

            logger.info("Configuration reloaded by ${source.name}")
            return 1
        } catch (e: Exception) {
            CommandManager.sendError(
                source,
                "§cFailed to reload configuration: ${e.message}"
            )
            logger.error("Failed to reload configuration", e)
            return 0
        }
    }

    private fun viewPlayerInventory(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val viewer = source.player
        if (viewer == null) {
            CommandManager.sendError(source, "§cThis command can only be executed by a player")
            return 0
        }
        try {
            val target = EntityArgumentType.getPlayer(context, "player")
            PlayerInventoryViewer.openInventoryViewer(viewer, target)
            logger.info("${viewer.name.string} is viewing ${target.name.string}'s inventory")
            return 1
        } catch (e: Exception) {
            CommandManager.sendError(source, "§cPlayer not found or not online")
            logger.error("Error viewing player inventory", e)
            return 0
        }
    }

    private fun forceSync(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        if (!EventAntiXrayConfig.config.database.enabled) {
            CommandManager.sendError(source, "§cDatabase is disabled in the configuration.")
            return 0
        }
        DatabaseManager.executor.submit {
            try {
                DatabaseManager.syncCacheToDatabase()
                logger.info("Database sync forced successfully by ${source.name}")
            } catch (e: Exception) {
                logger.error("Failed to force database sync", e)
            }
        }
        CommandManager.sendSuccess(source, "§aDatabase sync has been triggered.", true)
        return 1
    }
}