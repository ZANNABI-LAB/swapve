package dev.swapve.ocpp.rpc

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * decode 결과.
 *
 * 코덱은 **판정만 하고 정책은 정하지 않는다.** CALLERROR를 실제로 회신할지, 무엇을 기록할지는
 * 세션 계층(M4)의 몫이다.
 */
sealed interface DecodeOutcome {

    /** 프레임으로 읽혔다. 페이로드가 OCPP 스키마에 맞는지는 아직 모른다 (M2). */
    data class Decoded(val frame: OcppFrame) : DecodeOutcome

    /**
     * 표에 없는 Message Type Number. **메시지 전체를 무시한다.**
     *
     * Part 4 §4.1.3 + errata 2026-06 §4.1/§4.3 — 2.1부터 `MessageTypeNotSupported`로
     * 회신하지 않는다(해당 코드 deprecated). 나중에 OCPP가 타입을 추가해도 깨지지 않기 위한 규칙.
     */
    data class Ignored(val messageTypeNumber: Int) : DecodeOutcome

    /**
     * RPC 프레임으로 읽을 수 없다. 호출자는 CALLERROR로 회신해야 한다 (Part 4 §4.2.3).
     *
     * [messageId]를 읽을 수 없었으면 [OcppFrameCodec.UNREADABLE_MESSAGE_ID] 가 들어온다.
     */
    data class Malformed(
        val messageId: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
    ) : DecodeOutcome
}

/**
 * OCPP-J 프레임 코덱 (Part 4 §4.2).
 *
 * WebSocket 텍스트 프레임 ↔ [OcppFrame]. I/O도 세션 상태도 없는 순수 변환이다.
 */
class OcppFrameCodec(private val mapper: ObjectMapper = ObjectMapper()) {

    fun encode(frame: OcppFrame): String {
        requireValidMessageId(frame.messageId)

        val array = mapper.createArrayNode()
        array.add(frame.type.number)
        array.add(frame.messageId)

        when (frame) {
            is OcppFrame.Call -> {
                requireValidAction(frame.action)
                array.add(frame.action)
                array.add(frame.payload)
            }

            is OcppFrame.Send -> {
                requireValidAction(frame.action)
                array.add(frame.action)
                array.add(frame.payload)
            }

            is OcppFrame.CallResult -> array.add(frame.payload)

            is OcppFrame.CallError -> {
                requireValidError(frame.errorCode, frame.errorDescription)
                array.add(frame.errorCode)
                array.add(frame.errorDescription)
                array.add(frame.errorDetails)
            }

            is OcppFrame.CallResultError -> {
                requireValidError(frame.errorCode, frame.errorDescription)
                array.add(frame.errorCode)
                array.add(frame.errorDescription)
                array.add(frame.errorDetails)
            }
        }

        return mapper.writeValueAsString(array)
    }

    fun decode(text: String): DecodeOutcome {
        val root = try {
            mapper.readTree(text)
        } catch (e: JsonProcessingException) {
            // 잘못된 JSON 원문을 설명에 넣지 않는다 — CALLERROR가 깨진 JSON을 그대로
            // 실어 나르면 안 된다 (Part 4 §4.2.3).
            return unreadable("message is not valid JSON")
        }

        if (!root.isArray || root.size() < 2) return unreadable("message is not an RPC array")

        val typeNode = root.get(0)
        if (!typeNode.isInt) return unreadable("message type number is missing or not an integer")

        val messageId = root.get(1).takeIf { it.isTextual }?.textValue()
            ?: return unreadable("messageId is missing or not a string")
        if (messageId.isEmpty() || messageId.length > MAX_MESSAGE_ID_LENGTH) {
            // 길이 위반 id를 회신에 되쓰면 우리도 §4.1.4를 위반하게 된다. "-1"로 답한다.
            return unreadable("messageId must be 1..$MAX_MESSAGE_ID_LENGTH characters")
        }

        val type = MessageType.ofNumber(typeNode.intValue())
            ?: return DecodeOutcome.Ignored(typeNode.intValue())

        return when (type) {
            MessageType.CALL -> decodeActionFrame(root, messageId) { id, action, payload ->
                OcppFrame.Call(id, action, payload)
            }

            MessageType.SEND -> decodeActionFrame(root, messageId) { id, action, payload ->
                OcppFrame.Send(id, action, payload)
            }

            MessageType.CALL_RESULT -> {
                if (root.size() != 3) return protocolError(messageId, "CALLRESULT must have 3 elements")
                val payload = objectOrNull(root.get(2))
                    ?: return protocolError(messageId, "payload must be a JSON object")
                DecodeOutcome.Decoded(OcppFrame.CallResult(messageId, payload))
            }

            MessageType.CALL_ERROR -> decodeErrorFrame(root, messageId) { id, code, desc, details ->
                OcppFrame.CallError(id, code, desc, details)
            }

            MessageType.CALL_RESULT_ERROR -> decodeErrorFrame(root, messageId) { id, code, desc, details ->
                OcppFrame.CallResultError(id, code, desc, details)
            }
        }
    }

    private inline fun decodeActionFrame(
        root: JsonNode,
        messageId: String,
        build: (String, String, ObjectNode) -> OcppFrame,
    ): DecodeOutcome {
        if (root.size() != 4) return protocolError(messageId, "CALL/SEND must have 4 elements")
        val action = root.get(2).takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
            ?: return protocolError(messageId, "action is missing or not a non-empty string")
        val payload = objectOrNull(root.get(3))
            ?: return protocolError(messageId, "payload must be a JSON object")
        return DecodeOutcome.Decoded(build(messageId, action, payload))
    }

    private inline fun decodeErrorFrame(
        root: JsonNode,
        messageId: String,
        build: (String, String, String, ObjectNode) -> OcppFrame,
    ): DecodeOutcome {
        if (root.size() != 5) return protocolError(messageId, "CALLERROR must have 5 elements")
        val code = root.get(2).takeIf { it.isTextual }?.textValue()
            ?: return protocolError(messageId, "errorCode is missing or not a string")
        val description = root.get(3).takeIf { it.isTextual }?.textValue()
            ?: return protocolError(messageId, "errorDescription is missing or not a string")
        val details = objectOrNull(root.get(4))
            ?: return protocolError(messageId, "errorDetails must be a JSON object")
        return DecodeOutcome.Decoded(build(messageId, code, description, details))
    }

    private fun objectOrNull(node: JsonNode?): ObjectNode? = node as? ObjectNode

    private fun unreadable(description: String) =
        DecodeOutcome.Malformed(UNREADABLE_MESSAGE_ID, RpcErrorCode.RpcFrameworkError, description)

    private fun protocolError(messageId: String, description: String) =
        DecodeOutcome.Malformed(messageId, RpcErrorCode.ProtocolError, description)

    private fun requireValidMessageId(messageId: String) =
        require(messageId.isNotEmpty() && messageId.length <= MAX_MESSAGE_ID_LENGTH) {
            "messageId는 1..$MAX_MESSAGE_ID_LENGTH 자여야 한다 (Part 4 §4.1.4): 길이 ${messageId.length}"
        }

    private fun requireValidAction(action: String) =
        require(action.isNotBlank()) { "action은 비어 있을 수 없다 (Part 4 §4.1.6)" }

    private fun requireValidError(errorCode: String, errorDescription: String) {
        require(errorCode.isNotBlank()) { "errorCode는 비어 있을 수 없다 (Part 4 §4.3)" }
        require(errorDescription.length <= MAX_ERROR_DESCRIPTION_LENGTH) {
            "errorDescription은 최대 ${MAX_ERROR_DESCRIPTION_LENGTH}자다 (Part 4 §4.2.3)"
        }
    }

    companion object {
        /** Part 4 §4.1.4 */
        const val MAX_MESSAGE_ID_LENGTH = 36

        /** Part 4 §4.2.3 Table 7 — `errorDescription: string[255]` */
        const val MAX_ERROR_DESCRIPTION_LENGTH = 255

        /** messageId조차 읽을 수 없을 때 CALLERROR에 실어야 하는 값 (Part 4 §4.2.3) */
        const val UNREADABLE_MESSAGE_ID = "-1"
    }
}
