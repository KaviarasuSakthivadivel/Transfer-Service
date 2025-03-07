package org.onedatashare.transferservice.odstransferservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration
public class OptimizerCronConfig {

    @Value("${optimizer.url}")
    private String optimizerUrl;


    @Bean
    public RestTemplate optimizerTemplate() {
        return new RestTemplateBuilder()
                .uriTemplateHandler(new DefaultUriBuilderFactory(optimizerUrl))
                .build();
    }

    @Bean
    public ThreadPoolTaskScheduler optimizerTaskScheduler() {
        ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setThreadNamePrefix("OptimizerTaskScheduler");
        return threadPoolTaskScheduler;
    }
}
