package moe.ryuu.kyokouauth.limbo.session

import moe.ryuu.kyokouauth.limbo.LimboSettings
import org.geysermc.mcprotocollib.network.Session
import org.geysermc.mcprotocollib.protocol.data.game.chat.numbers.BlankFormat
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ObjectiveAction
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreType
import org.geysermc.mcprotocollib.protocol.data.game.scoreboard.ScoreboardPosition
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetDisplayObjectivePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetObjectivePacket
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.scoreboard.ClientboundSetScorePacket

internal object SidebarPackets {
    private const val OBJECTIVE = "kyokouauth_limbo"

    fun show(session: Session, sidebar: LimboSettings.Sidebar) {
        session.send(ClientboundSetObjectivePacket(OBJECTIVE, ObjectiveAction.ADD, sidebar.title.toLibrary(),
            ScoreType.INTEGER, BlankFormat.INSTANCE))
        sidebar.lines.forEachIndexed { index, line ->
            // Vanilla sorts descending by score; independent holders preserve duplicate/blank lines.
            session.send(ClientboundSetScorePacket("ka_limbo_$index", OBJECTIVE, sidebar.lines.size - index)
                .withDisplay(line.toLibrary()))
        }
        session.send(ClientboundSetDisplayObjectivePacket(ScoreboardPosition.SIDEBAR, OBJECTIVE))
    }

    fun remove(session: Session) {
        // Removing the objective also removes its scores and display slot on the client.
        session.send(ClientboundSetObjectivePacket(OBJECTIVE))
    }
}
