package org.wispyr.tgnet.model.generated

import kotlin.Long
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_messages_HistoryImport : TlGen_Object {
  public data class TL_messages_historyImport(
    public val id: Long,
  ) : TlGen_messages_HistoryImport() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeInt64(id)
    }

    public companion object {
      public const val MAGIC: UInt = 0x1662AF0BU
    }
  }
}
