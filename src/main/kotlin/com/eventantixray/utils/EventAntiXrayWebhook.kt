package com.eventantixray.utils

import com.everlastingutils.utils.LogDebug
import com.google.gson.Gson
import com.google.gson.JsonObject
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.Identifier
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import net.minecraft.util.math.BlockPos

object EventAntiXrayWebhook {
    private val logger = LoggerFactory.getLogger("EventAntiXrayWebhook")
    private val MOD_ID = "eventantixray"
    private val VERSION = "1.0.1" // Add your mod version here

    // Date formatter for readable timestamps
    private val dateFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    // Cache structure to include last update time and list of positions
    private data class CachedMessage(
        var messageId: String,
        var lastUpdated: Instant,
        val positions: MutableList<BlockPos> = mutableListOf()
    )
    private val webhookMessageCache = ConcurrentHashMap<String, CachedMessage>()

    // Scheduler for cache cleanup
    private val cleanupScheduler = Executors.newScheduledThreadPool(1)

    init {
        cleanupScheduler.scheduleAtFixedRate({
            cleanupWebhookCache()
        }, 0, 10, java.util.concurrent.TimeUnit.MINUTES)
    }

    private fun cleanupWebhookCache() {
        val now = Instant.now()
        webhookMessageCache.entries.removeIf { entry ->
            Duration.between(entry.value.lastUpdated, now).toMinutes() > 30
        }
        LogDebug.debug("Cleaned up webhook message cache", MOD_ID)
    }

    // Function to get a formatted list of the most valuable items in a player's inventory
    private fun getPlayerInventoryItems(player: ServerPlayerEntity): String {
        val sb = StringBuilder()
        var itemCount = 0
        val maxItems = 10 // Maximum number of items to show

        for (i in 0 until player.inventory.size()) {
            val stack = player.inventory.getStack(i)
            if (!stack.isEmpty) {
                val itemName = stack.name.string
                val count = if (stack.count > 1) " x${stack.count}" else ""
                sb.append(itemName).append(count).append("\n")
                itemCount++
                if (itemCount >= maxItems) {
                    sb.append("...(more items not shown)")
                    break
                }
            }
        }

        if (itemCount == 0) {
            sb.append("No items found")
        }

        return sb.toString()
    }

    fun sendXrayAlertToWebhook(
        webhookUrl: String,
        player: ServerPlayerEntity,
        blockId: Identifier,
        count: Int,
        timeWindow: Duration,
        consecutiveAlertCount: Int,
        recentPos: BlockPos
    ) {
        try {
            val fullWebhookUrl = if (webhookUrl.startsWith("http://") || webhookUrl.startsWith("https://")) {
                webhookUrl
            } else {
                "https://discord.com/api/webhooks/$webhookUrl"
            }

            val playerName = player.name.string
            val playerUuid = player.uuidAsString
            val blockName = EventAntiXrayConfig.getFormattedBlockName(blockId)
            val timeMinutes = timeWindow.toMinutes()
            val timestamp = Instant.now()
            val formattedTimestamp = dateFormatter.format(timestamp)
            val cacheKey = "${playerUuid}:${blockId}"
            val cachedMessage = webhookMessageCache.getOrPut(cacheKey) {
                CachedMessage("", Instant.now())
            }

            // Add the recent position to the list
            cachedMessage.positions.add(recentPos)

            val inventoryContents = getPlayerInventoryItems(player)

            val description = "Player $playerName has mined $count $blockName in $timeMinutes minutes!"
            val continuedAlert = if (consecutiveAlertCount > 1) "Yes (Alert #$consecutiveAlertCount)" else "No"

            // Format the list of positions including X, Y, and Z
            val positionsList = cachedMessage.positions.joinToString("\n") { pos ->
                "X: ${pos.x}, Y: ${pos.y}, Z: ${pos.z}"
            }

            fun escape(input: String): String = input.replace("\n", "\\n").replace("\"", "\\\"")

            val safeDescription = escape(description)
            val safePlayerUuid = escape(playerUuid)
            val safeBlockId = escape(blockId.toString())
            val safeContinuedAlert = escape(continuedAlert)
            val safeInventoryContents = escape(inventoryContents)
            val safeFormattedTimestamp = escape(formattedTimestamp)
            val safePositionsList = escape(positionsList)

            val jsonPayload = """
            {
              "embeds": [
                {
                  "title": "X-ray Alert",
                  "description": "$safeDescription",
                  "color": 15158332,
                  "fields": [
                    {
                      "name": "Continued Alert",
                      "value": "$safeContinuedAlert",
                      "inline": true
                    },
                    {
                      "name": "Player UUID",
                      "value": "$safePlayerUuid",
                      "inline": true
                    },
                    {
                      "name": "Block ID",
                      "value": "$safeBlockId",
                      "inline": true
                    },
                    {
                      "name": "Detection Time",
                      "value": "$safeFormattedTimestamp",
                      "inline": false
                    },
                    {
                      "name": "Locations",
                      "value": "```\\n$safePositionsList\\n```",
                      "inline": false
                    },
                    {
                      "name": "Player Inventory",
                      "value": "```\\n$safeInventoryContents\\n```",
                      "inline": false
                    }
                  ],
                  "timestamp": "$timestamp",
                  "footer": {
                    "text": "EventAntiXray v$VERSION"
                  }
                }
              ]
            }
            """.trimIndent()

            if (cachedMessage.messageId.isNotEmpty() && consecutiveAlertCount > 1) {
                LogDebug.debug("Updating existing webhook message (ID: ${cachedMessage.messageId})", MOD_ID)
                updateWebhookMessage(fullWebhookUrl, cachedMessage.messageId, jsonPayload)
                cachedMessage.lastUpdated = Instant.now()
            } else {
                LogDebug.debug("Sending new webhook message", MOD_ID)
                val messageId = sendNewWebhookMessage(fullWebhookUrl, jsonPayload)
                if (messageId != null) {
                    cachedMessage.messageId = messageId
                    cachedMessage.lastUpdated = Instant.now()
                    LogDebug.debug("Cached webhook message ID: $messageId for key: $cacheKey", MOD_ID)
                }
            }
        } catch (e: Exception) {
            logger.error("Error sending X-ray alert to webhook", e)
        }
    }

    private fun sendNewWebhookMessage(webhookUrl: String, jsonPayload: String): String? {
        var attempts = 0
        while (attempts < 3) {
            try {
                val sendUrl = "$webhookUrl?wait=true"
                val url = URL(sendUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "EventAntiXray Mod")

                val out = connection.outputStream
                out.write(jsonPayload.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                out.close()

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().readText()
                    val json = Gson().fromJson(responseBody, JsonObject::class.java)
                    val messageId = json.get("id").asString
                    logger.info("Successfully sent X-ray alert to webhook, message ID: $messageId")
                    connection.disconnect()
                    return messageId
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.readText() ?: "No error message"
                    logger.error("Failed to send webhook. Response code: $responseCode. Error: $errorResponse")
                    connection.disconnect()
                }
            } catch (e: Exception) {
                logger.error("Error sending new webhook message, attempt ${attempts + 1}", e)
            }
            attempts++
            if (attempts < 3) Thread.sleep(2000)
        }
        logger.error("Failed to send webhook after 3 attempts")
        return null
    }

    private fun updateWebhookMessage(webhookUrl: String, messageId: String, jsonPayload: String) {
        var attempts = 0
        while (attempts < 3) {
            try {
                val editUrl = if (webhookUrl.contains("/api/webhooks/")) {
                    "${webhookUrl}/messages/${messageId}"
                } else {
                    "https://discord.com/api/webhooks/${webhookUrl}/messages/${messageId}"
                }

                val client = HttpClient.newHttpClient()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(editUrl))
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "EventAntiXray Mod")
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                val responseCode = response.statusCode()

                if (responseCode in 200..299) {
                    logger.info("Successfully updated X-ray alert webhook message")
                    return
                } else {
                    val errorResponse = response.body()
                    logger.error("Failed to update webhook. Response code: $responseCode. Error: $errorResponse")

                    if (responseCode == 404) {
                        logger.warn("Webhook message not found (ID: $messageId)")
                        val keysToRemove = webhookMessageCache.filterValues { it.messageId == messageId }.keys
                        keysToRemove.forEach { webhookMessageCache.remove(it) }
                        LogDebug.debug("Removed message ID $messageId from cache for keys: $keysToRemove", MOD_ID)
                        return
                    }
                }
            } catch (e: Exception) {
                logger.error("Error updating webhook message, attempt ${attempts + 1}", e)
            }

            attempts++
            if (attempts < 3) Thread.sleep(2000)
        }

        logger.error("Failed to update webhook after 3 attempts")
    }
}