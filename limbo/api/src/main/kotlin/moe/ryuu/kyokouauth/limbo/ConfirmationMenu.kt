package moe.ryuu.kyokouauth.limbo

import moe.ryuu.kyokouauth.limbo.protocol.TextComponent
import java.util.UUID

/**
 * A server-authoritative two-choice confirmation dialog in the limbo.
 *
 * Rendered through the existing two-slot container protocol with fixed option IDs ([CONFIRM_ID],
 * [CANCEL_ID]) and non-zero presentation-only placeholder UUIDs that carry no identity or
 * permission meaning. Each prompt or refresh must supply a fresh [generation].
 */
data class ConfirmationMenu(
    val generation: UUID,
    val title: TextComponent,
    val confirmLabel: TextComponent,
    val cancelLabel: TextComponent,
    val confirmLore: List<TextComponent> = emptyList(),
    val cancelLore: List<TextComponent> = emptyList(),
) {
    init {
        require(confirmLore.size <= MAX_LORE_LINES && cancelLore.size <= MAX_LORE_LINES) {
            "Confirmation lore must contain at most $MAX_LORE_LINES lines"
        }
    }

    internal fun toRoleSelectorMenu(): RoleSelectorMenu = RoleSelectorMenu(
        generation = generation,
        title = title,
        microsoft = RoleSelectorItem(
            optionId = CONFIRM_ID,
            profileUuid = CONFIRM_PLACEHOLDER_UUID,
            profileName = "Confirm",
            textureValue = null,
            textureSignature = null,
            name = confirmLabel,
            lore = confirmLore.toList(),
            icon = RoleSelectorIcon.DIAMOND,
        ),
        premium = RoleSelectorItem(
            optionId = CANCEL_ID,
            profileUuid = CANCEL_PLACEHOLDER_UUID,
            profileName = "Cancel",
            textureValue = null,
            textureSignature = null,
            name = cancelLabel,
            lore = cancelLore.toList(),
            icon = RoleSelectorIcon.NAME_TAG,
        ),
    )

    companion object {
        const val CONFIRM_ID: String = "confirm"
        const val CANCEL_ID: String = "cancel"
        const val MAX_LORE_LINES: Int = 8

        /** Non-zero UI-only placeholder UUIDs; never treated as player or profile identities. */
        val CONFIRM_PLACEHOLDER_UUID: UUID = UUID(0L, 1L)
        val CANCEL_PLACEHOLDER_UUID: UUID = UUID(0L, 2L)
    }
}
