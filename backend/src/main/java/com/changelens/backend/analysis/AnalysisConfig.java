package com.changelens.backend.analysis;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChangeLensProperties.class)
class AnalysisConfig {

    /**
     * A fixed number of workers and a bounded queue. When both are full the pool refuses new work, which the service turns into
     * an HTTP 429, instead of letting memory grow without limit under load.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService analysisExecutor(ChangeLensProperties properties) {
        AtomicInteger n = new AtomicInteger();
        return new ThreadPoolExecutor(properties.workers(), properties.workers(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, properties.queue())), runnable -> {
                    Thread t = new Thread(runnable, "analysis-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
}
