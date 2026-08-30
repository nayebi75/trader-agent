package org.agent;

import lombok.extern.slf4j.Slf4j;
import org.agent.service.SignalChecker;
import org.agent.service.SignalDetector;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class TraderAgent {

    private static final Duration SIGNAL_DETECTOR_INTERVAL = Duration.ofMinutes(10);
    private static final Duration SIGNAL_CHECKER_INTERVAL = Duration.ofMinutes(5);
    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(15);

    private static ScheduledExecutorService signalDetectorScheduler;
    private static ScheduledExecutorService signalCheckerScheduler;

    public static void main(String[] args) {
        logApplicationStartup();
        registerShutdownHook();

        signalDetectorScheduler = createScheduler("signal-detector-thread");
        signalCheckerScheduler = createScheduler("signal-checker-thread");

        scheduleTask(signalDetectorScheduler, new SignalDetector(), SIGNAL_DETECTOR_INTERVAL, "SignalDetector");

        scheduleTask(signalCheckerScheduler, new SignalChecker(), SIGNAL_CHECKER_INTERVAL, "SignalChecker");
    }

    private static void logApplicationStartup() {
        try {
            String ipAddress = InetAddress.getLocalHost().getHostAddress();
            log.info("TraderAgent started successfully. IP: {}", ipAddress);
        } catch (Exception e) {
            log.warn("Application started, but local IP address could not be resolved", e);
        }
    }

    private static ScheduledExecutorService createScheduler(String threadName) {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, threadName);

            thread.setDaemon(false);

            thread.setUncaughtExceptionHandler((currentThread, exception) ->
                    log.error("Uncaught exception in thread '{}'", currentThread.getName(), exception)
            );

            return thread;
        };

        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }

    private static void scheduleTask(ScheduledExecutorService scheduler, Runnable task, Duration interval, String taskName) {
        Runnable safeTask = () -> {
            try {
                task.run();
            } catch (Throwable throwable) {
                log.error("Unexpected error while executing scheduled task '{}'", taskName, throwable);
            }
        };

        scheduler.scheduleWithFixedDelay(safeTask, 0, interval.toMillis(), TimeUnit.MILLISECONDS);

        log.info("{} scheduled to run every {} minutes", taskName, interval.toMinutes());
    }

    private static void registerShutdownHook() {
        Thread shutdownThread = new Thread(TraderAgent::shutdown, "trader-agent-shutdown");

        Runtime.getRuntime().addShutdownHook(shutdownThread);
    }

    private static void shutdown() {
        log.info("Shutdown requested. Stopping TraderAgent...");

        shutdownScheduler("SignalDetector", signalDetectorScheduler);

        shutdownScheduler("SignalChecker", signalCheckerScheduler);

        log.info("TraderAgent shutdown completed");
    }

    private static void shutdownScheduler(String name, ScheduledExecutorService scheduler) {
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }

        log.info("Stopping {} scheduler...", name);

        scheduler.shutdown();

        try {
            boolean terminated = scheduler.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            if (!terminated) {
                forceShutdown(name, scheduler);
            }
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for {} scheduler to stop", name);

            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void forceShutdown(String name, ScheduledExecutorService scheduler) throws InterruptedException {
        log.warn("{} scheduler did not stop within {} seconds. Forcing shutdown...", name, SHUTDOWN_TIMEOUT.toSeconds());

        scheduler.shutdownNow();

        boolean terminated = scheduler.awaitTermination(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        if (!terminated) {
            log.error("{} scheduler could not be terminated", name);
        }
    }
}