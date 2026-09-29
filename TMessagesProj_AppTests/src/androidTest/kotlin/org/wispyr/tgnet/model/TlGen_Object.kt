package org.wispyr.tgnet.model

import org.wispyr.tgnet.OutputSerializedData

public interface TlGen_Object {
    fun serializeToStream(stream: OutputSerializedData)
}