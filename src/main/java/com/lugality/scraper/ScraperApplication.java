package com.lugality.scraper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lugality.scraper.config.ScraperSettings;
import com.lugality.scraper.service.ParallelScraperService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.InputStream;
import java.util.List;

@Slf4j
@SpringBootApplication
@EnableAsync
@EnableConfigurationProperties
public class ScraperApplication {

    public static void main(String[] args) {
        SpringApplication.run(ScraperApplication.class, args);
    }

    @Bean
    public ApplicationRunner autoScrape(ParallelScraperService parallelScraperService,
                                        ScraperSettings settings,
                                        ObjectMapper objectMapper) {
        return args -> {
            InputStream is = getClass().getClassLoader().getResourceAsStream("applications.json");
            if (is == null) {
                log.warn("applications.json not found — skipping auto-scrape");
                return;
            }

            List<String> applications = objectMapper.readValue(is, new TypeReference<>() {});
            if (applications.isEmpty()) {
                log.warn("applications.json is empty — skipping auto-scrape");
                return;
            }

            log.info("Auto-scrape started: {} applications, {} workers",
                    applications.size(), settings.getNumWorkers());

            Thread.ofVirtual().name("auto-scrape").start(() -> {
                try {
                    parallelScraperService.runParallel(
                            settings.getEmailAddress(),
                            applications,
                            settings.getNumWorkers(),
                            false
                    );
                    log.info("Auto-scrape completed!");
                } catch (Exception e) {
                    log.error("Auto-scrape failed: {}", e.getMessage(), e);
                }
            });
        };
    }
}