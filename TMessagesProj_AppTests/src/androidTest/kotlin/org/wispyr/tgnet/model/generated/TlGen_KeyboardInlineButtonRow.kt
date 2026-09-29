package org.wispyr.tgnet.model.generated

import kotlin.UInt
import kotlin.collections.List
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_KeyboardInlineButtonRow : TlGen_Object {
  public data class TL_keyboardInlineButtonRow(
    public val buttons: List<TlGen_KeyboardInlineButton>,
  ) : TlGen_KeyboardInlineButtonRow() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      TlGen_Vector.serialize(stream, buttons)
    }

    public companion object {
      public const val MAGIC: UInt = 0x19420AF6U
    }
  }
}
