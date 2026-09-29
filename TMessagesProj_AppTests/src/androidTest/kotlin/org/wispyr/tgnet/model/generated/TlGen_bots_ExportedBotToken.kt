package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_bots_ExportedBotToken : TlGen_Object {
  public data class TL_bots_exportedBotToken(
    public val token: String,
  ) : TlGen_bots_ExportedBotToken() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(token)
    }

    public companion object {
      public const val MAGIC: UInt = 0x3C60B621U
    }
  }
}
