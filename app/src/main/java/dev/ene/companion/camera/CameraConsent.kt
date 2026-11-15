package dev.ene.companion.camera

import dev.ene.companion.connection.CameraShareView

/** 설정 창이나 시스템 권한 자체는 카메라를 열 수 없다. */
internal fun CameraShareView.shouldOpenCamera(connected: Boolean, permission: Boolean, resumed: Boolean): Boolean =
    connected && supported && available && permission && resumed && (question || (observe && observeAllowed))
