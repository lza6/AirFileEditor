// ICopyCallback.aidl
package com.example.tfgwj;

// 复制进度回调接口
interface ICopyCallback {
    // 进度回调
    // current: 当前处理的文件数
    // total: 总文件数
    // currentFile: 当前正在处理的文件名
    void onProgress(int current, int total, String currentFile);
    
    // 完成回调
    // successCount: 成功复制的文件数
    void onCompleted(int successCount);
    
    // 错误回调
    void onError(String message);
}
