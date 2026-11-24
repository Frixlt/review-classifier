package ru.innopolis.reviewclassifier.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.innopolis.reviewclassifier.domain.exception.ModelLoadException;

/**
 * Application entry point bootstrapping configuration, model verification, and HTTP server lifecycle.
 */
public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 5L;

    private Main() {}

    /**
     * Starts the review classifier microservice and awaits shutdown signal.
     *
     * @param args CLI arguments (unused; configured via environment)
     */
    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnvironment();
        CountDownLatch keepAlive = new CountDownLatch(1);
        CountDownLatch stoppedLatch = new CountDownLatch(1);

        try (ApplicationWiring wiring = new ApplicationWiring(config)) {
            startApplication(wiring, config);
            registerShutdownHook(keepAlive, stoppedLatch);
            awaitShutdown(keepAlive);
        } catch (IOException ex) {
            LOG.error("Fatal startup I/O error: {}", ex.getMessage(), ex);
            throw new UncheckedIOException("Fatal startup I/O error", ex);
        } finally {
            stoppedLatch.countDown();
        }
    }

    static void startApplication(ApplicationWiring wiring, AppConfig config) {
        try {
            wiring.loadModel();
            LOG.info("Loaded sentiment model from {}", config.modelPath());
        } catch (ModelLoadException ex) {
            // Keep HTTP server alive so /health/live returns 200 and /health/ready reports 503 DOWN (AC-23).
            LOG.error("Model cold-start failed; readiness probe will report DOWN: {}", ex.getMessage(), ex);
        }
        wiring.start();
    }

    private static void registerShutdownHook(CountDownLatch keepAlive, CountDownLatch stoppedLatch) {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(
                        () -> {
                            LOG.info("Shutdown signal received, stopping server...");
                            keepAlive.countDown();
                            try {
                                if (!stoppedLatch.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                                    LOG.warn("Timed out waiting {}s for graceful shutdown", SHUTDOWN_WAIT_SECONDS);
                                }
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                            }
                        },
                        "review-classifier-shutdown"));
    }

    private static void awaitShutdown(CountDownLatch keepAlive) {
        try {
            keepAlive.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            LOG.info("Main thread interrupted; initiating shutdown");
        }
    }
}
