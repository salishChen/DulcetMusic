package com.mtechviral.musicfinderexample.core.common

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用前后台可见性（耗电优化，doc/耗电分析报告.md §5）。
 *
 * 播放进度的精细轮询只在**有可见消费者**（界面可见 / 悬浮歌词可见）时启用；
 * 退到后台后降频或停止，状态变化依赖播放器回调，不再固定 200ms 空转。
 */
object AppVisibility {

    private val _visible = MutableStateFlow(false)

    /** 是否有 Activity 处于可见状态（前台） */
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var started = 0

                override fun onActivityStarted(activity: Activity) {
                    if (started++ == 0) _visible.value = true
                }

                override fun onActivityStopped(activity: Activity) {
                    if (--started <= 0) {
                        started = 0
                        _visible.value = false
                    }
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
