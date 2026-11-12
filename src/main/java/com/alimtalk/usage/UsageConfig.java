package com.alimtalk.usage;

import java.nio.file.Path;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UsageConfig {

    @Bean
    public UsageRecorder usageRecorder(UsageLogProperties props) {
        return new CsvUsageRecorder(Path.of(props.path()), ZoneId.systemDefault());
    }
}
