package dev.ticbox.companion.protocol

/** WearMessage 20 uses independent optional members, with open integer operation values. */
data class MigrationControlMessage(
    val type: Int = 0,
    val accountMatching: MigrationAccountMessage? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

data class MigrationAccountMessage(
    val operation: Int = 0,
    val accounts: List<DecodedStruct> = emptyList(),
    val errorMessage: String? = null,
    val unknownFields: List<ProtoField> = emptyList(),
)

object MigrationProtocol {
    const val TERMINATE_ASSOCIATION = 1
    const val MIGRATION_FAILED = 4
    const val ACCOUNT_MATCHING = 5
    const val MIGRATION_CANCELLED = 6
    const val GET_ACCOUNTS = 1
    const val ACCOUNTS_RESPONSE = 2
    const val REMOVE_ACCOUNTS = 3
    const val REMOVE_ACCOUNTS_CONFIRM = 4
    const val CANCEL = 5
    const val REMOTE_ERROR = 6

    fun decode(envelope: DecodedStruct): MigrationControlMessage? {
        val control = envelope["control"] as? DecodedStruct ?: return null
        val matching = control["accountMatching"] as? DecodedStruct
        return MigrationControlMessage(
            type = control["type"] as? Int ?: 0,
            accountMatching = matching?.let {
                MigrationAccountMessage(
                    operation = it["operation"] as? Int ?: 0,
                    accounts = (it["accounts"] as? List<*>)?.filterIsInstance<DecodedStruct>().orEmpty(),
                    errorMessage = it["errorMessage"] as? String,
                    unknownFields = it.unknownFields,
                )
            },
            unknownFields = control.unknownFields,
        )
    }

    fun encode(message: MigrationControlMessage): ByteArray = GmsStructs.encode(
        "WearMessage", mapOf("control" to struct("MigrationControl", mapOf(
            "type" to message.type,
            "accountMatching" to message.accountMatching?.let {
                struct("AccountMatching", mapOf(
                    "operation" to it.operation,
                    "accounts" to it.accounts,
                    "errorMessage" to it.errorMessage,
                ), it.unknownFields)
            },
        ), message.unknownFields)),
    )

    fun accountOperation(operation: Int): ByteArray = encode(
        MigrationControlMessage(ACCOUNT_MATCHING, MigrationAccountMessage(operation)),
    )
}
