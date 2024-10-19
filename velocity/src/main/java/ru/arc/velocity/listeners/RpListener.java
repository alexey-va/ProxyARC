package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.ProxyServer;
import de.themoep.resourcepacksplugin.velocity.events.ResourcePackSendEvent;
import lombok.RequiredArgsConstructor;
import ru.arc.CommonCore;
import ru.arc.config.Config;

@RequiredArgsConstructor
public class RpListener {

    public final CommonCore commonCore;
    public final ProxyServer proxyServer;
    public final Config config;

    @Subscribe
    public void onRp(ResourcePackSendEvent event) {
        System.out.println("Resource pack event "+event.getPlayerId()+" "+event.getResult());
    }

}
