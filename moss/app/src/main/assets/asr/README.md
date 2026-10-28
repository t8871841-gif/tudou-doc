# 离线识别模型（不进 git）

`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20` 的四个文件放在这个目录下（跟这个 README.md 平级），文件名必须完全一致：

    encoder-epoch-99-avg-1.int8.onnx
    decoder-epoch-99-avg-1.onnx
    joiner-epoch-99-avg-1.int8.onnx
    tokens.txt

来源（CI 在构建时下载，见 `.github/workflows/build-moss-apk.yml`）：

https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2

缺文件时 `OfflineAsrEngine.assetsPresent()` 返回 false，自检页“离线模型”一行会如实显示缺模型文件，不会假装能用；这个 README.md 本身不影响运行。
