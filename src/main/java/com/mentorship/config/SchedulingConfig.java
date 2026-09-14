package com.mentorship.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the Phase 10 background jobs.
 *
 * <p>Gated on {@code app.scheduler.enabled} (default true) so tests can drive the jobs directly and
 * assert their effect, instead of racing a timer that may fire in the middle of a fixture.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

}
