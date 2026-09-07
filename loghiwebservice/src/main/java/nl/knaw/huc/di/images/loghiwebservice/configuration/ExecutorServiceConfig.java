package nl.knaw.huc.di.images.loghiwebservice.configuration;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.setup.Environment;

import jakarta.validation.constraints.NotEmpty;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class ExecutorServiceConfig {

    @NotEmpty
    @JsonProperty
    private int maxThreads;

    @NotEmpty
    @JsonProperty
    private int queueLength;

    @NotEmpty
    @JsonProperty
    private String name;

    @NotEmpty
    @JsonProperty
    private int ledgerSize;



    public ExecutorService createExecutorService(Environment environment, MetricRegistry metricRegistry) {
        final ArrayBlockingQueue<Runnable> workQueue = new ArrayBlockingQueue<>(queueLength, true);
        metricRegistry.register(MetricRegistry.name(name + "QueueSize"), (Gauge<Integer>) workQueue::size);

        return environment.lifecycle()
                .executorService(name)
                .minThreads(maxThreads)
                .maxThreads(maxThreads)
                .workQueue(workQueue)
                .build();
    }

    public Supplier<String> createQueueUsageStatusSupplier(MetricRegistry metricRegistry) {
        return () -> String.format("%s/%d", metricRegistry.gauge(name + "QueueSize").getValue(), queueLength);
    }

    public int getLedgerSize() {
        return ledgerSize;
    }

}
