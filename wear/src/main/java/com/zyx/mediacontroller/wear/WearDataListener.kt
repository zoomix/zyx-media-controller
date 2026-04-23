package com.zyx.mediacontroller.wear

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable

class WearDataListener(
    context: Context,
    private val onStateChanged: (MediaStatePayload) -> Unit,
) : DataClient.OnDataChangedListener {
    private val appContext = context.applicationContext
    private val dataClient = Wearable.getDataClient(appContext)
    private var isListening = false

    fun start() {
        if (isListening) return
        isListening = true
        dataClient.addListener(this)
        dataClient.dataItems.addOnSuccessListener { buffer ->
            buffer.use {
                for (item in it) {
                    handleDataItem(item)
                }
            }
        }
    }

    fun stop() {
        if (!isListening) return
        isListening = false
        dataClient.removeListener(this)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type == DataEvent.TYPE_CHANGED) {
                    handleDataItem(event.dataItem)
                }
            }
        }
    }

    private fun handleDataItem(dataItem: DataItem) {
        if (dataItem.uri.path != MediaProtocol.statePath) return
        val json = DataMapItem.fromDataItem(dataItem)
            .dataMap
            .getString(MediaProtocol.jsonKey)
            ?: return

        onStateChanged(MediaStatePayload.fromJson(json))
    }
}
