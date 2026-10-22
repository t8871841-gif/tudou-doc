把 sherpa-onnx 的预编译 aar 放在这个目录下，文件名必须是：

    sherpa-onnx-1.12.23.aar

下载地址（本次交付环境没有公网访问，没法替你们下载）：
https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.12.23/sherpa-onnx-1.12.23.aar

不放这个文件，app/build.gradle 里的 `implementation files('libs/sherpa-onnx-1.12.23.aar')`
会直接导致 Gradle 配置失败（找不到文件），整个工程编译不过。
