package com.eventantixray.utils

import com.everlastingutils.command.CommandManager
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.ServerCommandSource
import org.slf4j.LoggerFactory

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
                    CommandManager.sendSuccess(context.source, "Usage: /cleanplay viewinventory <player>", false)
                    0
                }
                then(
                    argument("player", EntityArgumentType.player())
                    .executes { context ->
                        viewPlayerInventory(context)
                    }
                )
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

        try {
            EventAntiXrayConfig.reloadBlocking()

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
}