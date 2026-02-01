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
# 📖 听风改文件 使用说明

---

## 🎯 软件用途

这是一款专为**和平精英**游戏设计的文件管理工具，核心功能：

- **替换游戏资源文件** - 将主包中的 Android 目录下的**所有子文件和子文件夹**完整复制到游戏的内部存储目录
- **管理主包和小包** - 主包是完整资源包，小包是增量更新包
- **修改文件时间戳** - 防止游戏检测文件修改

---

## 📁 核心概念

### 主包
完整的游戏资源文件夹，必须包含 `Android/data/com.tencent.tmgp.pubgmhd/` 目录结构。

**存放位置**: `/storage/emulated/0/听风改文件/[主包名]/`

### 小包
只包含部分更新文件（通常是 ini 配置文件），用于快速更新主包而不用替换整个主包。

**存放位置**: `/storage/emulated/0/听风改文件/[小包名]/`

---

## ⚙️ 工作原理

### 主包替换到游戏（核心功能）

1. **源目录**: 主包中的 `Android/` 目录下的所有内容
2. **目标目录**: 手机内部存储的 `/Android/data/com.tencent.tmgp.pubgmhd/`
3. **替换逻辑**:
   - ✅ **新文件/新文件夹** → 直接复制过去
   - ✅ **已存在的文件** → 直接覆盖（不删除）
   - ✅ **不存在于源的文件** → 保留不动（不删除）

### 小包更新主包

将小包中的 ini 文件复制到主包的 Config 目录，用于快速更新配置。

---

## 🔧 功能说明

### 1️⃣ 权限状态
- Android 11+ 需要 **Shizuku** 授权才能访问 `/Android/data/` 目录
- 显示当前权限状态，点击按钮一键授权

### 2️⃣ 主包区域

| 功能 | 说明 |
|------|------|
| 选择源文件夹 | 选择要替换的主包目录 |
| 从压缩包解压 | 解压 ZIP/7z/RAR 等格式到听风改文件夹 |
| **点击时间框** | 自定义选择日期时间 |
| 🎲 一键随机 | 修改为随机未来时间（2027-2029年） |
| 开始替换 | 将主包的 Android 目录复制到游戏目录 |

### 3️⃣ 更新主包区域

| 功能 | 说明 |
|------|------|
| 小包列表 | 显示已解压的小包（名称+大小+文件数） |
| 点击小包 | 预览包含的 ini 文件列表 |
| 红色删除按钮 | 删除不需要的小包 |
| 扫描压缩包 | 扫描手机中的压缩包并解压 |

---

## 📦 支持的压缩格式

`ZIP` `7z` `TAR` `GZ` `XZ` `TAR.GZ` `TAR.XZ`

---

## ⚠️ 注意事项

> **重要**: 替换前请确保游戏已完全关闭！

- Android 11+ 首次使用需安装并启动 Shizuku
- 替换前会检测存储空间是否足够
- 建议在 WiFi 环境下解压大型压缩包

---

## 📋 日志功能

本软件会自动记录操作日志，方便排查问题：

| 项目 | 说明 |
|------|------|
| 日志位置 | `/听风改文件/logs/app_log.txt` |
| 大小限制 | 10MB（超出自动截断） |
| 生命周期 | 每次启动清空上次日志 |

**如遇问题**：请将日志文件发送给开发者

---

## 📞 联系开发者

| 平台 | 信息 |
|------|------|
| **微信** | Tf00798 |
| **GitHub** | [AirFileEditor](https://github.com/lza6/AirFileEditor) |

点击顶部菜单栏的图标可快速联系
"""
    }
}

