package org.agent;


import lombok.extern.slf4j.Slf4j;

import org.agent.service.SignalChecker;
import org.agent.service.SignalDetector;
import sun.misc.Signal;

import java.net.InetAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class TraderAgent {

    private static ScheduledExecutorService signalCheckerScheduler;
    private static ScheduledExecutorService signalDetectorScheduler;

    public static void main(String[] args) {
        logApplicationStartUp();
        runSignalDetectorScheduledTask();
        runSignalCheckerScheduledTask();
        handleGracefulShutdown();
    }

    private static void logApplicationStartUp() {
        try {
            log.info(
                    """
                                                        
                            ----------------------------------------------------------
                            \tApplication is running!
                            \tIP: '{}'
                            ----------------------------------------------------------
                            """,
                    InetAddress.getLocalHost().getHostAddress()
            );
        } catch (Exception e) {
            log.error("failed to start application with message: {}", e.getMessage(), e);
        }
    }

    private static void runSignalDetectorScheduledTask() {
        Runnable task = new SignalDetector();
        signalDetectorScheduler = buildThreadScheduledExecutor("signal-detector-thread");
        signalDetectorScheduler.scheduleAtFixedRate(task, 0, 10, TimeUnit.MINUTES);
    }

    private static void runSignalCheckerScheduledTask() {
        Runnable task = new SignalChecker();
        signalCheckerScheduler = buildThreadScheduledExecutor("signal-checker-thread");
        signalCheckerScheduler.scheduleAtFixedRate(task, 0, 5, TimeUnit.MINUTES);
    }

    private static ScheduledExecutorService buildThreadScheduledExecutor(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r);
            t.setName(threadName);
            t.setDaemon(false);
            t.setUncaughtExceptionHandler((thread, ex) ->
                    log.error("Uncaught exception in {}", thread.getName(), ex)
            );
            return t;
        });
    }


    private static void handleGracefulShutdown() {
        // Register SIGINT handler (Ctrl+C / IntelliJ Stop button)
        Signal.handle(new Signal("INT"), signal -> {
            log.info("SIGINT received cleanup resources");
            if (signalDetectorScheduler != null && !signalDetectorScheduler.isShutdown())
                signalDetectorScheduler.shutdown();
            if (signalCheckerScheduler != null && !signalCheckerScheduler.isShutdown())
                signalCheckerScheduler.shutdown();
            log.info("Shutdown complete. Exiting...");
            System.exit(0);
        });
    }
}