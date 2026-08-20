package dev.swapve.csms.support

import org.springframework.beans.BeansException
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource

class ApiCredentialsInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {

    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        applicationContext.environment.propertySources.addFirst(
            MapPropertySource(
                "test-api-basic-auth",
                mapOf(
                    "csms.api.security.enabled" to "true",
                    "csms.api.security.users[0].username" to TestCredentials.API_USER,
                    "csms.api.security.users[0].password-hash" to TestCredentials.API_PASSWORD_HASH,
                ),
            ),
        )

        applicationContext.beanFactory.addBeanPostProcessor(object : BeanPostProcessor {
            @Throws(BeansException::class)
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (bean is TestRestTemplate) {
                    bean.withBasicAuth(TestCredentials.API_USER, TestCredentials.API_PASSWORD)
                } else {
                    bean
                }
        })
    }
}
