package noammaddons.features.impl.dungeons

import gg.essential.elementa.utils.withAlpha
import net.minecraft.client.gui.inventory.GuiChest
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.item.ItemSkull
import net.minecraft.util.EnumParticleTypes
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import noammaddons.NoammAddons.Companion.CHAT_PREFIX
import noammaddons.config.EditGui.GuiElement
import noammaddons.config.EditGui.HudEditorScreen
import noammaddons.events.*
import noammaddons.features.Feature
import noammaddons.mixins.AccessorGuiContainer
import noammaddons.ui.config.core.impl.SeperatorSetting
import noammaddons.ui.config.core.impl.ToggleSetting
import noammaddons.utils.ChatUtils.modMessage
import noammaddons.utils.ChatUtils.removeFormatting
import noammaddons.utils.ChatUtils.sendPartyMessage
import noammaddons.utils.GuiUtils.changeTitle
import noammaddons.utils.GuiUtils.currentChestName
import noammaddons.utils.GuiUtils.getSlotFromIndex
import noammaddons.utils.ItemUtils.SkyblockID
import noammaddons.utils.ItemUtils.enchantNameToID
import noammaddons.utils.ItemUtils.getEssenceValue
import noammaddons.utils.ItemUtils.getIdFromName
import noammaddons.utils.ItemUtils.getItemId
import noammaddons.utils.ItemUtils.idToEnchantName
import noammaddons.utils.ItemUtils.lore
import noammaddons.utils.LocationUtils.WorldType.*
import noammaddons.utils.LocationUtils.world
import noammaddons.utils.NumbersUtils.format
import noammaddons.utils.NumbersUtils.romanToDecimal
import noammaddons.utils.RenderHelper.getStringHeight
import noammaddons.utils.RenderHelper.getStringWidth
import noammaddons.utils.RenderHelper.highlight
import noammaddons.utils.RenderUtils.drawCenteredText
import noammaddons.utils.RenderUtils.drawText
import noammaddons.utils.ThreadUtils.setTimeout
import noammaddons.utils.Utils.equalsOneOf
import noammaddons.utils.Utils.remove
import noammaddons.utils.WebUtils.fetchJsonWithRetry
import java.awt.Color
import java.lang.Math.*

object ChestProfit: Feature("Dungeon Chest Profit Calculator and Croesus Overlay") {
    private const val url = "https://raw.githubusercontent.com/Noamm9/NoammAddons/refs/heads/data/DungeonChestProfit"
    private val croesusChestRegex = Regex("^(Master Mode )?The Catacombs - Flo(or (IV|V?I{0,3}))?$")
    private val chestsToHighlight = mutableListOf<DungeonChest>()
    private val rngList = mutableListOf<String>()
    private val blackList = mutableListOf<String>()
    private var newName: String? = null

    private val hud = ToggleSetting("HUD Display")

    private val sortByProfit = ToggleSetting("Sort by Profit")
    val includeEssence = ToggleSetting("Includes Essence")
    private val rng = ToggleSetting("RNG Announcer")

    private val croesusChestsProfit = ToggleSetting("Croesus Chests Profit")
    private val croesusChestHighlight = ToggleSetting("Highlight Croesus Chests")
    private val hideRedChests = ToggleSetting("Hide Opened Chests").addDependency(croesusChestHighlight)

    override fun init() {
        addSettings(
            hud,
            sortByProfit, includeEssence, rng,
            SeperatorSetting("Croesus"),
            croesusChestsProfit,
            croesusChestHighlight,
            hideRedChests
        )

        fetchJsonWithRetry<List<String>>("$url/RNG_BLACKLIST.json") {
            blackList.clear()
            blackList.addAll(it)
        }

        fetchJsonWithRetry<List<String>>("$url/RNG_List.json") {
            rngList.clear()
            rngList.addAll(it)
        }
    }

    @SubscribeEvent
    fun onWorldUnload(event: WorldUnloadEvent) {
        DungeonChest.entries.forEach { it.reset() }
    }

    @SubscribeEvent
    fun onInventory(event: InventoryFullyOpenedEvent) {
        if (! world.equalsOneOf(DungeonHub, Catacombs)) return
        val chestName = event.title.removeFormatting()

        if (chestName.matches(croesusChestRegex)) {
            if (chestsToHighlight.isEmpty()) {
                DungeonChest.entries.forEach { it.reset() }
            }
        }
        else if (chestName.endsWith(" Chest")) {
            DungeonChest.getFromName(chestName)?.reset()
        }

        if (chestsToHighlight.isNotEmpty() && ! chestName.matches(croesusChestRegex)) {
            chestsToHighlight.clear()
        }

        newName?.let {
            changeTitle(it)
            newName = null
        }

        when {
            chestName.endsWith(" Chest") -> {
                val currentDungeonChest = DungeonChest.getFromName(chestName) ?: return
                if (event.items[31] == null) return

                val l = event.items[31] !!.lore.map { it.removeFormatting() }
                val i = l.indexOf("Cost")
                if (i == - 1) return

                var calculatedProfit = getChestPrice(l) * - 1

                for (obj in event.items) {
                    if (obj.value?.getItemId() == 160) continue

                    val itemId = obj.value.SkyblockID
                    val itemName = obj.value?.displayName

                    if (itemId == "ENCHANTED_BOOK") {
                        val bookName = obj.value !!.lore[0]
                        val bookID = enchantNameToID(bookName)
                        calculatedProfit += getPrice(bookID, currentDungeonChest)
                    }

                    itemName?.let { name -> getEssenceValue(name).takeIf { it != .0 } }?.let { essance ->
                        calculatedProfit += essance.toInt()
                    }

                    calculatedProfit += (itemId?.let { getPrice(it, currentDungeonChest) } ?: 0)
                }
                currentDungeonChest.profit = calculatedProfit
                currentDungeonChest.openedInSequence = true
            }

            chestName.matches(croesusChestRegex) -> {

                for (i in 10 .. 16) {
                    val item = event.items[i] ?: continue
                    if (item.getItemId() == 160) continue
                    val chestTypeEnum = DungeonChest.getFromName(item.displayName.removeFormatting()) ?: continue

                    val lore = item.lore
                    val contentIndex = lore.indexOf("§7Contents")
                    if (contentIndex == - 1) continue

                    chestTypeEnum.slot = i
                    var calculatedProfit = getChestPrice(lore) * - 1

                    lore.drop(contentIndex + 1).takeWhile { it != "" }.forEach { drop ->
                        val value = when (drop.contains("Essence")) {
                            true -> getEssenceValue(drop).toInt()
                            else -> getPrice(getIdFromName(drop) ?: return@forEach, chestTypeEnum)
                        }
                        calculatedProfit += value
                    }
                    chestTypeEnum.profit = calculatedProfit
                    chestTypeEnum.openedInSequence = true

                    if (! chestsToHighlight.any { it.displayText == chestTypeEnum.displayText }) chestsToHighlight.add(chestTypeEnum)
                    else chestsToHighlight.find { it.displayText == chestTypeEnum.displayText }?.profit = calculatedProfit
                }
            }
        }
    }

    @SubscribeEvent
    fun drawProfit(event: GuiScreenEvent.BackgroundDrawnEvent) {
        if (event.gui !is GuiChest) return
        if (! world.equalsOneOf(DungeonHub, Catacombs)) return

        val gui = (event.gui as AccessorGuiContainer)
        val (x, y) = gui.guiLeft.toFloat() to gui.guiTop.toFloat()
        val (width, height) = gui.width.toFloat() to gui.height.toFloat()

        GlStateManager.pushMatrix()
        GlStateManager.translate(x, y, 100f)

        when {
            currentChestName.removeFormatting().matches(croesusChestRegex) -> {
                // sry for horrible code but if it works, don't touch it lol @Noamm9
                val sortedNumbers = chestsToHighlight.sortedByDescending { it.profit }.toMutableList()
                val sortedNumbersV2 = sortedNumbers
                val toHighlight = sortedNumbers.take(2).toMutableList()

                val toRemove = sortedNumbers.filter { dungeonChest ->
                    getSlotFromIndex(dungeonChest.slot)
                        ?.stack?.lore?.any {
                            it.contains("§aAlready opened")
                        } ?: false
                }

                sortedNumbers.removeAll(toRemove.toSet())
                toHighlight.removeAll(toRemove.toSet())

                (if (sortByProfit.value) sortedNumbers else chestsToHighlight.filterNot { it in toRemove })
                    .forEachIndexed { index, it ->
                        val str = it.displayText + ": " + (if (it.profit < 0) "§4" else "§a") + format(it.profit) + "§r"

                        drawText(
                            str,
                            width * 1.15f,
                            index * 9f + height / 6f,
                            1f, it.color
                        )
                    }

                if (toRemove.isNotEmpty()) {
                    if (sortedNumbersV2.any { getSlotFromIndex(it.slot)?.stack?.lore?.contains("§cCan't open another chest!") == true }) {
                        changeTitle("", event.gui)
                        drawCenteredText("&4&l&nCan't open another chest!", width / 2f, height / 40)
                    }

                    toRemove.forEach {
                        getSlotFromIndex(it.slot)?.highlight(Color.RED)
                    }
                }

                toHighlight.forEachIndexed { i, chest ->
                    if (chest.profit < 0) return@forEachIndexed
                    getSlotFromIndex(chest.slot)?.highlight(
                        if (i == 0) Color.GREEN
                        else Color.GREEN.darker().darker().darker()
                    )
                }
            }

            currentChestName.endsWith(" Chest§r") -> {
                val lastProfit = DungeonChest.getFromName(currentChestName.removeFormatting())
                if (lastProfit != null) {
                    val profitText = "${if (lastProfit.profit < 0) "§4" else "§a"}${format(lastProfit.profit)}"
                    val str = "Profit: $profitText  "
                    drawText(str, width - getStringWidth(str), + 6f)
                }
            }
        }

        GlStateManager.popMatrix()
    }

    @SubscribeEvent
    fun onDrawSlot(event: DrawSlotEvent) {
        if (! croesusChestHighlight.value) return
        if (! world.equalsOneOf(DungeonHub, Catacombs)) return
        if (event.gui !is GuiChest) return
        if (event.slot.inventory == mc.thePlayer.inventory) return
        val stack = event.slot.stack ?: return
        if (stack.item !is ItemSkull) return
        val name = stack.displayName
        if (! name.equalsOneOf("§cThe Catacombs", "§cMaster Mode The Catacombs")) return
        val lore = stack.lore

        event.slot.highlight(
            when {
                lore.any { line -> line == "§aNo more Chests to open!" } -> {
                    if (! hideRedChests.value) Color.RED
                    else {
                        event.isCanceled = true
                        return
                    }


                }

                lore.any { line -> line == "§8No Chests Opened!" } -> Color.GREEN
                lore.any { line -> line.startsWith("§8Opened Chest: ") } -> Color.YELLOW
                else -> return
            }.withAlpha(100)
        )
    }

    @SubscribeEvent
    fun onGuiClick(event: SlotClickEvent) {
        if (! croesusChestsProfit.value) return
        if (event.gui !is GuiChest) return
        if (event.slot?.inventory == mc.thePlayer.inventory) return
        val stack = event.slot?.stack ?: return
        if (stack.item !is ItemSkull) return
        val name = stack.displayName
        if (! name.equalsOneOf("§cThe Catacombs", "§cMaster Mode The Catacombs")) return

        val floor = stack.lore[0].removeFormatting().substringAfterLast(" ").romanToDecimal()
        val title = if (name.startsWith("§cMaster")) "§c§lMaster Mode" else "§a§lFloor"

        newName = "$title §e§l$floor"
    }

    private fun getChestPrice(lore: List<String>): Int {
        lore.forEach {
            val line = it.removeFormatting()
            if (line.contains("FREE")) {
                return 0
            }
            if (line.contains(" Coins")) {
                return line.substring(0, line.indexOf(" ")).remove(",").toInt()
            }
        }
        return 0
    }

    private fun getPrice(id: String, chestContextName: DungeonChest): Int {
        if (id in blackList) return 0

        val price = when {
            bzData.containsKey(id) -> bzData[id] !!.sellPrice.toInt()
            ahData.containsKey(id) -> ahData[id] !!.toInt()
            else -> 0
        }

        setTimeout(200) { rng(id, chestContextName) }

        return price
    }

    private fun rng(id: String, chestContextName: DungeonChest) {
        if (! chestContextName.openedInSequence) return
        if (id !in rngList) return

        val profitString = "${if (chestContextName.profit < 0) "§4" else "§a"}${format(chestContextName.profit)}"
        val str = "&6${itemIdToNameLookup[id] ?: idToEnchantName(id)}&f (from ${chestContextName.displayText}): $profitString"

        if (rng.value) sendPartyMessage("$CHAT_PREFIX $str")
        modMessage(str)

        mc.thePlayer.playSound("note.pling", 50f, 1.22f)
        setTimeout(120) { mc.thePlayer.playSound("note.pling", 50f, 1.13f) }
        setTimeout(240) { mc.thePlayer.playSound("note.pling", 50f, 1.29f) }
        setTimeout(400) { mc.thePlayer.playSound("note.pling", 50f, 1.60f) }

        repeat(70) {
            val multiX = if (random() <= 0.5) - 2 else 2
            val multiZ = if (random() <= 0.5) - 2 else 2
            mc.theWorld.spawnParticle(
                EnumParticleTypes.HEART,
                mc.thePlayer.posX + random() * multiX,
                mc.thePlayer.posY + 0.5 + random(),
                mc.thePlayer.posZ + random() * multiZ,
                0.0, 1.0, 0.0
            )
        }
    }

    enum class DungeonChest(var displayText: String, var color: Color) {
        WOOD("Wood Chest", Color(100, 64, 1)),
        GOLD("Gold Chest", Color.YELLOW),
        DIAMOND("Diamond Chest", Color.CYAN),
        EMERALD("Emerald Chest", Color(0, 128, 0)),
        OBSIDIAN("Obsidian Chest", Color(128, 0, 128)),
        BEDROCK("Bedrock Chest", Color.DARK_GRAY);

        var slot = 0
        var profit = 0
        var openedInSequence: Boolean = false

        fun reset() {
            slot = 0
            profit = 0
            openedInSequence = false
        }

        companion object {
            fun getFromName(name: String?): DungeonChest? {
                if (name.isNullOrBlank()) return null
                return entries.find {
                    it.displayText == name
                }
            }
        }
    }

    private object ChestProfitHudElement: GuiElement(hudData.getData().chestProfitHud) {
        override val enabled get() = ChestProfit.enabled && hud.value
        override val width: Float get() = getStringWidth(text)
        override val height: Float get() = getStringHeight(text)

        private val text: List<String>
            get() {
                if (HudEditorScreen.isOpen()) return listOf(
                    "Wood Chest: §a75k", "Gold Chest: §4-62k",
                    "Diamond Chest: §a24k", "Emerald Chest: §4-442k",
                    "Obsidian Chest: §4-624k", "Bedrock Chest: §a5m"
                )
                var openedChests = DungeonChest.entries.filter { it.openedInSequence }
                if (sortByProfit.value) openedChests = openedChests.sortedByDescending { it.profit }
                if (openedChests.isEmpty()) return emptyList()

                return openedChests.map { chest ->
                    val profitColor = if (chest.profit < 0) "§4" else "§a"
                    val profit = format(chest.profit)
                    "${chest.displayText}: $profitColor$profit"
                }
            }

        override fun draw() {
            if (text.isEmpty()) return

            var chests = DungeonChest.entries.filter { it.openedInSequence }
            if (sortByProfit.value) chests = chests.sortedByDescending { it.profit }

            var currentY = getY()
            chests.forEachIndexed { i, c ->
                drawText(text[i], getX(), currentY, getScale(), c.color)
                currentY += 9f * getScale()
            }
        }

        override fun exampleDraw() {
            if (text.isEmpty()) return

            var currentY = getY()
            DungeonChest.entries.forEachIndexed { i, c ->
                drawText(text[i], getX(), currentY, getScale(), c.color)
                currentY += 9f * getScale()
            }
        }
    }

    @SubscribeEvent
    fun onRenderOverlay(event: RenderOverlay) {
        if (! ChestProfitHudElement.enabled) return
        if (! world.equalsOneOf(DungeonHub, Catacombs)) return
        if (DungeonChest.entries.none { it.openedInSequence }) return
        ChestProfitHudElement.draw()
    }
}