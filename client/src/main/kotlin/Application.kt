package client

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

/**
 * Controller responsible for handling OAuth2 login and displaying user information.
 * 
 * This controller maps the root endpoint "/" and provides the following functionality:
 * 1. Extracts the authenticated user's information from the OIDC principal.
 * 2. Adds to the model ID token for using in API calls and username for rendering in the view.
 */
@Controller
class AuthController (
    private val authorizedClientService: OAuth2AuthorizedClientService
) {

    /**
     * Handles requests to the index page.
     *
     * @param model Spring MVC Model for passing attributes to the view.
     * @param authentication Current authenticated OAuth2 token, may be null if unauthenticated.
     * @return The name of the view template to render ("index").
     */
    @GetMapping("/")
    fun index(
        model: Model,
        authentication: OAuth2AuthenticationToken?
    ): String {
        if (authentication != null && authentication.principal is OidcUser) {
            val oidcUser = authentication.principal as OidcUser

            model.addAttribute("idToken", oidcUser.idToken.tokenValue)
            model.addAttribute("username", oidcUser.fullName)

            // val accessToken = authorizedClientService
            //     .loadAuthorizedClient<OAuth2AuthorizedClient>(
            //         authentication.authorizedClientRegistrationId,
            //         authentication.name
            //     )?.accessToken?.tokenValue
            //
            // val userId = oidcUser.idToken.claims["sub"]?.toString()
            // println("USER ID: $userId")
        }

        return "index"
    }
}

/**
 * Security configuration for OAuth2 login and authorization.
 * 
 * Configures:
 * - All requests are permitted (no additional authorization required for demonstration purposes).
 * - OAuth2 login with a default success URL of "/".
 */
@Configuration
class SecurityConfig {

    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                auth.anyRequest().permitAll()
            }
            .oauth2Login { oauth2 ->
                oauth2.defaultSuccessUrl("/", true)
            }

        return http.build()
    }
}


/**
 * Client application for the Microservices API Gateway.
 *
 * This Spring Boot application:
 * 1. Serves as a client that authenticates users using Google's OAuth2/OpenID Connect.
 * 2. Passes user information (ID token, username) to views.
 * 3. Does not directly interact with Eureka or microservices; all interactions go through the API Gateway
 *    (Single Entry Point).
 */
@SpringBootApplication
class ClientApp

fun main(args: Array<String>) {
    runApplication<ClientApp>(*args)
}