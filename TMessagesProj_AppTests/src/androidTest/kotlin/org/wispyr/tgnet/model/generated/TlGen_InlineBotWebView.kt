package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_InlineBotWebView : TlGen_Object {
  public data class TL_inlineBotWebView(
    public val text: String,
    public val url: String,
  ) : TlGen_InlineBotWebView() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(text)
      stream.writeString(url)
    }

    public companion object {
      public const val MAGIC: UInt = 0xB57295D5U
    }
  }
}
