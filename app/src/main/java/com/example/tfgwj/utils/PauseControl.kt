package com.example.tfgwj.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay

/**
 * 全局暂停/恢复控制器
 * 用于协调所有耗时任务（解压、复制、清理）的暂停状态
 */
object PauseControl {
    
    // 是否处于暂停状态
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    
    // 互斥锁，用于保护状态变更
    private val mutex = Mutex()
    
    /**
     * 暂停任务
     */
    suspend fun pause() {
        mutex.withLock {
            if (!_isPaused.value) {
                _isPaused.value = true
                AppLogger.d("PauseControl", "⏸️ 任务已暂停")
            }
        }
    }
    
    /**
     * 恢复任务
     */
    suspend fun resume() {
        mutex.withLock {
            if (_isPaused.value) {
                _isPaused.value = false
                AppLogger.d("PauseControl", "▶️ 任务已恢复")
            }
        }
    }
    
    /**
     * 切换暂停/恢复状态
     */
    suspend fun toggle() {
        if (_isPaused.value) {
            resume()
        } else {
            pause()
        }
    }
    
    /**
     * 检查并等待（如果在暂停状态）
     * 在耗时循环中调用此方法
     */
    suspend fun waitIfPaused() {
        if (_isPaused.value) {
            AppLogger.d("PauseControl", "⏳ 等待恢复中...")
            while (_isPaused.value) {
                delay(200) // 每 200ms 检查一次
            }
            AppLogger.d("PauseControl", "Wait 结束，继续执行")
        }
    }
    
    /**
     * 重置状态（任务结束时调用）
     */
    suspend fun reset() {
        mutex.withLock {
            _isPaused.value = false
        }
    }
}
