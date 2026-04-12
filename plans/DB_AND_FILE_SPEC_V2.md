# 数据库与元数据规格指南 (V10 预备)

## 1. 存储架构升级
从单一的 `Preferences (DataStore)` 升级为 `Hybrid Storage (DataStore + Room)`。

## 2. 数据库设计 (db_structure.md)

### 表 1: `replace_history` (替换记录表)
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | 唯一ID |
| timestamp | Long | 执行时间 |
| source_package | String | 来源包名/路径 |
| target_dir | String | 目标目录 |
| result_json | String | 记录每个文件的 MD5, 修改时间, 状态 |
| snapshot_tag | String | 备份快照标识 (用于一键回滚) |

### 表 2: `dynamic_rules` (动态规则缓存)
| 字段 | 类型 | 说明 |
|------|------|------|
| rule_id | String | 规则唯一标识 |
| version | Int | 规则版本 |
| json_content | Text | 混淆后的规则正文 |
| expiry_date | Long | 缓存过期时间 |

## 3. 文件存储规范
- `cache/extracted/`: 存放临时解压出的文件，任务结束后必须强制清理。
- `files/backups/`: 存放 V10 回滚机制产生的旧文件快照。
- `files/logs/`: 结构化 JSON 日志。

## 4. 清理建议
- V10 启动时自动检查 `cache/` 目录。
- 超过 3 天的备份文件自动清理，防止占用过多存储空间资源。
