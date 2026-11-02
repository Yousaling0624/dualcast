package com.dualcast.live

import android.content.Context
import com.dualcast.live.data.SettingsRepository
import com.dualcast.live.data.net.BiliApi
import com.dualcast.live.data.net.BiliLogin
import com.dualcast.live.data.net.LiveHttp
import com.dualcast.live.data.net.RoomResolver

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val http = LiveHttp()
    val biliApi = BiliApi(http)
    val biliLogin = BiliLogin(http)
    val resolver = RoomResolver(biliApi)
    val settings = SettingsRepository(appContext)
}
