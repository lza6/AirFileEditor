package com.example.tfgwj.model

/**
 * V6.0.0 Cloud Rules Engine
 * 云端动态规则数据模型
 */
data class DynamicRule(
    val version: String,
    val targetPackage: String,
    val configOffsetPath: String,
    val enabled: Boolean,
    val description: String,
)

data class CloudConfigResponse(
    val rules: List<DynamicRule>,
)
