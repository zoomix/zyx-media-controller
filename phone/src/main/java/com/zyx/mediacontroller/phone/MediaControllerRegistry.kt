package com.zyx.mediacontroller.phone

import android.media.session.MediaController

object MediaControllerRegistry {
    @Volatile
    var activeController: MediaController? = null
}
