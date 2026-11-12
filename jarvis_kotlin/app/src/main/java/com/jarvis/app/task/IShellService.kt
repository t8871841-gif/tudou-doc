package com.jarvis.app.task

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel

/**
 * Shizuku UserService 的 shell 通道（手写 Binder，不用 .aidl 文件）。
 *
 * 为什么不用 AIDL：jk-v75 第一次构建在 CI 上失败——日志里根本没有 compileDebugAidl
 * 这一项，生成的 IShellService 类不存在，Kotlin 报 Unresolved reference。手写 Binder
 * 不依赖任何代码生成：服务端（ShellUserService）继承 android.os.Binder、重写 onTransact，
 * 客户端（ShellServiceProxy）用 IBinder.transact 发包。协议只有一个方法，收发都在这一个文件里。
 */
interface IShellService : IInterface {

    fun runCommand(cmd: Array<String>?, timeoutMs: Long): Array<String>

    companion object {
        const val DESCRIPTOR = "com.jarvis.app.task.IShellService"
        // = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_runCommand = 1

        fun asInterface(obj: IBinder?): IShellService? {
            if (obj == null) return null
            val local = obj.queryLocalInterface(DESCRIPTOR)
            if (local is IShellService) return local
            return ShellServiceProxy(obj)
        }
    }
}

/** 客户端代理：把调用封成 Parcel 发过去，等回包拆出来。服务端挂了抛 RemoteException，调用方按失败处理。 */
private class ShellServiceProxy(private val remote: IBinder) : IShellService {

    override fun asBinder(): IBinder = remote

    override fun runCommand(cmd: Array<String>?, timeoutMs: Long): Array<String> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IShellService.DESCRIPTOR)
            data.writeStringArray(cmd)
            data.writeLong(timeoutMs)
            remote.transact(IShellService.TRANSACTION_runCommand, data, reply, 0)
            reply.readException()
            return reply.createStringArray() ?: arrayOf("-1", "", "null result")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
