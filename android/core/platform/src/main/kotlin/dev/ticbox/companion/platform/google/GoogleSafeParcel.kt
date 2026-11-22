package dev.ticbox.companion.platform.google

import android.os.Parcel

/** The recovered SafeParcelable layout, written through Parcel so Binder objects retain handles. */
internal object GoogleSafeParcel {
    fun objectValue(parcel: Parcel, body: () -> Unit) = variable(parcel, 20293, body)
    fun variable(parcel: Parcel, id: Int, body: () -> Unit) {
        parcel.writeInt(-0x10000 or id)
        parcel.writeInt(0)
        val start = parcel.dataPosition()
        body()
        val end = parcel.dataPosition()
        parcel.setDataPosition(start - 4); parcel.writeInt(end - start); parcel.setDataPosition(end)
    }
    fun int(parcel: Parcel, id: Int, value: Int) { parcel.writeInt((4 shl 16) or id); parcel.writeInt(value) }
    fun string(parcel: Parcel, id: Int, value: String) = variable(parcel, id) { parcel.writeString(value) }
    fun bool(parcel: Parcel, id: Int, value: Boolean) = int(parcel, id, if (value) 1 else 0)

    private fun size(parcel: Parcel, header: Int): Int = if ((header ushr 16) == 0xffff) parcel.readInt() else header ushr 16
    fun fields(parcel: Parcel, consume: (id: Int, size: Int) -> Unit) {
        val header = parcel.readInt()
        require((header and 0xffff) == 20293) { "invalid_safeparcel_object" }
        val length = size(parcel, header)
        val start = parcel.dataPosition()
        require(length >= 0 && length <= 1024 * 1024 && length <= parcel.dataSize() - start) { "invalid_safeparcel_size" }
        val end = start + length
        var count = 0
        while (parcel.dataPosition() < end) {
            require(++count <= 1024 && end - parcel.dataPosition() >= 4) { "safeparcel_field_budget" }
            val field = parcel.readInt()
            val width = size(parcel, field)
            val at = parcel.dataPosition()
            require(width >= 0 && width <= end - at) { "invalid_safeparcel_field" }
            consume(field and 0xffff, width)
            require(parcel.dataPosition() <= at + width) { "safeparcel_field_overread" }
            parcel.setDataPosition(at + width)
        }
        require(parcel.dataPosition() == end) { "safeparcel_object_overread" }
    }
    fun skipObject(parcel: Parcel) = fields(parcel) { _, _ -> }
    fun status(parcel: Parcel): Int? {
        if (parcel.readInt() == 0) return null
        var code: Int? = null
        fields(parcel) { id, size -> if (id == 1 && size == 4) code = parcel.readInt() }
        return code
    }
}
