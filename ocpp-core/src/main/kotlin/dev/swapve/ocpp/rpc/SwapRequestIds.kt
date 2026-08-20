package dev.swapve.ocpp.rpc

import com.github.f4b6a3.tsid.TsidCreator

/**
 * S02 원격 개시의 `requestId` 발번.
 *
 * ### ★ 여기서 수평 확장 여지 와 스키마가 정면으로 맞지 않는다
 *
 * 규칙은 **"ID 는 시간순 정렬 가능한 값으로 생성 (UUIDv7 / TSID). 로컬 카운터 금지"**
 * 라고 못박는다. 그런데 `RequestBatterySwapRequest.requestId` 는 스키마상 **`integer`** 다.
 * TSID 는 64비트라 Int 에 들어가지 않는다. 둘 다 만족시킬 방법이 없다.
 *
 * 택한 절충은 **TSID 의 하위 31비트를 접어 양수 Int 로 만드는 것**이다. 무엇을 얻고
 * 무엇을 포기했는지 분명히 해 둔다:
 *
 * - ✅ **로컬 카운터가 아니다.** 재시작해도 값이 되돌아가지 않고, 인스턴스를 늘려도 각자
 *   1 부터 세지 않는다 — 수평 확장 여지 가 실제로 막으려던 것이 이것이다.
 * - ⚠️ **완전한 시간순 정렬은 포기했다.** TSID 는 상위 42비트가 시각인데 우리는 하위
 *   31비트만 남기므로, 남는 것은 밀리초의 하위 몇 비트와 난수뿐이다. **정렬 키로 쓰면 안 된다.**
 * 순서가 필요하면 이벤트 로그의 `seq` 를 보라.
 * - ⚠️ **전역 유일하지 않다.** 31비트 공간이라 생일 문제로 수만 건 규모에서 충돌이 보인다.
 *   그래도 안전한 이유는 상관키가 애초에 **`(stationId, requestId)` 복합키**이고
 *, 교환은 몇 분 안에 끝나 같은 스테이션에서 동시에 열려 있는 건수가 한 자리
 *   수이기 때문이다. 스펙도 `requestId` 에 전역 유일성을 요구하지 않는다.
 *
 * 더 나은 해법은 스펙 범위 밖이다 — `requestId` 를 넓히려면 표준을 바꿔야 한다.
 */
object SwapRequestIds {

    /** 양수 Int. `0` 은 돌려주지 않는다 — "값이 없다"와 구분되지 않는 값을 발번하지 않는다. */
    fun next(): Int {
        val folded = (TsidCreator.getTsid().toLong() and Int.MAX_VALUE.toLong()).toInt()
        return if (folded == 0) 1 else folded
    }
}
