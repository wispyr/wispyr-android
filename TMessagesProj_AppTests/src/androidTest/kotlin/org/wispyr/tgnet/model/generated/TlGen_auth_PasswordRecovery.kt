package org.wispyr.tgnet.model.generated

import kotlin.String
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_auth_PasswordRecovery : TlGen_Object {
  public data class TL_auth_passwordRecovery(
    public val email_pattern: String,
  ) : TlGen_auth_PasswordRecovery() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeString(email_pattern)
    }

    public companion object {
      public const val MAGIC: UInt = 0x137948A5U
    }
  }
}
