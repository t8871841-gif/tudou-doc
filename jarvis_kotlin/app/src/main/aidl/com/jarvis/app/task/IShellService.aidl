package com.jarvis.app.task;

// Shizuku UserService 的 shell 通道：客户端（ShizukuHelper）经 bindUserService 拿到
// 这个接口，在 shell 进程里跑一条命令。
//
// 回 3 个 String：[0] = 退出码（十进制字符串；超时或起不来 = -1）、[1] = stdout、
// [2] = stderr。只一个方法，String[] 是 AIDL 原生支持的类型，不用自定义 Parcelable。
// 服务端把输出各截到约 2000/500 字，客户端再按老样子拼（stderr 非空时缀在后面）。
interface IShellService {
    String[] runCommand(in String[] cmd, long timeoutMs);
}
