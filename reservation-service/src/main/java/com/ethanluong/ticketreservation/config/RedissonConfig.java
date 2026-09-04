package com.ethanluong.ticketreservation.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds Redisson from Boot's {@link DataRedisConnectionDetails} rather than from the
 * {@code spring.data.redis.url} property. The two agree in prod and compose (the property
 * is the source of the details there), but under {@code @ServiceConnection} the details
 * point at the Testcontainers port while the property still says localhost:6379 —
 * which is how the ITs passed against a compose Redis for weeks and failed on run 1 of CI.
 */
@Configuration
public class RedissonConfig {

	@Bean(destroyMethod = "shutdown")
	public RedissonClient redissonClient(DataRedisConnectionDetails details) {
		DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
		String scheme = details.getSslBundle() != null ? "rediss" : "redis";
		Config config = new Config();
		SingleServerConfig server = config.useSingleServer()
				.setAddress(scheme + "://" + standalone.getHost() + ":" + standalone.getPort())
				.setDatabase(standalone.getDatabase());
		if (details.getUsername() != null) {
			server.setUsername(details.getUsername());
		}
		if (details.getPassword() != null) {
			server.setPassword(details.getPassword());
		}
		return Redisson.create(config);
	}
}
