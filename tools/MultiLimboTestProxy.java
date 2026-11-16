import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.util.GameProfile;
import moe.ryuu.kyokouauth.limbo.*;
import moe.ryuu.kyokouauth.limbo.protocol.TextComponent;
import org.slf4j.Logger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Isolated, offline, loopback-only library E2E fixture; never contacts identity/auth services. */
public final class MultiLimboTestProxy {
    private final ProxyServer proxy;
    private final Logger logger;
    private LimboServer limbo;
    @Inject public MultiLimboTestProxy(ProxyServer proxy, Logger logger) { this.proxy=proxy; this.logger=logger; }
    @Subscribe public void initialize(ProxyInitializeEvent event) throws Exception {
        byte[] secret=(byte[])proxy.getConfiguration().getClass().getMethod("getForwardingSecret").invoke(proxy.getConfiguration());
        limbo=new LimboServer(new LimboSettings(),new LimboListener(){
            public void onJoin(LimboPlayer player) {
                logger.info("ORIGINAL_CLIENT_READY {} {}",player.getName(),player.getConnectionId());
                player.sendMessage(new TextComponent("验证 𠀋 Oracle link","#22d3ee",true,null,true,null,
                    new TextComponent.Click.OpenUrl("https://example.invalid/limbo-oracle?code=public-fixture"),List.of()));
                player.showTitle(new TextComponent("Oracle title","gold",true,null,null,null,null,List.of()),
                    new TextComponent("Oracle subtitle","gray",null,null,null,null,null,List.of()),10,1200,10);
                logger.info("TEST_TITLE_ONCE {}",player.getName());
            }
            public void onLeave(LimboPlayer player) {logger.info("TEST_CLIENT_LEFT {}",player.getName());}
        },(text,failure)->logger.info(text),new InetSocketAddress("127.0.0.1",0),secret);
        limbo.startAll().forEach((protocol,address)->proxy.registerServer(new ServerInfo("test-limbo-"+protocol,address)));
        logger.info("LIMBO_TEST_READY protocols=767..777");
    }
    @Subscribe public void prelogin(PreLoginEvent event) {
        if(!event.getConnection().getRemoteAddress().getAddress().isLoopbackAddress())
            event.setResult(PreLoginEvent.PreLoginComponentResult.denied(net.kyori.adventure.text.Component.text("Local test only")));
        else event.setResult(PreLoginEvent.PreLoginComponentResult.forceOfflineMode());
    }
    @Subscribe public void profile(GameProfileRequestEvent event) {
        List<GameProfile.Property> properties=new ArrayList<>(event.getGameProfile().getProperties());
        properties.removeIf(p->p.getName().equals("kyokouauth:connection-id"));
        properties.add(new GameProfile.Property("kyokouauth:connection-id",event.getConnection().getSessionId().toString(),""));
        event.setGameProfile(event.getGameProfile().withProperties(properties));
    }
    @Subscribe public void initial(PlayerChooseInitialServerEvent event) {
        event.setInitialServer(proxy.getServer("test-limbo-"+event.getPlayer().getProtocolVersion().getProtocol()).orElseThrow());
    }
    @Subscribe public void connect(ServerPreConnectEvent event) {
        String target="test-limbo-"+event.getPlayer().getProtocolVersion().getProtocol();
        if(!event.getOriginalServer().getServerInfo().getName().equals(target)) event.setResult(ServerPreConnectEvent.ServerResult.denied());
    }
    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        if(limbo!=null)limbo.close();
        for(int protocol=767;protocol<=777;protocol++)proxy.getServer("test-limbo-"+protocol).ifPresent(server->proxy.unregisterServer(server.getServerInfo()));
    }
}
