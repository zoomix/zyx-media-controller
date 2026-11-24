package se.kvrgic.mediawristremote.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

object MediaStatePublisher {
    private const val logTag = "MediaStatePublisher"

    fun publish(context: Context, payload: MediaStatePayload) {
        val request = PutDataMapRequest.create(MediaProtocol.statePath).apply {
            dataMap.putString(MediaProtocol.jsonKey, payload.toJson())
            dataMap.putLong(MediaProtocol.updatedAtKey, System.currentTimeMillis())
        }

        Wearable.getDataClient(context)
            .putDataItem(request.asPutDataRequest().setUrgent())
            .addOnFailureListener { error ->
                Log.w(logTag, "Failed to publish media state", error)
            }
    }

    /** Full-resolution covers keyed by artwork token. Replaces the whole set, so covers no longer in use are dropped. */
    fun publishArtwork(context: Context, jpegByToken: Map<String, ByteArray>) {
        val request = PutDataMapRequest.create(MediaProtocol.artworkPath).apply {
            for ((token, jpeg) in jpegByToken) {
                dataMap.putAsset(token, Asset.createFromBytes(jpeg))
            }
        }

        Wearable.getDataClient(context)
            .putDataItem(request.asPutDataRequest().setUrgent())
            .addOnFailureListener { error ->
                Log.w(logTag, "Failed to publish artwork", error)
            }
    }
}
