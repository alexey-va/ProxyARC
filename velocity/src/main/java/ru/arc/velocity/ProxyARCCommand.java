package ru.arc.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import lombok.RequiredArgsConstructor;
import ru.arc.CommonCore;
import ru.arc.config.ConfigManager;
import ru.arc.Utils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class ProxyARCCommand implements SimpleCommand {

    final CommonCore commonCore;

    @Override
    public void execute(Invocation invocation) {
        CommandSource commandSource = invocation.source();
        String[] args = invocation.arguments();
        if (!commandSource.hasPermission("arc.admin")) {
            commandSource.sendMessage(Utils.mm("У вас нет разрешения на использование этой команды"));
            return;
        }
        if (args.length == 0) {
            commandSource.sendMessage(Utils.mm("This is ProxyARC plugin for Velocity!"));
        } else {
            if (args[0].equalsIgnoreCase("reload")) {
                ConfigManager.reloadAll();
                commandSource.sendMessage(Utils.mm("Перезагрузка успешна!"));
            } else if (args[0].equalsIgnoreCase("resetai")) {
                commonCore.getJippityConversation().resetHistory();
            } else if (args[0].equalsIgnoreCase("cleardiscord")) {
                if (args.length != 3) {
                    commandSource.sendMessage(Utils.mm("Usage: /arc cleardiscord <channelId> start/stop"));
                    return;
                }
                String channelId = args[1];
                String action = args[2];
                if (action.equalsIgnoreCase("start"))
                    commonCore.getDiscordBot().clearChat(channelId);
                else if (action.equalsIgnoreCase("stop")) {
                    commonCore.getDiscordBot().stopClearTask(channelId);
                } else {
                    commandSource.sendMessage(Utils.mm("Usage: /arc cleardiscord <channelId> start/stop"));
                }

            } else {
                commandSource.sendMessage(Utils.mm("Unknown command!"));
            }
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("reload", "resetai", "cleardiscord");
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(List.of("reload", "resetai", "cleardiscord"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("arc.admin");
    }
}
