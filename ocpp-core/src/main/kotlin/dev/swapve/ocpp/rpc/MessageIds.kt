package dev.swapve.ocpp.rpc

import com.github.f4b6a3.tsid.TsidCreator

/**
 * CALL/SEND 의 messageId 발번.
 *
 * Part 4 §4.1.4 — messageId는 **같은 스테이션 식별자에 대해 그 발신자가 이전에 쓴 모든 값과
 * 달라야 한다(MUST). 연결이 끊겼다 다시 붙어도 마찬가지다.**
 *
 * 그래서 `AtomicInteger` 같은 로컬 카운터를 쓰지 않는다 — 재시작하면 값이 되돌아가고,
 * 인스턴스를 늘리면 충돌한다 (PLAN §11.5). TSID는 시간순 정렬이 가능하면서 13자라
 * 36자 한도에 여유가 크다.
 */
object MessageIds {

    /** Crockford Base32 13자. 사전순 정렬 = 생성순 정렬. */
    fun next(): String = TsidCreator.getTsid().toString()
}
