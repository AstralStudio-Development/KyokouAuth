package moe.ryuu.kyokouauth.bukkit.inject

import io.netty.channel.Channel
import moe.ryuu.kyokouauth.core.skin.Skin

/**
 * Replaces the skin on a Bedrock connection, in the one window where it can still take effect.
 *
 * ## Why the profile cannot be set through Bukkit
 *
 * Floodgate constructs the game profile itself and hands the packets straight to the login handler,
 * so a profile assigned to `AsyncPlayerPreLoginEvent` is discarded on this path - verified by logging:
 * the event carried the Java UUID and the joined player still had the Bedrock one. The skin goes the
 * same way, which is why a linked Bedrock player shows the default texture even though the Java
 * account has a real one.
 *
 * ## Where the skin actually lives
 *
 * Not in a `GameProfile` field on the pipeline handler: on Paper that handler is
 * `net.minecraft.network.Connection`, and dumping its fields showed `spoofedUUID` and
 * `spoofedProfile: Property[]` - the property array the login flow reads textures from. That array is
 * the documented place for a proxy to declare a player's properties, which is exactly this
 * situation: something other than the client is deciding who this connection is.
 *
 * ## Failure is silent by design
 *
 * Every step is reflective and every step may fail - the field may be renamed, the array may be
 * replaced later. None of that should stop a player from joining, so a failure here means "no skin",
 * never "no login".
 */
internal object BedrockProfilePatcher {

    private const val TEXTURES = "textures"
    private const val SPOOFED_PROFILE = "spoofedProfile"
    private const val PROPERTY_TYPE = "com.mojang.authlib.properties.Property"

    /**
     * @return true when the skin was applied, false when the field could not be reached or
     *   modified - in which case the connection keeps whatever skin it already had.
     */
    fun patch(channel: Channel, skin: Skin, logger: (String) -> Unit = {}): Boolean = try {
        val connection = channel.pipeline().get("packet_handler")
        if (connection == null) {
            logger("no packet_handler on the pipeline")
            false
        } else {
            val field = findField(connection.javaClass, SPOOFED_PROFILE)
            if (field == null) {
                logger("no $SPOOFED_PROFILE on ${connection.javaClass.name}")
                false
            } else {
                val propertyType = Class.forName(PROPERTY_TYPE)
                val property = propertyType
                    .getConstructor(String::class.java, String::class.java, String::class.java)
                    .newInstance(TEXTURES, skin.value, skin.signature)

                val array = java.lang.reflect.Array.newInstance(propertyType, 1)
                java.lang.reflect.Array.set(array, 0, property)

                field.isAccessible = true
                field.set(connection, array)
                true
            }
        }
    } catch (failure: Throwable) {
        logger("skin patch failed: ${failure.javaClass.simpleName}: ${failure.message}")
        false
    }

    /** Field lookup up the hierarchy: the field may be declared on a superclass after a refactor. */
    private fun findField(type: Class<*>, name: String): java.lang.reflect.Field? {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }
}
