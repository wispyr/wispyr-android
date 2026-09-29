package org.wispyr.tgnet.model.generated

import kotlin.UInt
import org.wispyr.tgnet.OutputSerializedData
import org.wispyr.tgnet.model.TlGen_Object
import org.wispyr.tgnet.model.TlGen_Vector

public sealed class TlGen_stats_StoryStats : TlGen_Object {
  public data class TL_stats_storyStats(
    public val views_graph: TlGen_StatsGraph,
    public val reactions_by_emotion_graph: TlGen_StatsGraph,
  ) : TlGen_stats_StoryStats() {
    public override fun serializeToStream(stream: OutputSerializedData) {
      stream.writeInt32(MAGIC.toInt())
      views_graph.serializeToStream(stream)
      reactions_by_emotion_graph.serializeToStream(stream)
    }

    public companion object {
      public const val MAGIC: UInt = 0x50CD067CU
    }
  }
}
