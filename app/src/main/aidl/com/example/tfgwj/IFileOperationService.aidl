// IFileOperationService.aidl
package com.example.tfgwj;

// 导入回调接口
import com.example.tfgwj.ICopyCallback;
import com.example.tfgwj.IDeleteCallback;

// Shizuku UserService 文件操作接口
// 此接口定义在特权进程中执行的文件操作方法

interface IFileOperationService {
    
    // 销毁服务
    void destroy();
    
    // 检查服务是否存活
    boolean isAlive();
    
    // 创建目录
    boolean createDirectory(String path);
    
    // 删除文件或目录
    boolean deleteFile(String path);
    
    // 复制文件
    boolean copyFile(String sourcePath, String targetPath);
    
    // 复制目录（递归）
    boolean copyDirectory(String sourcePath, String targetPath);
    
    // 复制目录并带进度回调 (高性能优化版)
    void copyDirectoryWithProgress(String sourcePath, String targetPath, ICopyCallback callback);
    
    // 检查文件是否存在
    boolean fileExists(String path);
    
    // 执行 shell 命令
    int executeCommand(String command);
    
    // 获取命令执行输出
    String executeCommandWithOutput(String command);
    
    // 停止应用
    boolean stopApp(String packageName);
    
    // 检查应用是否运行
    boolean isAppRunning(String packageName);
    
    // 清理环境（删除指定目录下的所有内容，可指定白名单）
    // basePath: 基础目录路径
    // whiteList: 白名单数组（不删除这些项）
    // callback: 进度回调
    void cleanDirectoryWithProgress(String basePath, in String[] whiteList, IDeleteCallback callback);
}
