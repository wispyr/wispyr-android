package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_payments_StarsRevenueWithdrawalUrl : TlGen_Object {
  public data class TL_payments_starsRevenueWithdrawalUrl(
    public val url: String,
  ) : TlGen_payments_StarsRevenueWithdrawalUrl() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(url)
    }

    public companion object {
      public const val MAGIC: UInt = 0x1DAB80B7U
    }
  }
}
