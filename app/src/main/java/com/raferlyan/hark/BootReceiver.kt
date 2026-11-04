package com.raferlyan.hark

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 开机（以及应用升级后）自动启动引擎。
 *
 * 普通安装的应用在 Android 14+ 上不允许从 BOOT_COMPLETED 启动麦克风类型的前台服务，
 * 服务那边会起不来，改成发一条「点此启动」通知。预置成 persistent 系统应用时由 [HarkApp] 拉起，
 * 不受这条限制，这里只是兜底（服务已经在跑就什么都不做）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> autoStart(ctx, intent.action!!)
        }
    }

    companion object {
        fun autoStart(ctx: Context, why: String) {
            if (!AppSettings.load(ctx).autoStart) return
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AssistantBus.log("自动启动跳过（$why）：没有录音权限")
                return
            }
            AssistantBus.log("自动启动（$why）")
            runCatching { AssistantService.send(ctx, AssistantService.ACTION_BOOT) }
                .onFailure { AssistantBus.log("自动启动失败：${it.javaClass.simpleName}: ${it.message}") }
        }
    }
}
