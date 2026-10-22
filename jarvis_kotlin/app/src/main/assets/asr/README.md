把 sherpa-onnx 流式 zipformer（中英双语，2023-02-20）模型包里的这四个文件放在这个目录下
（跟这个 README.md 平级），文件名必须完全一致：

    encoder-epoch-99-avg-1.int8.onnx
    decoder-epoch-99-avg-1.onnx
    joiner-epoch-99-avg-1.int8.onnx
    tokens.txt

下载地址（本次交付环境没有公网访问，没法替你们下载/解压）：
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2

这几个文件加起来大概几十到上百 MB，解压后只取上面四个，别的文件（比如测试用的 wav）不用拷。
缺文件时 OfflineAsrSession.isAvailable() 会返回 false，自检页会如实显示"缺模型文件"，
不会假装能用；这个 README.md 本身不影响运行，随时可以删掉。
