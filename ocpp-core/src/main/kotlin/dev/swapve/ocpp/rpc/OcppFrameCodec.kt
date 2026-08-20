package dev.swapve.ocpp.rpc

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The outcome of a decode.
 *
 * The codec **judges but does not decide policy.** Whether a CALLERROR is actually sent, and
 * what gets recorded, belongs to the session layer.
 */
sealed interface DecodeOutcome {

    /** Read as a frame. Whether the payload matches its OCPP schema is not yet known. */
    data class Decoded(val frame: OcppFrame) : DecodeOutcome

    /**
     * A Message Type Number outside the table. **Ignore the whole message.**
     *
     * Part 4 §4.1.3 with errata 2026-06 §4.1/§4.3 — from 2.1 this is not answered with
     * `MessageTypeNotSupported` (that code is deprecated). The rule exists so that a future
     * OCPP adding a type does not break this receiver.
     */
    data class Ignored(val messageTypeNumber: Int) : DecodeOutcome

    /**
     * Not readable as an RPC frame. The caller should answer with a CALLERROR (Part 4 §4.2.3).
     *
     * When even the messageId could not be read, [messageId] carries
     * [OcppFrameCodec.UNREADABLE_MESSAGE_ID].
     */
    data class Malformed(
        val messageId: String,
        val errorCode: RpcErrorCode,
        val errorDescription: String,
    ) : DecodeOutcome
}

/**
 * The OCPP-J frame codec (Part 4 §4.2).
 *
 * WebSocket text frame ↔ [OcppFrame]. A pure conversion — no I/O, no session state.
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
            return unreadable("message is not valid JSON")
        }

        if (!root.isArray || root.size() < 2) return unreadable("message is not an RPC array")

        val typeNode = root.get(0)
        if (!typeNode.isInt) return unreadable("message type number is missing or not an integer")

        val messageId = root.get(1).takeIf { it.isTextual }?.textValue()
            ?: return unreadable("messageId is missing or not a string")
        if (messageId.isEmpty() || messageId.length > MAX_MESSAGE_ID_LENGTH) {
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
            "messageId must be 1..$MAX_MESSAGE_ID_LENGTH characters (Part 4 §4.1.4): got ${messageId.length}"
        }

    private fun requireValidAction(action: String) =
        require(action.isNotBlank()) { "action must not be blank (Part 4 §4.1.6)" }

    private fun requireValidError(errorCode: String, errorDescription: String) {
        require(errorCode.isNotBlank()) { "errorCode must not be blank (Part 4 §4.3)" }
        require(errorDescription.length <= MAX_ERROR_DESCRIPTION_LENGTH) {
            "errorDescription is at most $MAX_ERROR_DESCRIPTION_LENGTH characters (Part 4 §4.2.3)"
        }
    }

    companion object {
        /** Part 4 §4.1.4 */
        const val MAX_MESSAGE_ID_LENGTH = 36

        /** Part 4 §4.2.3 Table 7 — `errorDescription: string[255]` */
        const val MAX_ERROR_DESCRIPTION_LENGTH = 255

        /** What a CALLERROR must carry when even the messageId was unreadable (Part 4 §4.2.3). */
        const val UNREADABLE_MESSAGE_ID = "-1"
    }
}
