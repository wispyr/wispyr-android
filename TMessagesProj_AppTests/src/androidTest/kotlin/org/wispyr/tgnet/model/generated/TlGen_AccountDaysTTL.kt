package org.wispyr.tgnet.model.generated

import kotlin.Int
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_AccountDaysTTL : TlGen_Object {
  public data class TL_accountDaysTTL(
    public val days: Int,
  ) : TlGen_AccountDaysTTL() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeInt32(days)
    }

    public companion object {
      public const val MAGIC: UInt = 0xB8D0AFDFU
    }
  }
}
