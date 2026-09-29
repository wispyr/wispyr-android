package org.wispyr.tgnet.model.generated

import kotlin.Int
import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_Timezone : TlGen_Object {
  public data class TL_timezone(
    public val id: String,
    public val name: String,
    public val utc_offset: Int,
  ) : TlGen_Timezone() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(id)
      stream.writeString(name)
      stream.writeInt32(utc_offset)
    }

    public companion object {
      public const val MAGIC: UInt = 0xFF9289F5U
    }
  }
}
