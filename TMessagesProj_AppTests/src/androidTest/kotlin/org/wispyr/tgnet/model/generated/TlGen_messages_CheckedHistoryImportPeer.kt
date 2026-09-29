package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_messages_CheckedHistoryImportPeer : TlGen_Object {
  public data class TL_messages_checkedHistoryImportPeer(
    public val confirm_text: String,
  ) : TlGen_messages_CheckedHistoryImportPeer() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(confirm_text)
    }

    public companion object {
      public const val MAGIC: UInt = 0xA24DE717U
    }
  }
}
