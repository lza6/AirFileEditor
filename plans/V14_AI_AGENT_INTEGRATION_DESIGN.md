# V14.0.0 AI Agent 赋能：自然语言管理文件

## 1. 场景描述
用户说：“帮我把刚才下载的和平精英补丁应用到游戏里，顺便清理一下缓存。”

## 2. 系统架构 (Client-Side AI)

### 2.1 语义解析层 (NLU)
- 集成 Google Gemini Nano (端侧模型)。
- 将用户指令解析为 `Action(type=REPLACE, source=Download/patch.zip, target=com.tencent.tmgp.pubgmhd)`.

### 2.2 规则自动生成
- AI 根据用户提供的压缩包结构，自动推断出目标目录，无需用户手动选择 `Omni-Mode`。

## 3. 实现步骤
1.  **接入 SDK**: 引入 Google AI Edge SDK。
2.  **构建 Prompt**: 为文件操作场景定制高精度的 System Prompt。
3.  **UI 集成**: 增加一个语音/文字输入入口，替代繁杂的按钮操作。
