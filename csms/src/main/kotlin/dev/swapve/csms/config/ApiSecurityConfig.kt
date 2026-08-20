package dev.swapve.csms.config

import dev.swapve.csms.api.ApiBasicAuthFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApiSecurityConfig {

    @Bean
    fun apiBasicAuthFilter(properties: CsmsProperties): FilterRegistrationBean<ApiBasicAuthFilter> =
        FilterRegistrationBean(ApiBasicAuthFilter(properties.api.security)).apply {
            addUrlPatterns("/api/*")
            order = 0
        }
}
