package net.firebreadmc.fbsmp.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

@Plugin(id = "fbsmp-proxy", name = "FB-SMP Proxy", version = "1.0.0",
        description = "FB-SMP Velocity proxy companion.")
public final class FbsmpProxyPlugin {
    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public FbsmpProxyPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        logger.info("FB-SMP proxy companion initialized for {} registered server(s).",
                server.getAllServers().size());
    }
}