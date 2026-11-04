package com.raferlyan.hark

import android.app.Application
import android.content.pm.ApplicationInfo

/**
 * 预置为 persistent 系统应用时，系统在用户解锁后（没有锁屏密码就是开机后）主动拉起这个进程，
 * 比等 BOOT_COMPLETED 广播早；进程崩溃也会被系统自动重启。进程一起来就启动引擎。
 * 普通安装的应用 persistent 不生效，这里什么都不做，照常由用户或 [BootReceiver] 启动。
 */
class HarkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val flags = applicationInfo.flags
        if (flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_PERSISTENT != 0) {
            BootReceiver.autoStart(this, "persistent 进程启动")
        }
    }
}
