package org.wispyr.tgnet.model.generated

import kotlin.Int
import kotlin.Long
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_StatsGroupTopInviter : TlGen_Object {
  public data class TL_statsGroupTopInviter(
    public val user_id: Long,
    public val invitations: Int,
  ) : TlGen_StatsGroupTopInviter() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeInt64(user_id)
      stream.writeInt32(invitations)
    }

    public companion object {
      public const val MAGIC: UInt = 0x535F779DU
    }
  }
}
