package com.urlshortener.orchestrator.engine.executor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wiring for the executor seam. The pool runs node executors off the engine's transaction/lock;
 * parallel branches (e.g. testing + documentation) run their work concurrently while their
 * {@code complete} callbacks still serialize on the engine's per-run lock. Tests override the
 * {@code nodeExecutorPool} bean with a synchronous executor for determinism.
 */
@Configuration
@EnableConfigurationProperties(ExecutorProperties.class)
public class ExecutorConfig {

    @Bean(name = "nodeExecutorPool")
    @ConditionalOnMissingBean(name = "nodeExecutorPool")
    public Executor nodeExecutorPool() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(64);
        pool.setThreadNamePrefix("node-exec-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        pool.initialize();
        return pool;
    }
}
