package com.mentorship.config;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

/**
 * Bridges precise cache eviction for service methods that return void.
 *
 * <p>{@code @CacheEvict} SpEL can only reach method arguments and {@code #result}, never a local
 * variable inside a service method. Passing an already-loaded mentor id to this bean turns that
 * local into an argument the annotation can key on, so a single mentor is evicted instead of
 * {@code allEntries = true} flushing every mentor.
 *
 * <p>Routing through the annotation keeps eviction on the CacheInterceptor path, where the
 * configured CacheErrorHandler applies. A direct CacheManager call would bypass it and make Redis
 * a hard dependency for cancellation.
 */
@Component
public class CacheEvictor {

	public static final String MENTOR_CACHE = "mentor";

	public static final String AVAILABILITY_CACHE = "availability";

	@CacheEvict(cacheNames = MENTOR_CACHE, key = "#mentorId")
	public void evictMentor(Long mentorId) {
		// The annotation is the behaviour.
	}

	@CacheEvict(cacheNames = AVAILABILITY_CACHE, key = "#mentorId")
	public void evictAvailability(Long mentorId) {
		// The annotation is the behaviour.
	}

}
