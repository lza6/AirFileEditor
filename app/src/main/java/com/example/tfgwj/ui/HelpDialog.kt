package com.example.tfgwj.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin

/**
 * 帮助对话框
 * 显示软件使用说明和教程（支持 Markdown 渲染）
 */
class HelpDialog(context: Context) : Dialog(context) {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        val scrollView = ScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        // 使用 Markwon 渲染 Markdown
        val markwon = Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .build()
        
        val textView = TextView(context).apply {
            setPadding(48, 48, 48, 48)
            setLineSpacing(8f, 1.2f)
        }
        
        markwon.setMarkdown(textView, HELP_CONTENT.trimIndent())
        
        scrollView.addView(textView)
        setContentView(scrollView)
        
        // 设置对话框宽度
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    
    companion object {
        fun show(context: Context) {
            // 使用 Markwon 渲染 Markdown
            val markwon = Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .build()
            
            val scrollView = NestedScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    600  // 固定高度，避免对话框过长
                )
            }
            
            val textView = TextView(context).apply {
                setPadding(48, 32, 48, 32)
                setLineSpacing(4f, 1.1f)
            }
            
            markwon.setMarkdown(textView, HELP_CONTENT.trimIndent())
            scrollView.addView(textView)
            
            MaterialAlertDialogBuilder(context)
                .setTitle("📖 使用说明")
                .setView(scrollView)
                .setPositiveButton("我知道了", null)
                .show()
        }
        
        private const val HELP_CONTENT = """
# 📖 听风改文件 使用说明 (v3.1.0)

---

## 🚀 核心更新 (Omni-Mode)

本版本引入了全新的 **全能模式 (Omni-Mode)**，旨在为所有安卓版本提供最稳健的文件管理方案：

- **智能梯队**：系统自动探测并排序最佳模式 (`Root` -> `Shizuku` -> `原生`)。
- **并发控制**：普通模式新增**分批处理机制**，完美解决 6000+ 文件导致的内存爆炸和闪退问题。
- **无感适配**：自动识别鸿蒙 (HarmonyOS) 及安卓 11+ 的特殊权限要求。

---

## 🎯 软件用途

精细化的**和平精英**游戏文件管理工具：

- **完整替换** - 将 Android 目录下的所有子项完整复制到游戏目录。
- **主包与小包** - 支持全量资源替换与轻量级配置更新。
- **时间混淆** - 修改文件修改时间，规避常规检测。

---

## 🛠️ 工作模式详解

### 1️⃣ Root 极速模式
- **要求**：设备已获取 Root 权限。
- **性能**：最高。直接调用核心指令，瞬时完成数千个文件的替换。
- **提示**：首次使用需在权限管理器中允许“听风改文件”访问 Root。

### 2️⃣ Shizuku 授权模式
- **要求**：已安装并运行 Shizuku 服务。
- **性能**：极高。适用于安卓 11+ 且没有 Root 的设备。
- **提示**：请确保 Shizuku 服务处于运行中并已对本软件授权。

### 3️⃣ 原生 (Native) 普通模式
- **要求**：无特殊要求。
- **稳定性**：最强。采用 32 并发分批写入，**针对极多小文件进行了崩溃防护处理**。
- **HarmonyOS 适配**：系统会自动探测您的鸿蒙版本，如果环境允许，将优先使用原生访问，**无需强制启动 Shizuku**。
- **提示**：建议作为高级模式无法使用时的保底方案。

---

## ⚙️ 核心逻辑说明

### 替换逻辑 (Copy-Only)
- ✅ **新项** -> 新增。
- ✅ **已有项** -> 直接覆盖。
- ✅ **非冲突项** -> 完整保留。
- ⚠️ **注意**：替换过程不会删除游戏目录原有的其他文件。

---

## 🔧 进阶功能

| 功能 | 说明 |
|------|------|
| 🎲 一键随机 | 将主包内所有文件的时间属性改为随机的未来时间 (2027-2029)。 |
| 🛡️ 锁定时间 | 记录当前选定的时间点，以便后续多次替换保持一致。 |
| 🔍 环境验证 | 实时探测当前包名关联的目录是否可读、可写。 |
| 🧹 清理环境 | 一键删除目标目录中的 OBB/Data 相关缓存文件。 |

---

## 📞 联系与反馈

- **微信**: Tf00798
- **GitHub**: [AirFileEditor](https://github.com/lza6/AirFileEditor)
- **日志**: 若遇到问题，请将 `/听风改文件/logs/app_log.txt` 发送给开发者。

---

> **温馨提示**：替换文件前请务必确认游戏已彻底关闭，并建议先在历史记录中创建备份。
"""
    }
}

