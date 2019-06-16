import net.corda.core.serialization.SerializationWhitelist
import org.cordacodeclub.bluff.player.PlayerDatabaseService

class TemplateSerializationWhitelist : SerializationWhitelist {
    override val whitelist: List<Class<*>> = listOf(PlayerDatabaseService::class.java)
}