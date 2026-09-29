package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_BankCardOpenUrl : TlGen_Object {
  public data class TL_bankCardOpenUrl(
    public val url: String,
    public val name: String,
  ) : TlGen_BankCardOpenUrl() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(url)
      stream.writeString(name)
    }

    public companion object {
      public const val MAGIC: UInt = 0xF568028AU
    }
  }
}
