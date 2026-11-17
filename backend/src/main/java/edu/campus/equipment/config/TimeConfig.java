package edu.campus.equipment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * All timestamps are stored as UTC instants (timestamptz). The campus timezone is only used to
 * render dates in messages and reports.
 */
@Configuration
public class TimeConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public ZoneId campusZone(AppProperties props) {
        return ZoneId.of(props.timezone() == null ? "UTC" : props.timezone());
    }
}
