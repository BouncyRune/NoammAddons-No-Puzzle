package noammaddons.features.impl.alerts

import noammaddons.features.Feature
import noammaddons.noammaddons.Companion.CHAT_PREFIX
import noammaddons.ui.config.core.impl.TextInputSetting
import noammaddons.ui.config.core.impl.ToggleSetting
import noammaddons.utils.ChatUtils
import noammaddons.utils.TablistListener

object CryptsDone: Feature() {
    private var alerted = false

    private val showTitle = ToggleSetting("Show Title", true).register1()
    private val title = TextInputSetting("Title", "&aCrypts Done!").addDependency(showTitle).register1()
    private val sendMessage = ToggleSetting("Send Message", true).register1()
    private val message = TextInputSetting("Message", "$CHAT_PREFIX Crypts Done!").addDependency(sendMessage).register1()

    init {
        TablistListener.cryptsCount.onSetValue { cryptsCount ->
            if (! enabled) return@onSetValue
            if (cryptsCount == 0) alerted = false
            if (cryptsCount < 5) return@onSetValue
            if (alerted) return@onSetValue
            alerted = true
            repeat(2) { mc.thePlayer.playSound("note.pling", 1F, 1.79F) }
            if (showTitle.value) ChatUtils.showTitle(title.value)
            if (sendMessage.value) ChatUtils.sendPartyMessage(message.value)
        }
    }
}
