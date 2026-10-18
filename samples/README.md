---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 0f1ca96c22636fc717b9a0c884521552_6bf2bd0ccadd11f1acd7525400cd780f
    ReservedCode1: sbHZrd4dn27JV4Vft2h2xPSnrdmu3qrU1k/S2PeGhOy37cdrM0PhkkkXSOl6N6o1qdaXRTcZfvVxpaLZyXDZa67+9ScRBaI/485ciCU5ngLneMD7P+IFSxgjpDT7aufKro8g14HSU6fZ3gbBL26CloSYjyYvAWyQ9LxpHHrTDn1lxSRNo/2xWTN7bPA=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 0f1ca96c22636fc717b9a0c884521552_6bf2bd0ccadd11f1acd7525400cd780f
    ReservedCode2: sbHZrd4dn27JV4Vft2h2xPSnrdmu3qrU1k/S2PeGhOy37cdrM0PhkkkXSOl6N6o1qdaXRTcZfvVxpaLZyXDZa67+9ScRBaI/485ciCU5ngLneMD7P+IFSxgjpDT7aufKro8g14HSU6fZ3gbBL26CloSYjyYvAWyQ9LxpHHrTDn1lxSRNo/2xWTN7bPA=
---

# samples/ —— 测试样本放置说明

本仓库**不附带任何厂商固件样本**。固件及其内含资源的版权归各设备厂商所有，
仓库只提供解析工具本身。

## 为什么这里（可能）是空的

`build.bat selftest` / `build.sh selftest` 需要在 `samples/` 下找到 5 个样本文件
才能跑回归自检：

| 文件名 | 用途 | 基线指纹记录于 |
| --- | --- | --- |
| `ROCK.RFW` | 瑞芯微整包固件（裸图段 + 子镜像） | `baseline/baseline.json` |
| `OPPOS9.RKW` | OPPO S39 整包固件（RKW 外壳） | 同上 |
| `BMP0.BIN` | BMP0 资源包（图片表） | 同上 |
| `U6.BIN` | 加密段样本 | 同上 |
| `Image.code.bin` | 加密段样本 | 同上 |

缺样本时 `--selftest` 会跳过对应用例并报告 SKIP，不影响编译与正常解包功能。

## 怎么自己准备

1. 使用你**合法持有**的设备的固件（官方升级包、自己设备 dump 出来的镜像）；
2. 放进本目录，文件名与上表一致（或改用 `--samples <目录>` 指定路径）；
3. 如果你的样本与作者的样本版本不同，指纹自然对不上 —— 这时应当更新
   `baseline/baseline.json`，而不是怀疑代码；具体做法见 `CONTRIBUTING.md`。

## 不要提交样本

`.gitignore` 已排除 `samples/` 下的固件文件（`*.RFW` / `*.RKW` / `*.BIN` 等），
请勿用 `git add -f` 强行加入：一是体积巨大（单个样本可达数十 MB），
二是会引入版权与分发风险。
*（内容由AI生成，仅供参考）*
