package ru.arc.xserver.repos;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
@Getter
public abstract class BukkitRunnable implements Runnable{

    ScheduledExecutorService service = Executors.newScheduledThreadPool(8);
    ScheduledFuture<?> future;
    boolean cancelled = false;

    public BukkitRunnable runTaskLater(long delayTicks) {
        service.schedule(this, delayTicks*50, MILLISECONDS);
        return this;
    }

    public BukkitRunnable runTaskTimer(long delayTicks, long periodTicks) {
        future = service.scheduleAtFixedRate(this, delayTicks*50, periodTicks*50, MILLISECONDS);
        return this;
    }

    public void cancel() {
        cancelled = true;
        future.cancel(false);
    }
}
