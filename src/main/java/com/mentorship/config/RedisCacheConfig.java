package com.mentorship.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.mentorship.dto.AvailabilityResponse;
import com.mentorship.dto.MentorProfileResponse;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.type.TypeFactory;

@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

	private static final Logger log = LoggerFactory.getLogger(RedisCacheConfig.class);

	@Bean
	RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
			@Value("${app.cache.mentor-ttl}") Duration mentorTtl,
			@Value("${app.cache.availability-ttl}") Duration availabilityTtl) {

		// Default prefix would produce "mentor::7"; this yields the agreed "mentor:7".
		RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
				.computePrefixWith(cacheName -> cacheName + ":")
				.disableCachingNullValues();

		// Each cache holds one known type, so a typed serializer is used rather than
		// polymorphic typing - records are final, which default typing would not tag.
		JavaType availabilityList = TypeFactory.createDefaultInstance()
				.constructCollectionType(List.class, AvailabilityResponse.class);

		Map<String, RedisCacheConfiguration> perCache = Map.of(
				CacheEvictor.MENTOR_CACHE, base.entryTtl(mentorTtl)
						.serializeValuesWith(serializer(new JacksonJsonRedisSerializer<>(
								MentorProfileResponse.class))),
				CacheEvictor.AVAILABILITY_CACHE, base.entryTtl(availabilityTtl)
						.serializeValuesWith(serializer(new JacksonJsonRedisSerializer<>(availabilityList))));

		// immediateWrites() is required for correctness, not tuning. Spring Data Redis defaults
		// asynchronousWrites to true whenever the connection factory is reactive, and Lettuce is.
		// DefaultRedisCacheWriter.put/evict then hand the command to an async delegate and discard
		// the returned CompletableFuture, so the calling method returns before Redis has been
		// touched. An @Cacheable put can therefore land AFTER a later @CacheEvict and resurrect the
		// evicted entry, serving stale data until the TTL expires. Confirmed by a Redis MONITOR
		// trace showing SET arriving after UNLINK for the same key. Writing synchronously restores
		// the ordering the cache annotations already imply.
		//
		// Deliberately NOT transactionAware(): every cached method is also @Transactional, so
		// that decorator defers puts and evicts into afterCommit callbacks which run outside the
		// cache interceptor. When such a callback does not fire, the write is lost silently -
		// no exception, nothing for CacheErrorHandler to catch, and an empty cache.
		RedisCacheWriter cacheWriter = RedisCacheWriter.create(connectionFactory,
				RedisCacheWriter.RedisCacheWriterConfigurer::immediateWrites);

		return RedisCacheManager.builder(cacheWriter)
				.cacheDefaults(base)
				.withInitialCacheConfigurations(perCache)
				.build();
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static RedisSerializationContext.SerializationPair<Object> serializer(
			JacksonJsonRedisSerializer<?> serializer) {
		return RedisSerializationContext.SerializationPair.fromSerializer((JacksonJsonRedisSerializer) serializer);
	}

	/**
	 * Redis is a cache, never a hard dependency. Cache failures are logged and swallowed so the
	 * call falls through to PostgreSQL. Only cache exceptions reach this handler, so genuine
	 * business and data-access errors still propagate normally.
	 */
	@Override
	public CacheErrorHandler errorHandler() {
		return new CacheErrorHandler() {

			@Override
			public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
				log.warn("Cache read failed for {}:{} - falling back to the database", cache.getName(), key,
						exception);
			}

			@Override
			public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
				log.warn("Cache write failed for {}:{} - the response is still correct", cache.getName(), key,
						exception);
			}

			@Override
			public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
				log.warn("Cache eviction failed for {}:{} - stale entry expires via TTL", cache.getName(), key,
						exception);
			}

			@Override
			public void handleCacheClearError(RuntimeException exception, Cache cache) {
				log.warn("Cache clear failed for {}", cache.getName(), exception);
			}

		};
	}

}
