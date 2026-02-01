// IDeleteCallback.aidl
package com.example.tfgwj;

// 删除进度回调接口
interface IDeleteCallback {
    // 进度回调
    // current: 当前已删除的项数
    // total: 总项数
    // currentItem: 当前正在删除的项名称
    void onProgress(int current, int total, String currentItem);
    
    // 完成回调
    // deletedCount: 成功删除的项数
    void onCompleted(int deletedCount);
    
    // 错误回调
    void onError(String message);
}
