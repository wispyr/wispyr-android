package org.wispyr.tgnet.model.generated

import kotlin.Int
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_DefaultHistoryTTL : TlGen_Object {
  public data class TL_defaultHistoryTTL(
    public val period: Int,
  ) : TlGen_DefaultHistoryTTL() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeInt32(period)
    }

    public companion object {
      public const val MAGIC: UInt = 0x43B46B20U
    }
  }
}
