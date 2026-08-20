package dev.swapve.csms.support

import dev.swapve.csms.audit.LoadScenario
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.MapPropertySource

object TestStations {

    const val TC_S_102 = "CS-TC-S-102"
    const val TC_S_102_VALIDATION = "CS-TC-S-102-VALIDATION"
    const val TC_S_102_UNAUTHORIZED = "CS-TC-S-102-UNAUTHORIZED"

    const val TC_S_103 = "CS-TC-S-103"
    const val TC_S_103_STEP1 = "CS-TC-S-103-STEP1"
    const val TC_S_103_IDTOKENINFO = "CS-TC-S-103-IDTOKENINFO"
    const val TC_S_103_STARTED = "CS-TC-S-103-STARTED"
    const val TC_S_103_ENDED = "CS-TC-S-103-ENDED"
    const val TC_S_103_ORPHAN_TX = "CS-TC-S-103-ORPHAN-TX"
    const val TC_S_103_BATTERIES = "CS-TC-S-103-BATTERIES"
    const val TC_S_103_ACK = "CS-TC-S-103-ACK"
    const val TC_S_103_ORDER = "CS-TC-S-103-ORDER"

    const val TC_S_104 = "CS-TC-S-104"
    const val TC_S_104_SEQ = "CS-TC-S-104-SEQ"
    const val TC_S_104_REQID = "CS-TC-S-104-REQID"
    const val TC_S_104_VARS = "CS-TC-S-104-VARS"
    const val TC_S_104_VALUES = "CS-TC-S-104-VALUES"
    const val TC_S_104_CASE = "CS-TC-S-104-CASE"
    const val TC_S_104_SCHEMA = "CS-TC-S-104-SCHEMA"

    const val F1_NO_BATTERY = "CS-F1-NO-BATTERY"
    const val F2_OUT_TIMEOUT = "CS-F2-OUT-TIMEOUT"
    const val F2_PERSISTENT = "CS-F2-PERSISTENT"
    const val F3_UNKNOWN_BATTERY = "CS-F3-UNKNOWN-BATTERY"
    const val F4_DUPLICATE_IN = "CS-F4-DUPLICATE-IN"
    const val F5_NOT_AUTHORIZED = "CS-F5-NOT-AUTHORIZED"
    const val F6_RECONNECT = "CS-F6-RECONNECT"

    val CONFORMANCE_IDS = listOf(
        TC_S_102,
        TC_S_102_VALIDATION,
        TC_S_102_UNAUTHORIZED,
        TC_S_103,
        TC_S_103_STEP1,
        TC_S_103_IDTOKENINFO,
        TC_S_103_STARTED,
        TC_S_103_ENDED,
        TC_S_103_ORPHAN_TX,
        TC_S_103_BATTERIES,
        TC_S_103_ACK,
        TC_S_103_ORDER,
        TC_S_104,
        TC_S_104_SEQ,
        TC_S_104_REQID,
        TC_S_104_VARS,
        TC_S_104_VALUES,
        TC_S_104_CASE,
        TC_S_104_SCHEMA,
        F1_NO_BATTERY,
        F2_OUT_TIMEOUT,
        F2_PERSISTENT,
        F3_UNKNOWN_BATTERY,
        F4_DUPLICATE_IN,
        F5_NOT_AUTHORIZED,
        F6_RECONNECT,
    )

    val ALL_IDS = CONFORMANCE_IDS + LoadScenario.RUN_STATION_IDS
}

class BasicAuthStations : ApplicationContextInitializer<ConfigurableApplicationContext> {

    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val properties = mutableMapOf<String, Any>(
            "csms.security.profile" to "BASIC",
        )
        TestStations.ALL_IDS.forEachIndexed { index, stationId ->
            properties["csms.security.stations[$index].station-id"] = stationId
            properties["csms.security.stations[$index].password-hash"] = TestCredentials.PASSWORD_HASH
        }

        applicationContext.environment.propertySources.addFirst(
            MapPropertySource("test-basic-auth-stations", properties),
        )
    }
}
