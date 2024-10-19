package ru.arc.xserver;



import lombok.Getter;
import ru.arc.xserver.repos.RepoData;

import java.util.HashSet;
import java.util.Set;

@Getter
public class JoinMessages extends RepoData<JoinMessages> {

    String player;
    Set<String> joinMessages = new HashSet<>();
    Set<String> leaveMessages = new HashSet<>();
    long timestamp = System.currentTimeMillis();

    public JoinMessages(String player) {
        this.player = player;
        this.timestamp = System.currentTimeMillis();
    }

    public void addJoinMessage(String message) {
        joinMessages.add(message);
        setDirty(true);
    }

    public void removeJoinMessage(String message) {
        joinMessages.remove(message);
        setDirty(true);
    }

    public void addLeaveMessage(String message) {
        leaveMessages.add(message);
        setDirty(true);
    }

    public void removeLeaveMessage(String message) {
        leaveMessages.remove(message);
        setDirty(true);
    }

    @Override
    public String id() {
        return player;
    }

    @Override
    public boolean isRemove() {
        return System.currentTimeMillis() - timestamp > 1000 * 60 * 60 * 24 * 7 && joinMessages.isEmpty() && leaveMessages.isEmpty();
    }

    @Override
    public void merge(JoinMessages other) {
        joinMessages.clear();
        joinMessages.addAll(other.joinMessages);
        leaveMessages.clear();
        leaveMessages.addAll(other.leaveMessages);
    }
}
