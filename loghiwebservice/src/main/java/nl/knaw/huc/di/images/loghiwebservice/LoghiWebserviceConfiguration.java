package nl.knaw.huc.di.images.loghiwebservice;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonProperty;
//import edu.stanford.nlp.ling.tokensregex.Env;
//import io.dropwizard.Configuration;
//import io.dropwizard.setup.Environment;
import io.dropwizard.core.Configuration;
import io.dropwizard.core.setup.Environment;
import nl.knaw.huc.di.images.loghiwebservice.configuration.ExecutorServiceConfig;
import nl.knaw.huc.di.images.loghiwebservice.configuration.SecurityConfig;
import nl.knaw.huc.di.images.loghiwebservice.resources.*;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class LoghiWebserviceConfiguration extends Configuration {
    @JsonProperty
    private String uploadLocation;

    @JsonProperty
    private String p2palaConfigFile;

    @JsonProperty
    private String laypaConfig;

    @JsonProperty
    private ExecutorServiceConfig extractBaseLinesExecutorServiceConfig;

    @JsonProperty
    private ExecutorServiceConfig cutFromImageBasedOnPageXmlExecutorServiceConfig;

    @JsonProperty
    private ExecutorServiceConfig loghiHTRMergePageXMLResourceExecutorServiceConfig;

    @JsonProperty
    private ExecutorServiceConfig recalculateReadingOrderNewResourceExecutorServiceConfig;

    @JsonProperty
    private ExecutorServiceConfig splitPageXMLTextLineIntoWordsResourceExecutorServiceConfig;

    @JsonProperty
    private ExecutorServiceConfig detectLanguageOfPageXmlResourceExecutorService;

    @JsonProperty
    private ExecutorServiceConfig extractAndCutExecutorServiceConfig;

    @JsonProperty
    private SecurityConfig securityConfig;

    public void registerExtractBaseLinesResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService =
                extractBaseLinesExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier =
                extractBaseLinesExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new ExtractBaselinesResource(executorService, uploadLocation, p2palaConfigFile,
                laypaConfig, queueUsageStatusSupplier, extractBaseLinesExecutorServiceConfig.getLedgerSize()));
    }

    public void registerCutFromImageBasedOnPageXMLNewResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = cutFromImageBasedOnPageXmlExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = cutFromImageBasedOnPageXmlExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new CutFromImageBasedOnPageXMLNewResource(executorService, uploadLocation, queueUsageStatusSupplier, cutFromImageBasedOnPageXmlExecutorServiceConfig.getLedgerSize()));
    }

    public void registerLoghiHTRMergePageXMLResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = loghiHTRMergePageXMLResourceExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = loghiHTRMergePageXMLResourceExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new LoghiHTRMergePageXMLResource(uploadLocation, executorService, queueUsageStatusSupplier, loghiHTRMergePageXMLResourceExecutorServiceConfig.getLedgerSize()));
    }

    public void registerRecalculateReadingOrderNewResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = recalculateReadingOrderNewResourceExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = recalculateReadingOrderNewResourceExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new RecalculateReadingOrderNewResource(executorService, uploadLocation, queueUsageStatusSupplier, recalculateReadingOrderNewResourceExecutorServiceConfig.getLedgerSize()));
    }

    public void registerSplitPageXMLTextLineIntoWordsResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = splitPageXMLTextLineIntoWordsResourceExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = splitPageXMLTextLineIntoWordsResourceExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new SplitPageXMLTextLineIntoWordsResource(executorService, uploadLocation, queueUsageStatusSupplier, splitPageXMLTextLineIntoWordsResourceExecutorServiceConfig.getLedgerSize()));
    }

    public void registerDetectLanguageOfPageXmlResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = detectLanguageOfPageXmlResourceExecutorService.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = detectLanguageOfPageXmlResourceExecutorService.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new DetectLanguageOfPageXmlResource(uploadLocation, executorService, queueUsageStatusSupplier, detectLanguageOfPageXmlResourceExecutorService.getLedgerSize()));
    }

    public void registerExtractAndCutResource(Environment environment, MetricRegistry metricRegistry) {
        final ExecutorService executorService = extractAndCutExecutorServiceConfig.createExecutorService(environment, metricRegistry);
        final Supplier<String> queueUsageStatusSupplier = extractAndCutExecutorServiceConfig.createQueueUsageStatusSupplier(metricRegistry);
        environment.jersey().register(new ExtractAndCutResource(executorService, uploadLocation, p2palaConfigFile,
                laypaConfig, queueUsageStatusSupplier, extractAndCutExecutorServiceConfig.getLedgerSize()));
    }

    public void registerSecurity(Environment environment) {
        securityConfig.registerSecurity(environment);
    }

    public String getUploadLocation() {
        return uploadLocation;
    }
}
