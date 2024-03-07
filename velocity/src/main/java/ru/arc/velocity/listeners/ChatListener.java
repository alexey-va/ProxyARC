package ru.arc.velocity.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.RequiredArgsConstructor;
import ru.arc.CommonCore;
import ru.arc.Config;
import ru.arc.Utils;

import java.util.prefs.PreferenceChangeEvent;

@RequiredArgsConstructor
public class ChatListener {

    final CommonCore commonCore;
    final ProxyServer proxyServer;
    final Config jippityConfig;

    @Subscribe(async = true)
    public void onChatMessage(PlayerChatEvent event) {
        if (!event.getResult().isAllowed()) return;
        if (!event.getMessage().startsWith("!")) return;
        commonCore.getChatHistory().add(event.getPlayer().getUsername(), event.getMessage());
        if (event.getMessage().contains(jippityConfig.string("jippity-detect-string", "ии"))) {
            commonCore.getJippityConversation().sendOpenaiApiRequest(event.getPlayer().getUsername() + ": " + event.getMessage())
                    .thenAccept(resp -> {
                        proxyServer.sendMessage(resp);
                        commonCore.getBot().sendChatMessage(Utils.plain(resp).replace(
                                jippityConfig.string("jippity-name", "ИИ"),
                                "**" + jippityConfig.string("jippity-name", "ИИ") + "**")
                        );
                    });
            System.out.println("Finished event processing");
        }
    }

}
