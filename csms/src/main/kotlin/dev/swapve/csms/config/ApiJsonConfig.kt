package dev.swapve.csms.config

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import dev.swapve.ocpp.json.OcppDateTime
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

/**
 * REST 응답의 시각을 **OCPP 와 같은 형식**으로 낸다.
 *
 * Jackson 기본(`ISO_INSTANT`)은 밀리초가 0 이면 소수부를 아예 내지 않는다. 그러면 같은
 * 응답 안에서 `"2026-08-18T09:30:00Z"` 와 `"2026-08-18T09:30:12.345Z"` 가 섞여 나가고,
 * 앱 파서의 소수부 처리가 다를 때 그것이 사고가 된다 — 전선 위에서 피하려던 것과 같은 사고다.
 *
 * 그래서 [OcppDateTime] 하나만 쓴다. 스테이션에게 말할 때와 앱에게 말할 때가 다른 형식일
 * 이유가 없고, **형식이 값에 따라 바뀌면 그것은 계약이 아니다.**
 */
@Configuration
class ApiJsonConfig {

    @Bean
    fun ocppInstantFormat(): Jackson2ObjectMapperBuilderCustomizer =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.serializerByType(Instant::class.java, OcppInstantSerializer)
        }

    private object OcppInstantSerializer : JsonSerializer<Instant>() {
        override fun serialize(value: Instant, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(OcppDateTime.format(value))
        }
    }
}
