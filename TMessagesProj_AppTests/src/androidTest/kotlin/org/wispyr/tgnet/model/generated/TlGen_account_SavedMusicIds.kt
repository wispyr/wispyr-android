package org.wispyr.tgnet.model.generated

import kotlin.Long
import kotlin.UInt
import kotlin.collections.List
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_account_SavedMusicIds : TlGen_Object {
  public data object TL_account_savedMusicIdsNotModified : TlGen_account_SavedMusicIds() {
    public const val MAGIC: UInt = 0x4FC81D6EU

    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
    }
  }

  public data class TL_account_savedMusicIds(
    public val ids: List<Long>,
  ) : TlGen_account_SavedMusicIds() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      TlGen_Vector.serializeLong(stream, ids)
    }

    public companion object {
      public const val MAGIC: UInt = 0x998D6636U
    }
  }
}
