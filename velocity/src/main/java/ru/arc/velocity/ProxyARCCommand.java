package ru.arc.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import ru.arc.CommonCore;
import ru.arc.ConfigManager;
import ru.arc.Utils;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ProxyARCCommand implements SimpleCommand {

    @Override
    public void execute(Invocation invocation) {
        CommandSource commandSource = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            commandSource.sendMessage(Utils.mm("This is ProxyARC plugin for Velocity!"));
        } else if (args.length == 1) {
            if (args[0].equalsIgnoreCase("reload") && commandSource.hasPermission("arc.admin")) {
                ConfigManager.reloadAll();
                commandSource.sendMessage(Utils.mm("Перезагрузка успешна!"));
            }
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return List.of("reload");
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.completedFuture(List.of("reload"));
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("arc.admin");
    }
}
