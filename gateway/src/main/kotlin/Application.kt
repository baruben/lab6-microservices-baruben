package gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain
import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.time.Duration
import java.security.Principal
import java.util.concurrent.ConcurrentHashMap
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import org.springframework.web.cors.CorsConfiguration
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Global filter for logging all incoming requests and outgoing responses.
 *
 * Runs early in the filter chain (order = -1) to ensure that all requests are logged
 * before any other filters (like rate limiting) are applied.
 */
@Component
class LoggingFilter : GlobalFilter, Ordered {

    private val logger = KotlinLogging.logger {}

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request
        logger.info { "Incoming request: ${request.method} ${request.uri}" }

        return chain.filter(exchange).doOnSuccess {
            val response = exchange.response
            logger.info { "Outgoing response: ${response.statusCode}" }
        }
    }

    override fun getOrder(): Int {
        return -1 // runs early
    }
}

/**
 * Global filter for rate limiting requests per user.
 *
 * Uses Bucket4j for token bucket rate limiting. 
 * Limits each authenticated user to 5 requests per minute.
 *
 * Runs after LoggingFilter (order = 0).
 */
@Component
class RateLimitFilter : GlobalFilter, Ordered {

    private val buckets: ConcurrentHashMap<String, Bucket> = ConcurrentHashMap()

    /**
     * Resolve or create a rate-limiting bucket for the given key.
     *
     * @param key The unique key for rate limiting (authenticated user's ID)
     * @return A token bucket instance
     */
    private fun resolveBucket(key: String) : Bucket {
        return buckets.computeIfAbsent(key) {
            val refill = Refill.intervally(5, Duration.ofMinutes(1))
            val limit = Bandwidth.classic(5, refill)
            Bucket.builder().addLimit(limit).build()
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        return exchange.getPrincipal<Principal>().flatMap { auth ->
            // println(auth)
            val key = auth.name
            // println(key)
            val bucket = resolveBucket(key)
            val probe = bucket.tryConsumeAndReturnRemaining(1)

            if (!probe.isConsumed) {
                val retryAfter = (probe.nanosToWaitForRefill / 1_000_000_000).toString()
                exchange.response.statusCode = HttpStatus.TOO_MANY_REQUESTS
                exchange.response.headers["Retry-After"] = retryAfter

                val buffer = exchange.response.bufferFactory()
                    .wrap("""{"error":"Too Many Requests", "retryAfter":"${retryAfter}s"}""".toByteArray())
                exchange.response.writeWith(Mono.just(buffer))
            } else {
                exchange.response.headers["X-RateLimit-Remaining"] = probe.remainingTokens.toString()
                chain.filter(exchange)
            }
        }
    }

    override fun getOrder(): Int {
        return 0 // runs after LoggingFilter
    }
}

/**
 * Security configuration for the API Gateway.
 *
 * Configures:
 * 1. CSRF disabled (suitable for APIs)
 * 2. CORS configuration for the frontend client
 * 3. All exchanges require authentication (OAuth2 JWT)
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    /**
     * Configure the security filter chain for the gateway.
     *
     * @param http ServerHttpSecurity
     * @return SecurityWebFilterChain configured with OAuth2 JWT and authentication rules
     */
    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http
            .csrf { it.disable() } 
            .cors { } 
            .authorizeExchange { auth ->
                auth.anyExchange().authenticated()                     
            }
            .oauth2ResourceServer { oauth ->
                oauth.jwt()
            }
            .build()
    }

    /**
     * Configure CORS to allow requests from the frontend client.
     *
     * @return CorsConfigurationSource
     */
    @Bean
    fun corsConfig(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = listOf("http://localhost:8080") // CLIENT DOMAIN
        config.allowedMethods = listOf("*")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}

/**
 * Gateway application. This application acts as an entry point for API clients.
 *
 * At startup, this application will:
 * 1. Register with Eureka (service discovery) using the name GATEWAY-SERVICE
 *    (from application.yml: spring.application.name)
 * 2. Fetch configuration from Config Server (discovered via Eureka)
 * 3. Start the embedded web server on the configured port (default: 1111)
 * 4. Begin accepting REST requests
 *
 * Other services can discover this service by querying Eureka for GATEWAY-SERVICE.
 */
@SpringBootApplication
class GatewayServer

fun main(args: Array<String>) {
    runApplication<GatewayServer>(*args)
}