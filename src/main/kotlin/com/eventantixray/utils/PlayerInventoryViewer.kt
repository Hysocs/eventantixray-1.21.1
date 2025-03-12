package com.eventantixray.utils

import net.minecraft.component.DataComponentTypes
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.inventory.Inventory
import net.minecraft.inventory.InventoryChangedListener
import net.minecraft.inventory.RecipeInputInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.PlayerScreenHandler
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.slot.Slot
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text

// Extension function for setting a custom name on an ItemStack.
fun ItemStack.setCustomName(name: Text) {
    this.set(DataComponentTypes.ITEM_NAME, name)
}

object PlayerInventoryViewer {
    // Cache slot coordinates to avoid repeated calculations
    private val SLOT_POSITIONS = Array(54) { i ->
        Pair(8 + (i % 9) * 18, 18 + (i / 9) * 18)
    }

    fun openInventoryViewer(viewer: ServerPlayerEntity, target: ServerPlayerEntity) {
        val factory = object : NamedScreenHandlerFactory {
            override fun createMenu(syncId: Int, playerInventory: PlayerInventory, player: PlayerEntity): ScreenHandler {
                return PlayerInventoryScreenHandler(syncId, playerInventory, target)
            }
            override fun getDisplayName(): Text = Text.literal("${target.name.string}'s Inventory")
        }
        viewer.openHandledScreen(factory)
    }

    class FixedLayoutProxyInventory(
        private val targetPlayer: ServerPlayerEntity,
        private val targetInventory: PlayerInventory
    ) : Inventory {
        private val listeners = mutableListOf<InventoryChangedListener>()

        // Cache the filler pane item
        private val FILLER_PANE by lazy {
            ItemStack(Items.GRAY_STAINED_GLASS_PANE).apply {
                setCustomName(Text.literal(" "))
            }
        }

        // Cache for crafting inventory to reduce lookups
        private var craftingInventoryCache: RecipeInputInventory? = null
        private var lastCacheTime: Long = 0
        private val CACHE_VALID_TIME = 500L // ms

        private fun getCraftingInventory(): RecipeInputInventory? {
            val currentTime = System.currentTimeMillis()

            // Only refresh cache if enough time has passed
            if (currentTime - lastCacheTime > CACHE_VALID_TIME) {
                val screenHandler = targetPlayer.currentScreenHandler
                craftingInventoryCache = if (screenHandler is PlayerScreenHandler) {
                    screenHandler.getCraftingInput()
                } else {
                    null
                }
                lastCacheTime = currentTime
            }

            return craftingInventoryCache
        }

        override fun clear() { /* Not needed for this viewer */ }
        override fun size() = 54

        // Optimized isEmpty by checking key slots first
        override fun isEmpty(): Boolean {
            // Check hotbar first (most likely to have items)
            for (i in 0..8) {
                if (!targetInventory.getStack(i).isEmpty) return false
            }

            // Check armor slots
            for (i in 0..3) {
                if (!targetInventory.armor.getOrNull(i)?.isEmpty!!) return false
            }

            // Check offhand
            if (!targetInventory.offHand.getOrNull(0)?.isEmpty!!) return false

            // Check main inventory
            for (i in 9 until targetInventory.size()) {
                if (!targetInventory.getStack(i).isEmpty) return false
            }

            return true
        }

        // Optimized getStack with slot type categorization
        override fun getStack(slot: Int): ItemStack {
            return when {
                // Filler panes (most common access pattern in UI)
                slot in 36..44 -> FILLER_PANE

                // Equipment slots
                slot in 0..4 -> getEquipmentStack(slot)

                // Crafting slots
                slot in 5..8 -> getCraftingStack(slot)

                // Main inventory
                slot in 9..35 -> getMainInventoryStack(slot)

                // Hotbar
                slot in 45..53 -> getHotbarStack(slot - 45)

                // Invalid slot
                else -> ItemStack.EMPTY
            }
        }

        // Helper methods to improve readability and performance
        private fun getEquipmentStack(slot: Int): ItemStack {
            return when (slot) {
                0 -> targetInventory.armor.getOrNull(3) ?: ItemStack.EMPTY // Helmet
                1 -> targetInventory.armor.getOrNull(2) ?: ItemStack.EMPTY // Chestplate
                2 -> targetInventory.armor.getOrNull(1) ?: ItemStack.EMPTY // Leggings
                3 -> targetInventory.armor.getOrNull(0) ?: ItemStack.EMPTY // Boots
                4 -> targetInventory.offHand.getOrNull(0) ?: ItemStack.EMPTY // Offhand
                else -> ItemStack.EMPTY
            }
        }

        private fun getCraftingStack(slot: Int): ItemStack {
            val craftingInv = getCraftingInventory() ?: return ItemStack.EMPTY
            val index = slot - 5
            return craftingInv.getStack(index).copy()
        }

        private fun getMainInventoryStack(slot: Int): ItemStack {
            return if (slot in 9 until targetInventory.size()) {
                targetInventory.getStack(slot)
            } else {
                ItemStack.EMPTY
            }
        }

        private fun getHotbarStack(index: Int): ItemStack {
            return if (index < 9) targetInventory.getStack(index) else ItemStack.EMPTY
        }

        override fun removeStack(slot: Int, amount: Int): ItemStack {
            val stack = getStack(slot)
            if (stack.isEmpty) return ItemStack.EMPTY
            val removed = stack.split(amount)
            setStack(slot, stack)
            return removed
        }

        override fun removeStack(slot: Int): ItemStack {
            val stack = getStack(slot)
            setStack(slot, ItemStack.EMPTY)
            return stack
        }

        // Optimized setStack with direct slot type handling
        override fun setStack(slot: Int, stack: ItemStack) {
            when {
                // Armor and offhand slots
                slot in 0..4 -> setEquipmentStack(slot, stack)

                // Crafting slots
                slot in 5..8 -> setCraftingStack(slot, stack)

                // Main inventory slots
                slot in 9..35 -> setMainInventoryStack(slot, stack)

                // Filler slots - no-op for better performance
                slot in 36..44 -> { /* Do nothing */ }

                // Hotbar slots
                slot in 45..53 -> setHotbarStack(slot - 45, stack)
            }
        }

        // Helper methods for setStack
        private fun setEquipmentStack(slot: Int, stack: ItemStack) {
            when (slot) {
                0 -> if (targetInventory.armor.size > 3) targetInventory.armor[3] = stack
                1 -> if (targetInventory.armor.size > 2) targetInventory.armor[2] = stack
                2 -> if (targetInventory.armor.size > 1) targetInventory.armor[1] = stack
                3 -> if (targetInventory.armor.isNotEmpty()) targetInventory.armor[0] = stack
                4 -> if (targetInventory.offHand.isNotEmpty()) targetInventory.offHand[0] = stack
            }
            targetInventory.markDirty()
        }

        private fun setCraftingStack(slot: Int, stack: ItemStack) {
            val craftingInv = getCraftingInventory()
            if (craftingInv != null) {
                val index = slot - 5
                craftingInv.setStack(index, stack)
                craftingInv.markDirty()
            }
        }

        private fun setMainInventoryStack(slot: Int, stack: ItemStack) {
            if (slot in 9 until targetInventory.size()) {
                targetInventory.setStack(slot, stack)
                targetInventory.markDirty()
            }
        }

        private fun setHotbarStack(index: Int, stack: ItemStack) {
            if (index < 9) {
                targetInventory.setStack(index, stack)
                targetInventory.markDirty()
            }
        }

        override fun markDirty() {
            targetInventory.markDirty()
            // Avoid creating iterator for single listener case
            if (listeners.size == 1) {
                listeners[0].onInventoryChanged(this)
            } else if (listeners.size > 1) {
                listeners.forEach { it.onInventoryChanged(this) }
            }
        }

        override fun canPlayerUse(player: PlayerEntity) = true

        fun addListener(listener: InventoryChangedListener) {
            if (listener !in listeners) {
                listeners.add(listener)
            }
        }
    }

    class PlayerInventoryScreenHandler(
        syncId: Int,
        private val viewerInventory: PlayerInventory,
        private val targetPlayer: ServerPlayerEntity
    ) : ScreenHandler(ScreenHandlerType.GENERIC_9X6, syncId) {

        private val proxyInventory = FixedLayoutProxyInventory(targetPlayer, targetPlayer.inventory)

        // Cache for slot validation
        private val slotTypeCache = IntArray(90) {
            when (it) {
                in 0..35 -> 1  // Target inventory slots
                in 36..44 -> 2  // Filler slots
                in 45..53 -> 3  // Target hotbar slots
                in 54..89 -> 4  // Viewer inventory slots
                else -> 0       // Invalid
            }
        }

        init {
            setupTargetInventorySlots()
            setupViewerInventorySlots()

            // Listener for proxy inventory changes
            proxyInventory.addListener(object : InventoryChangedListener {
                override fun onInventoryChanged(inventory: Inventory) {
                    sendContentUpdates()
                }
            })
        }

        // Split initialization for better organization and performance
        private fun setupTargetInventorySlots() {
            for (i in 0 until 54) {
                val (x, y) = SLOT_POSITIONS[i]
                val slot = when (i) {
                    in 36..44 -> {
                        object : Slot(proxyInventory, i, x, y) {
                            // Fast-track slot checking
                            override fun canInsert(stack: ItemStack) = false
                            override fun canTakeItems(player: PlayerEntity) = false
                        }
                    }
                    else -> {
                        Slot(proxyInventory, i, x, y)
                    }
                }
                addSlot(slot)
            }
        }

        private fun setupViewerInventorySlots() {
            val playerInvStartX = 8
            val playerInvStartY = 140

            // Viewer's main inventory (slots 54-80)
            for (row in 0 until 3) {
                for (col in 0 until 9) {
                    val index = col + row * 9 + 9
                    addSlot(Slot(viewerInventory, index, playerInvStartX + col * 18, playerInvStartY + row * 18))
                }
            }

            // Viewer's hotbar (slots 81-89)
            val hotbarY = playerInvStartY + 58
            for (col in 0 until 9) {
                addSlot(Slot(viewerInventory, col, playerInvStartX + col * 18, hotbarY))
            }
        }

        override fun canUse(player: PlayerEntity) = true

        // Optimized slot click handling
        override fun onSlotClick(slotIndex: Int, button: Int, actionType: SlotActionType, player: PlayerEntity) {
            // Handle clicks outside the inventory (slotIndex == -999)
            if (slotIndex == -999) {
                super.onSlotClick(slotIndex, button, actionType, player)
                return
            }

            // Quick rejection for filler slots
            if (slotIndex in 36..44) return

            // Use cached slot types for faster processing
            val slotType = if (slotIndex >= 0 && slotIndex < slotTypeCache.size) slotTypeCache[slotIndex] else 0

            // Process based on slot type
            if (slotType > 0) {
                super.onSlotClick(slotIndex, button, actionType, player)
            }
        }

        // Optimized quick move to reject invalid operations immediately
        override fun quickMove(player: PlayerEntity, index: Int): ItemStack {
            // Filler slots can never be quick moved
            if (index in 36..44) return ItemStack.EMPTY

            // Other slots could potentially implement quick move logic here
            return ItemStack.EMPTY
        }
    }
}