package org.wispyr.tgnet.model.generated

import kotlin.Int
import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_InputDialogPeer : TlGen_Object {
  public data class TL_inputDialogPeer(
    public val peer: TlGen_InputPeer,
  ) : TlGen_InputDialogPeer() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      peer.serializeToStream(stream)
    }

    public companion object {
      public const val MAGIC: UInt = 0xFCAAFEB7U
    }
  }

  public data class TL_inputDialogPeerFolder(
    public val folder_id: Int,
  ) : TlGen_InputDialogPeer() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      stream.writeInt32(folder_id)
    }

    public companion object {
      public const val MAGIC: UInt = 0x64600527U
    }
  }

  public data class TL_inputDialogPeerCommunity(
    public val community: TlGen_InputChannel,
  ) : TlGen_InputDialogPeer() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      community.serializeToStream(stream)
    }

    public companion object {
      public const val MAGIC: UInt = 0x69EF72C4U
    }
  }
}
