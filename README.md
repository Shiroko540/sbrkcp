# sbrkcp

> 版本 **0.0.2**（Beta，CLI 参数与模块 API 可能变动）· Java 8 字节码 · GPL-3.0

喵～这里是 sbrkcp 的小窝，主人请慢慢看这份说明喵。

早期瑞芯微（Rockchip）MP3/MP4 固件解包工具集，纯 Java（Java 8 字节码）+ Swing。
目标设备与方案：RK27 / RK28 系列（蓝魔 RM970、OPPO S39 等），解包对象为整机固件
（`.RFW`）、资源包（`.RKW`）、图片归档（`BMP0.BIN`）等。

项目采用 **boot + 模块** 的插件式结构：`boot.jar` 负责 GUI、模块加载与公共解析内核，
每个格式一个独立模块 jar，放进 `dist/main` 或 `dist/other` 即被自动发现，删除即下线喵。

0.0.2 起除了解包，还多了两件「先看清再动手」的事：

- **字符串抽取**（`--headless strings` / GUI 内置「界面文案」）：按 10 条策略从固件或目录里
  捞结构化文案与裸扫候选，可导出 CSV / TXT / JSON / Markdown；
- **ROCK 表分析**（`--headless rock`）：解析 `ROCK` 头 + 16 字节目录表容器，产出条目表、
  分区地图、块分类、文本与字库/像素 PNG（CLI 专属，暂无 GUI 入口）。

### 关于适用范围的一点坦白

> **本工具只在一小部分固件的、一小部分容器上真机验证过**（具体样本与门禁见第 6 节，
> 未覆盖的部分见第 7 节）。因此**无法保证 100% 成功，也无法保证解包结果完美无瑕**喵。
> 换个固件版本、换个容器包装、遇到没见过的怪块，翻车都是很可能发生的事。
>
> **特别声明：RK26 系列等部分固件目前暂时不可用。** 手上 `ROCK.RFW`
> （`RK2602A_SDK_MLC_DRM_APE.rfw`）这类 RK26 固件，它的 `ROCK` 头不在文件 0 偏移，
> 而是压在外层 RFW 头之后的 `0x200` 处；当前 `ROCK` 签名判定只认文件首 4 字节，
> 所以直接喂进去一律「未命中 ROCK 目录表」，`rock` 子命令对它是 exit 1。
> 这不是「数据坏了」，而是**入口还没支持嵌套偏移的头**，详见第 7 节第 1 条。
>
> 这份代码更像是一个还在长大的毛坯，欢迎大家多多完善、多提 Issue / PR，
> 把手上的真实样本和踩坑结论一起喂给它，让它早点学会更多格式喵～

---

## 1. 目录结构

```
sbrkcp/
├── build.bat / build.sh        构建脚本（编译 + 打包 + 自检）
├── pack_exe.bat                自包含 EXE 打包（jlink 运行时 + jpackage app-image + zip）
├── packaging/README_EXE.txt    EXE 包内的使用说明模板
├── baseline/baseline.json      回归基线（样本 MD5 + 期望解包计数）
├── samples/                    样本固件（不入库，自行放置）
├── tools/bmp0_ref.py           BMP0.BIN 独立参考解码器（对拍用，零依赖，不进产品路径）
├── tools/fat_ref.py            FAT12/16/32 独立参考解码器（对拍用，零依赖，不进产品路径）
├── src/
│   ├── api/     sbrkcp.api     模块契约：SbrkcpModule / ModuleInfo / UnpackOptions /
│   │                            UnpackResult / ProgressListener / AbstractModulePanel
│   ├── core/    sbrkcp.core    解析内核：BigFile / Firmware / Rkrs / RawImage / Fat / Engine
│   │   ├── rock/               ROCK 头 + 16 字节目录表分析（0.0.2）
│   │   │                        RockTable / RockAnalyzer / RockReport / RockStats
│   │   └── text/               字符串抽取内核（0.0.2）
│   │                            StringsProbe / StringTable / StringsExport / StringsEntry /
│   │                            BareScanner / ArchiveProbe / CryptoDiag
│   ├── boot/    sbrkcp.boot    BootMain / Version / ModuleLoader / MainFrame / LogBus /
│   │                            SelfTest / MiniJson
│   │                            RockCli / StringsCli / StringsModule / StringsPanel（0.0.2）
│   └── modules/
│       ├── fuckrkw/             .RKW 资源包解包
│       ├── fuckrfw/             .RFW 整机固件解包
│       ├── fuckrkimg/           FAT 镜像整盘解包（L1） / 未知·加密镜像识别
│       └── fuckbmp0/            BMP0.BIN 图片归档解包
├── dist/
│   ├── boot.jar                主程序（含 api + core）
│   ├── main/
│   │   ├── fuckrkw.jar
│   │   ├── fuckrfw.jar
│   │   ├── fuckrkimg.jar
│   │   └── fuckit/rockchip/    预留目录（按芯片族归档的扩展位）
│   └── other/
│       └── fuckbmp0.jar        BMP0 属于子资源，归入 other
└── out/                        解包产物落盘位置（手工指定）
```

**关键约束**：模块 jar 内不得出现 `sbrkcp/api` 或 `sbrkcp/core` 下的类，模块只通过
`boot.jar` 暴露的 api 与 core 公共静态方法工作；模块加载失败不会影响其它模块。

---

## 2. 构建

要求 JDK 9+（用 `--release 8` 产出 Java 8 兼容字节码，运行期 Java 8 即可）。

```bat
:: Windows
build.bat              :: 编译 + 打包
build.bat selftest     :: 编译 + 打包 + 回归自检
build.bat clean
```

```sh
# POSIX
./build.sh
./build.sh selftest
./build.sh clean
```

产物：`dist/boot.jar`、`dist/main/*.jar`、`dist/other/fuckbmp0.jar`。

> 注意喵：模块编译必须带 `--release 8`（或 `-source 8 -target 8`），否则 JDK 21 会产出
> class 版本 65，在 Java 8 运行时抛 `UnsupportedClassVersionError`，表现为“模块全部加载失败”。

---

## 3. 运行

### 3.1 图形界面

```bat
java -jar dist\boot.jar
```

左侧模块列表，右侧为该模块的参数面板；选择输入文件与输出目录后点“开始解包”，
日志实时输出，随时可以中断喵。

模块列表里除了 `dist/main`、`dist/other` 的 jar 模块，0.0.2 起还**内置**了一个
「界面文案」入口（id `strings`，归在“其他功能”下）：

- 它**不是**独立模块 jar，由 `boot.jar` 里的 `StringsModule` 直接注册，与 jar 模块并列显示；
- 因此它**不会**出现在 `--list-modules` 里（那边只列 `main/`、`other/` 下真实加载到的 jar），
  也不受模块加载失败影响；
- 面板提供「结构化 / 裸扫 / 全部合并」三个视图与「合并去重」按钮，输入可以是单个固件，
  也可以是一个目录。

> 注意：`GUI` 里目前**没有** ROCK 表分析的入口，`rock` 只有命令行一条路（见 §3.2、§7）。

### 3.2 命令行

```bat
:: 打印版本号（含通道、boot apiVersion、Java 版本）
java -jar dist\boot.jar --version

:: 列出模块及加载状态
java -jar dist\boot.jar --list-modules

:: 无头解包
java -jar dist\boot.jar --headless --module fuckrkw --in samples\OPPOS9.RKW --out out\rkw

:: 只列条目不落盘
java -jar dist\boot.jar --headless --module fuckrkw --in samples\OPPOS9.RKW --out out\rkw --list

:: 纵向翻转
java -jar dist\boot.jar --headless --module fuckbmp0 --in samples\BMP0.BIN --out out\bmp0 --flip

:: 回归自检（退出码 0 通过 / 4 失败）
java -Dsbrkcp.base=dist -jar dist\boot.jar --selftest --samples samples
```

#### 字符串抽取（0.0.2）

```bat
:: 从资源包/固件里抠界面文案（S1–S10 全跑，默认输出 CSV）
java -jar dist\boot.jar --headless strings samples\OPPOS9.RKW --out out\s_rkw

:: 多格式同时导出 + 中英对照 + 去重 + 去尾空白
java -jar dist\boot.jar --headless strings samples\OPPOS9.RKW --out out\s_rkw ^
     --format csv,txt,json,md --lang both --dedup --strip

:: 只跑指定策略、提高最短长度、限制裸扫条数、静默
java -jar dist\boot.jar --headless strings D:\dump\files --out out\s_dir ^
     --strategies S1,S4 --min-len 6 --bare-limit 3000 --quiet

:: 明确指定用哪个模块解外层容器（不指定则按 Engine.auto 自动识别）
java -jar dist\boot.jar --headless --module fuckrkw strings samples\OPPOS9.RKW --out out\s_rkw
java -jar dist\boot.jar --headless --module fuckrkimg strings samples\U6.BIN --out out\s_u6
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--format` | `csv` | 可逗号分隔多选：`csv,txt,json,md`；CSV 为 UTF-8 **带 BOM**（Excel 直接双击不乱码） |
| `--lang` | `both` | `cn` / `en` / `both`，控制中英对照列的保留方式 |
| `--dedup` / `--no-dedup` | `--no-dedup` | 归一化去重（同一条文案只留一条）；默认关，怕误合并不同界面的同名字符串 |
| `--strip` | 关 | 去掉首尾空白与控制字符 |
| `--strategies` | 全跑 S1–S10 | 只跑指定策略，逗号分隔，如 `S1,S4,S9` |
| `--min-len` | `0`（用内核默认） | 最短长度；中文取该值，英文自动取 `max(N,4)` |
| `--bare-limit` | `0`（用内核默认） | 裸扫候选条数上限 |
| `--quiet` | 关 | 只留错误与最终路径，不打策略详情 |

产出：`<stem>_界面文案_中英对照.<fmt>`、`<stem>_裸扫候选.<fmt>`、`probe_report.txt`
（逐策略状态、命中数、耗时、告警，以及 `_probe_unpack/` 下解包递归的明细）。
退出码：`0` 成功、`5` 未提取到任何文案（其余同全局退出码表）。

#### ROCK 表分析（0.0.2）

```bat
:: 单个文件
java -jar dist\boot.jar --headless rock D:\inner\SOMETHING.BIN --out out\rock

:: 目录（每个文件一个子目录）
java -jar dist\boot.jar --headless rock D:\inner --out out\rock --quiet
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--out` | 输入同级 `rock_out/` | 输出目录；目录输入时每个文件建一个子目录 |
| `--quiet` | 关 | 不打印每个文件的命中摘要 |

产出 7 个文件：`rock_table.csv`（逐条：偏移/大小/结束/flag/熵/零占比/可打印率/512 重复/分类/判据）、
`rock_partition.md`（分区地图）、`rock_blocks.csv`（块分类）、`rock_texts.csv`（可打印块）、
`rock_font_<i>_<w>x<h>.png`、`rock_pixel_<i>_<w>x<h>.png`、`rock_report.txt`（凭证与告警）。
未命中时**不创建任何产物**，只打印 `未命中 ROCK 目录表：<notes>` 并以 exit 1 结束。

`baseDir` 用于定位 `main/` 与 `other/`，默认为 `boot.jar` 所在目录，可用
`-Dsbrkcp.base=<dir>` 覆盖。

### 3.3 运行前准备：样本文件

厂商固件样本因版权与体积不入库（见第 9 节），仓库内 `samples/` 只有一份说明文件喵。
自行准备下列 5 个样本放入 `samples/` 后，回归自检才能跑满 6 项：

| 样本 | 覆盖模块 | 说明 |
|---|---|---|
| `ROCK.RFW` | fuckrfw | 整机固件，裸图段启发式识别 |
| `OPPOS9.RKW` | fuckrkw | 资源包，文件表 + 内嵌 RKRS |
| `BMP0.BIN` | fuckbmp0 | RKRS 图片块，含 RLE 扩展 |
| `U6.BIN` | fuckrkimg | 加密镜像，验证拒绝路径 |
| `Image.code.bin` | fuckrkimg | 加密镜像，验证拒绝路径 |

样本缺失时 `--selftest` 会输出 `PASS 1 / FAIL 5` 并以退出码 4 结束，这是缺少样本的
预期行为，不用惊慌喵，不是构建失败；构建本身的判定看 `[5/5]` 的 `已加载 4 / 失败 0`。

> 自检读取的基线路径由**样本目录的父目录**推导（`new File(samples.getParentFile(),
> "baseline/baseline.json")`）。因此样本目录必须与 `baseline/` 同级，默认结构即满足；
> 若把样本放到别处并读取那里的基线，指纹门禁会换成另一套期望值。

图形界面需要可用的桌面会话；纯 SSH / 无头环境请走命令行或用 `--smoke` 做启动自检。

---

## 4. 模块能力

### 4.1 模块职责边界（硬隔离）

每个模块**只负责自己那一种格式**，解出来的原始条目原样落盘，不替别的格式做解释喵：

- `fuckrkw` 只解 `KRDL` 外壳与文件表，`files/` 下的 `BMP0.BIN`、`.RKZ`、`BASE.RKW`
  一律**原样导出、不递归**；要拿图片请把 `files/RESOURCE/BID/BMP0.BIN` 拖给 `fuckbmp0`。
- `fuckrfw` 只解 `WFKR` 分区与分区内 RKRS/裸图段，不解释分区内的其它容器。
- `fuckbmp0` 只解 `BMP0.BIN`（RKRS 容器 + 8 字节块头）。
- `fuckrkimg` 只解 FAT12/FAT16/FAT32 文件系统（L1：目录树 → 文件原样落盘），
  不解析盘内任何业务格式；非 FAT 结构只做加密/未知判定，不产出。

这样拆分的原因：内嵌资源的解析依赖「当前文件就是该格式的完整文件」这一前提，
在外壳里就地递归时偏移基准与完整性校验容易错位，且某一格式的解析缺陷会污染上层模块的结果。
因此**没有任何递归开关**：`UnpackOptions` 不再有 `nested` / `maxDepth` 字段，CLI 不再有
`--nested`，GUI 面板也不再提供相关复选框。要图片就自己把 `files/` 下的条目交给
`fuckbmp0` 单独解。报告里只会记录「内嵌资源 N 个已原样导出，未做二次解析」。

| 模块 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `fuckrkw` | `.RKW` | 内嵌文件（原样，含 PNG 为 0） | KRDL 文件表 → 按原路径导出条目；不递归内嵌其它格式 |
| `fuckrfw` | `.RFW` | 分区原始文件 + PNG | WFKR 分区表 → 逐分区落盘 → 分区内 RKRS 或 0x200 头裸图段 |
| `fuckrkimg` | 任意镜像 | 文件树 + 清单 | FAT12/16/32 → 整盘解出 `files/`；非 FAT → 只输出加密/未知判定 |
| `fuckbmp0` | `BMP0.BIN` | PNG + 清单 | RKRS 容器 + 8 字节块头 + 32bit BGR / RLE 解码 |

每轮解包会在输出目录写 `unpack_report.txt`：识别类型、条目统计、逐条记录与告警。

---

## 5. 格式要点（实测结论）

### 5.1 RKRS 资源容器

- 容器头起点 = `"RKRS"` 魔数所在位置 **减 4**（即 `+0x00` 为 `0x28`，`+0x04` 为 `RKRS`）。
- `+0x30` = 索引表偏移（常见 `0x800`），`+0x3C` = 条目数，`+0x42` = BID 基值（常见 `0xB000`）。
- 索引项 8 字节一条：`length(int32)` + `offset(int32)`；**块定界应以相邻 offset 之差为准**，
  不要依赖 `length`——该字段相对实际跨度存在固定 `-2` 偏差，末条为 `-10`（含尾部 8 字节尾签）。
- 一致性校验：`dataOff == tableOff + count * 8`；尾部常见尾签 `28 00 00 00 52 4B 52 53`。

### 5.2 BMP0.BIN 图片块

- 块头有 **两种**，必须自适应，写死任一种都会整体错位：
  - **8 字节**（旧固件，样本 A 的 1270 条全部如此）：`width(u16) height(u16) depth(u16) comp(u16)`；
  - **14 字节**（RK28 SDK V1.2，样本 B 的 2624 条全部如此）：前 4 字段同上，`+8` 起再放
    `u16 mode` + `FF FF FF` + `CD CD CD CD CD CD`（mode 对 comp=5 无意义，仅作对齐/标记）。
  - RAW 类（comp 0/5）可由 `length - w*h*bpp` 唯一反推：恰等 8 或 14 直接采用；落在
    `(8,14]` 视为 8 字节头 + `0~6` 字节尾部残留；大于 14 视为 14 字节头 + 尾部残留。
  - RLE（comp 1）长度不可反推，只能看 `+8` 处 6 字节是否全 `0xCD`：命中取 14，否则取 8；
    解码未铺满时再按另一头长互试一次。
- `depth` 恒为 32：像素单元 4 字节 `B, G, R, A`（BGR 通道序，自上而下行优先，无行翻转）。
- `comp = 0` 未压缩：每像素 `B, G, R, 0x00`，第 4 字节是**填充位**（实测全 0），不是 alpha，
  当 alpha 用会让整幅图全透明；导出时统一补 `A=255`。
- `comp = 5` 未压缩但第 4 字节是**真实 alpha**（0~255 梯度），必须原样保留，
  既不能补 255，也不能参与色键改写。
- `comp = 1` 为 RK 非标准 RLE，token 仍是 4 字节 `B, G, R, count`，`count` 必须按**无符号**读：
  - `count = 0x01..0xFE`：该 BGR 重复 `count` 次；
  - `count = 0xFF`：扩展计数，后跟 4 字节**小端 uint32 `v`**，本次游程 `255 + v`
    （实测单次最长 95745，u8/u16 都读不下）；
  - `count = 0x00`：流终结符（数据尾部恒有 4 字节零），不产生像素。
  - 游程**跨行连续**（207 条 RLE 中 173 条的单个游程大于图宽），必须往一条线性的 `w*h`
    缓冲里连写，不能按行重置或截断；解完必须恰好铺满 `w*h`。
- 纯品红 `FF00FF` 为透明色键：只把 `alpha` 置 0，**RGB 原样保留**（导出像素为 `(255,0,255,0)`）。
  判定必须**逐像素、按 4 字节边界**做；用跨像素的字节模式扫描（如正则 `FF 00 FF ?`）
  会把上一像素的 `A` 与下一像素的 `B/G` 凑成假品红，连带污染第三个通道。

> 上述语义为两批真机样本全量对拍结论：样本 A（1270 条 / 8 字节头 / comp 0:1063、1:207）
> 与样本 B（2624 条 / 14 字节头 / comp 0:842、1:402、5:1380）均与独立参考解码器逐条像素
> MD5 **零差异**，样本 B 的 8 个金标准 MD5 探针全部命中。仍缺第三方资料佐证；
> 遇到异常图块时优先怀疑此处。

### 5.3 .RKW 资源包

- 头 `KRDL`；文件表条目含 `name(定长) + offset + size + type`，扫描需同时满足三条约束：
  `buf[i-1] == 0`、`buf[i+nextName]` 为可打印字符、`type ∈ 1..4`。
- 条目绝对地址 = **容器起始偏移 + 条目相对偏移**（`delta` 因样本而异，**不可写死**）。
- 去重键取 `name|offset|size`；条目按原相对路径（如 `RESOURCE/BID/BMP0.BIN`）导出。
  内嵌的其它格式**一律不递归**，交由对应模块单独处理（见 §4.1），无开关可改。

### 5.4 .RFW 整机固件

- 头 `WFKR`，分区表给出各分区 `offset/length`；先按分区原样落盘（便于回溯），
  再在分区数据内尝试 RKRS 容器；无容器则按“0x200 头裸图段”启发式扫描。
- 裸图段判据（当前校正版，2026-10-18）：
  - 单条记录长度 = `(0x200 + w*h*2 + 3) & ~3`（4 字节对齐；按 0x200 对齐会让部分分区整段断链归零）；
  - 宽高范围 `4..1024`；下界 4 是为了剔除由像素数据误判出的 1x1 / 1x2 退化伪头（8x8、16x16 这类真实图标仍保留）；
  - 记录必须**紧邻成链**，段内 `≥3` 条且总字节 `≥100KB` 才收段，收段后直接跳到段尾继续扫；
  - 记录头的 `+4` 处 u16 字段（记作 `f3`）在真实 UI 记录上恒为 `0x0018`，但它只覆盖部分分区
    （`part2`/`part3` 的前 31 张），其余资源取值为 `w`、`h` 或随机值。因此 **`f3==0x18` 只作可信度诊断，
    不作硬过滤**，否则会连带丢掉非 0x18 族的真实资源。

  样本 `ROCK.RFW` 据此得 4 分区 118 张，分区分布 `[6, 6, 53, 53]`。
- **可信段诊断**：`unpack_report.txt` 会逐段给出 `f3` 值与取值种类数，并汇总
  `裸图段 N 段 / M 张（可信段 a 段 / b 张）`；`f3` 整段同族且等于 `0x0018` 的段记为“可信”，
  其余标为“疑似伪段”并仍照常导出（不删数据，留待人工复核）。
  `ROCK.RFW` 实测：`part2`、`part3` 各 10 段 53 张，可信段均为 3 段 31 张；
  `part0`、`part1` 各 6 张**全部落在疑似伪段**（图像判读为噪声，非 UI）。
- 交叉校验：`part2` 与 `part3` 内容互为镜像，`--selftest` 会比对两者导出的尺寸序列，
  要求同位置一致率 ≥90%（实测 94%）。**可信段部分两者 31/31 完全一致，连 PNG 字节数都相同**；
  剩余 3 处不一致已定位为数据本身差异，非判据问题：
  - `part2`/`part3` 逐字节比对，在相对偏移 `0x850000..0xbef000` 存在约 3.5MB 真实内容差异
    （两分区并非 100% 同源），`0xbef000` 之后才完全一致；
  - 伪段区域（无 `0x0018` 头）的链式收段会让扫描进度产生相位差，`part2` 先在某处收段
    就跳过了 `part3` 才收段的那一段，于是同一区域被切出不同组合的伪头。
  这比追一个绝对张数更能反映判据是否稳定。

### 5.5 FAT 镜像（L1 解包）

- 只按 BPB 走，**不猜分区表**：`0x0B` 扇区字节、`0x0D` 扇区/簇、`0x0E` 保留扇区、
  `0x10` FAT 份数、`0x11` 根目录项数、`0x13` / `0x16` 为 16 位总扇区（为 0 时取 `0x20` 的 32 位值）；
  FAT 位宽由簇数反推：`< 4085` → FAT12，`< 65525` → FAT16，否则 FAT32。
- 数据区起点 = `(保留扇区 + FAT 份数 × 每 FAT 扇区数) × 扇区字节 + 根目录扇区数 × 扇区字节`；
  簇 `N` 物理位置 = `数据区起点 + (N - 2) × 簇字节`。**根目录不在簇区**（FAT12/16），
  必须先按固定偏移读，不能当普通目录走簇链。
- FAT12 表项为 12 bit 打包：`off = N + (N >> 1)`，奇数项取高 12 位、偶数项取低 12 位。
- 长文件名：`0x0F` 属性项的 `byte0` 低 5 位是序号，**必须按序号升序重排后再拼 UTF-16LE**
  （磁盘上是从高序号往低写的），`byte0 & 0x40` 标记最后一片；短名大小写标志在 `+0x0C`：
  `0x08` 只小写基名、`0x10` 只小写扩展名，**两者独立**，一起小写会把 `MUSIC1.MP3` 变成 `music1.mp3`。
- 目录一律**按簇链逐簇拼接**后再按 32 字节切项。禁止「首簇 + 顺序读下一簇」：
  真机 `RKmidity/BANK0` 就跨 2 簇且两簇在物理上不相邻，顺序读会读到别的文件内容。
- 簇链必须维护 `visited` 集合与深度上限（当前 12 层）：坏镜像里存在 `A→B→A` 的自环，
  无保护会无限递归。命中环记 `DIR-LOOP` 跳过，并计入异常统计。
- 条目 `size == 0` 直接建空文件、不读簇（`first_clus` 此时可能是 0 或残留值）。
- 输出布局：`<out>/files/<原路径>`，可选 `<out>/dump_manifest.csv`
  （`path,size,first_clus,read_bytes,md5`）与 `<out>/text/`（`.txt/.ime/.cfg/.rkz` 的 UTF-8 副本，
  原文件保留）。

**L1 门禁**（真机 `S39_10.b08.img`，213,909,536 B，FAT16 / 簇 4096 B）：

| 指标 | 期望 |
|------|------|
| 目录数 | 22 |
| 文件数 | 292 |
| 文件总字节 | 192,550,327 |
| 校验异常 | 0 |
| 顶层条目 | 10（7 目录 + 3 文件） |

实测该镜像与独立参考解码器 `tools\fat_ref.py` 的 292 条清单（路径 / 大小 / 首簇 /
实读字节 / MD5）**零差异**，抽样文件与同镜像手工导出树逐字节一致；
合成镜像（LFN 中文长名、跨簇目录、`size == 0`、`A→B→A` 目录环）亦零差异。

### 5.6 字符串抽取（0.0.2）

结果分两轨，互不混算：

- **结构化**：来自真正的字符串表（有表头、有索引、可还原条目边界），可信度高；
- **裸扫**：对整个字节流按编码启发式扫描出的可打印串，**天然含噪声**，条数多不等于有用。

十条策略（`--strategies` 的取值就是这几个编号）：

| 策略 | 做什么 | 实测（`OPPOS9.RKW`，23 MB） | 实测（`U6.BIN`，8 MB） |
|---|---|---|---|
| S1 | RKRS 字符串表解析 | hit 702 条，基址 `0x1315014`，score 2369.25 | miss（无自洽 RKRS 字段表） |
| S2 | 基址 / 偏移对齐枚举 | 候选 41 个，有效 1 个 | 候选 1 个，有效 0 |
| S3 | 解包 / 目录递归 | skip（单文件输入） | skip |
| S4 | UTF-16LE 裸扫 | hit 5000（达上限截断） | hit 5000（截断） |
| S5 | GBK 裸扫 | hit 5000（截断） | hit 5000（截断） |
| S6 | UTF-8 裸扫 | hit 6 | hit 13 |
| S7 | ASCII 裸扫 | hit 5000（截断） | hit 5000（截断） |
| S8 | 压缩容器解压 | miss（无可解压点，LZMA 无 JDK 实现） | miss |
| S9 | 加密 / 压缩诊断 | 熵 6.56 bit/B，判「非加密特征」 | 熵 6.80 bit/B，判「非加密特征」 |
| S10 | 归一化去重 | 15440 → 15440（默认不去重） | 14650 → 14650 |

合计：`OPPOS9.RKW` 结构化 702 + 裸扫 14738 = 15440 条；`U6.BIN` 结构化 0 + 裸扫 14650 条。

要点与坑：

- **S1 未命中时会自动解外层容器再递归**（`<out>\_probe_unpack\`，输入 <256 MB 才做）。
  这条路径与 §4.1 的「模块不递归」不冲突：递归只发生在 `strings` 这个独立工具内部，
  模块解包本身仍然不递归。指定 `--module fuckrkw` 则用它解，不指定就 `Engine.auto` 猜。
- **S4/S5/S7 各自有 5000 条硬上限**，达到即截断，报告里会逐条写
  「达到条数上限（5000），后续被截断」。看到 5000 就该知道**不是全部**，用 `--bare-limit`
  或 `--min-len` 收窄再来一遍。
- **S9 只做诊断，不产文案**，而且它的判定口径与 `fuckrkimg` 的加密判定**不是同一套阈值**：
  `U6.BIN` 在 `--selftest` 里是 `ENCRYPTED`，但 S9 会给「非加密特征」（熵 6.80）。
  两个结论不要互相引用，加密与否以 `fuckrkimg` 的解包判定为准。
- **裸扫条数与质量无关**：U6 这种打不开的镜像照样能扫出 14650 条「候选」，绝大多数是
  加密数据里凑巧连续的可见字节。不要拿条数当成果。
- 导出：CSV / TXT / JSON / MD 一律 UTF-8，CSV 额外写 BOM；`probe_report.txt` 记录每个策略的
  `status / hits / 耗时 / 说明` 与全部告警，排查先看它。

### 5.7 ROCK 表分析（0.0.2）

容器形态：`ROCK` 四字节签名 + 版本串（如 `ROCK26XX2005VER1.00`）→ 头部区（≤`0x400`）
→ 16 字节目录表 → 数据区。单条记录 `type(u32) + offset(u32) + size(u32) + flag(u32)`。

- **判据是自洽，不是偏移常量**：代码在 `0x20..0x1000` 之间按 16 步进枚举表起点，要求
  `type ∈ {0,1}`、`type=0` 时 `offset=size=0`、`type=1` 时 `offset>=表尾` 且
  `offset+size<=文件长`、`offset` 非递减，最后**表尾必须恰好等于首条数据 offset**
  （允许 0 或 16 字节余量）。全仓没有任何硬编码的 `0x280` 之类魔数偏移。
- 找不到严格自洽时，会退而接受「最长宽松候选」（≥`MIN_ENTRIES*2`），并在
  `rock_report.txt` 的告警里写明；表尾不吻合时记「疑有额外头字段」。
- 每条记录会给 `entropy` / `zero_ratio` / `printable` / `repeat512` 四个特征再分类，
  字库（等宽位图）与像素块会按推断的宽高导出 PNG。

实测口径（这两个数就是当前**命中率**的全部证据）：

| 输入 | 结果 |
|---|---|
| `OPPOS9.RKW`（RK28，`KRDL` 外壳） | **未命中**，exit 1，`notes` 为空列表 |
| `ROCK.RFW`（RK26，`WFKR` 外壳） | **未命中**，exit 1，`notes` 为空列表 |
| `ROCK.RFW` 手工切掉外层 `0x200` 头后的 `ROCK.RFW[0x200:]` | 命中：表起点 `0x20`、条目 67、字库 2（16×16、16×24）、像素 0、索引 1、明文 26815、用时 3457 ms |

所以结论很直白：**当前 `rock` 只认「文件偏移 0 就是 `ROCK`」的裸容器**。带外层壳
（`.RFW` / `.RKW`）的固件，必须先自己解出内层文件再喂进来；RK26 就是卡在这一步（§7 第 1 条）。
另外 `notes` 在「签名不在 0 偏移」这一种失败下是空的——只告诉你未命中，不告诉你为什么，
这也是后面要补的地方。

---

## 6. 回归基线

`baseline/baseline.json` 固定样本 MD5 与期望计数，`--selftest` 会逐项比对
（MD5、识别类型、PNG 数、导出文件数、分区分布、加密标记）。

外壳类模块（`fuckrkw`）自身 PNG 恒为 0，光看计数无法发现内容漂移，因此额外校验
**`files/` 目录指纹**（`filesFingerprint`）：把 `files/` 下所有文件按相对路径排序，
逐条混入 `相对路径|字节数|文件内容` 做 MD5。二阶段再取其中内嵌的 `BMP0.BIN`
交给 `fuckbmp0` 参照解一遍（`then` 块），但**该结果的张数不参与门禁**，
仅作参照——原因见 §7 第 7 条。

| 样本 | MD5 | 模块 | 期望 |
|------|-----|------|------|
| `ROCK.RFW` | `422f197b5e52085250ee7ad4e11abd56` | fuckrfw | PNG 118，分区 `[6, 6, 53, 53]`，part2/part3 镜像 ≥90% |
| `OPPOS9.RKW` | `2b49dedd47aabc41d03ab1073122dde7` | fuckrkw | 文件 29，PNG 0，`files/` 指纹固定；二阶段 `files/RESOURCE/BID/BMP0.BIN` 参照 618 张 |
| `BMP0.BIN` | `e6ee01f2482fd19718b85be36ec7359b` | fuckbmp0 | PNG 1270 |
| `U6.BIN` | `92dee7a1fbd7ac65e3b612b383044678` | fuckrkimg | 加密，PNG 0 |
| `Image.code.bin` | `b6a3fa75a001e678a9db813560a99616` | fuckrkimg | 加密，PNG 0 |

本地验证：`--selftest` → `PASS 6 / FAIL 0`，退出码 0（第 6 项为 `BigFile` 读满校验）。
这一行全部通过的时候，猫娘是真的很开心喵～

换固件后可先用参考解码器对拍，确认是解码缺陷还是新格式：

```bat
python tools\bmp0_ref.py samples\BMP0.BIN --out temp\ref.txt
python tools\bmp0_ref.py G:\RESOURCE\BID\BMP0.BIN --verify temp\java.txt

:: FAT 镜像：先用 GUI 勾选「导出清单」，再拿清单对拍
python tools\fat_ref.py "<镜像>" --manifest temp\fat_ref.csv
python tools\fat_ref.py "<镜像>" --verify "<解包目录>\dump_manifest.csv"
```

---

## 7. 已知限制

以下是猫娘目前还没搞定、或者不敢打包票的地方喵，动手前请先看一遍：

1. **RK26 系列等部分固件暂时不可用**：`ROCK.RFW`（`RK2602A_SDK_MLC_DRM_APE.rfw`）这类
   RK26 固件，`ROCK` 头实际压在外层 `WFKR` 头之后的 `0x200` 处，而签名判定只认文件首 4 字节，
   于是直接判「未命中 ROCK 目录表」，`--headless rock` 以 exit 1 结束，`notes` 还是空的
   （只告诉你未命中，不告诉你为什么）。**这不是格式不认识**：把 `ROCK.RFW` 从 `0x200` 切出来
   （`ROCK.RFW[0x200:]`）再喂进去，同一份代码能解出 67 条目录、2 个字库（16×16 / 16×24）、
   1 个索引表、26815 条明文，用时 3.5 s（见 §5.7）。所以卡的是**入口不支持嵌套偏移的头**，
   不是解析内核。在补上「自动找 `ROCK` 头偏移」之前，RK26 系列请先手工切头、或先解掉外层壳
   再喂；直接丢整包进去一律不可用。`OPPOS9.RKW`（RK28）同理，它根本没有顶层 `ROCK` 表。
2. **不支持加密镜像**：`U6.BIN`、`Image.code.bin` 这类镜像仅识别为 `ENCRYPTED` 并退出，
   不做解密。判定依据为熵值、零字节比、可打印率与游程密度的组合，属于启发式，可能误判。
3. **裸图段识别是启发式**：`0x200` 记录头没有魔数，判据由阈值决定，张数会随实现细节漂移
   （曾出现 118~183 张的不同结果）。当前判据见 §5.4，并辅以 part2/part3 镜像一致率门禁；
   修改判据后必须人工检查产物可读性，再更新基线。
   报告里的“可信段 / 疑似伪段”只是基于 `f3` 字段的诊断口径，**不是判据**：
   `part2`/`part3` 的 31 张可信图已人工确认是真实 UI，`part0`/`part1` 的 12 张全部落在伪段，
   该结论可直接用来判断后续改动是否把真图挤出可信集。
4. **裸图段像素格式未完全确定**：实测同一固件内不同分辨率对 565 / 555 / BGR 的平滑度表现不一，
   当前统一按 565 + BGR + 0x200 偏移导出，个别图块可能出现色偏。
5. **FAT L1 已实测，L2 未做**：`core/Fat.java` 的 FAT12/16/32 读取路径已在真机 FAT16 镜像
   （§5.5 门禁）与合成镜像上通过，并与 `tools\fat_ref.py` 全量清单对拍零差异；但**不做分区表
   探测**（镜像须是分区起始处的裸 FAT 卷）、不恢复已删除项、不处理跨盘 .img 内的多分区，
   这些属 L2，未实现。
6. **BMP0 语义为实测推断**：`count` 的无符号游程、`0xFF` + 小端 u32 扩展计数、`0x00` 终结符、
   8/14 字节双头自适应与洋红色键规则均来自实测（样本 A 1270 条 + 样本 B 2624 条全量与独立
   参考解码器像素 MD5 一致），尚缺第三方资料佐证；换固件后若出现整批花屏，先查 §5.2 的块头判据。
7. **覆盖范围有限**：RK26 部分容器（切头后可解，见第 1 条）+ RK27 / RK28 早期方案做过实测，
   不保证兼容后续 RK29+ 的资源格式；RK26 的整包入口尚未打通。
8. **`BMP0.BIN` 花屏缺陷已修复（2026-10-18）**：原实现把 RLE 的 `0xFF` 扩展计数当单字节游程、
   把未压缩块的第 4 字节填充位当 alpha、把块头写死 8 字节、色键按跨像素字节模式匹配，
   导致含扩展游程的图块整幅错位、未压缩图块全透明、14 字节头样本整体错位、部分图块通道被清零。
   现按 §5.2 的实测语义解码，两批真机样本全量对拍均通过：
   - 样本 A（1270 条 / 8 字节头）：逐条像素 MD5 与参考解码器完全一致，抽样 10 条 PNG 逐像素无差异
     （透明像素为 `(255,0,255,0)`），指纹门禁更新为 `3228c404…`；
   - 样本 B（2624 条 / 14 字节头 / comp 5:1380）：逐条像素 MD5 零差异，8 个金标准 MD5 全部命中，
     导出 2624 张 PNG。

   二阶段（`files/RESOURCE/BID/BMP0.BIN`）张数 618 仍只作参照告警——该内嵌样本尚未做逐条对拍。
9. **字符串抽取的边界**（0.0.2 新增，先说清再看数）：
   - S10 去重**默认关闭**，同一句文案在不同界面出现会重复列出；加了 `--dedup` 才合并。
   - S4/S5/S7 各自 5000 条硬上限，达到即截断，报告里有告警——**条数不是成果**。
   - S9 的「非加密特征」与 `fuckrkimg` 的 `ENCRYPTED` **不是同一套阈值**：`U6.BIN` 两边结论相反
     （S9 熵 6.80 判非加密，`fuckrkimg` 判加密）。加密与否以 `fuckrkimg` 为准，S9 只作参考。
   - 对加密镜像，工具只能给「疑似 / 非加密」的判定，**抽不出可用文案**，扫出来的候选基本是噪声。
   - S8 只认得 JDK 自带的解压能力，**LZMA 没有 JDK 实现**，所以 `S8=miss` 属预期，不是漏扫。
   - 结构化结果依赖 S1/S2 认表；表头字段被改过或加了壳的固件会退化到纯裸扫，准确率随之下降。
10. **ROCK 表分析只有 CLI，且只认裸容器**（0.0.2 新增）：
   - **没有 GUI 入口**，只能 `--headless rock`；`--list-modules` 里也找不到它（它不是模块）。
   - 签名必须落在**文件偏移 0**，带 `.RFW` / `.RKW` 外壳的固件一律未命中（§5.7 两个样本都是）。
   - 失败时 `notes` 可能是空列表，只打印 `未命中 ROCK 目录表：[]`，**没有原因说明**；
     排查时得自己看文件头前 8 字节是不是 `ROCK`。
   - 「命中率」目前只有 §5.7 那三行实测（两负一正），**没有跨固件统计**；别把切头后那 67 条 /
     26815 条明文当成质量背书——明文是按字节特征扫出来的，未逐条校验。
   - 单文件读取上限 512 MB（`RockCli.MAX_BYTES`），超了直接拒绝，不做流式处理。
11. **`--help` 不存在**：参数写错时才会顺带打印 `usage()`；而 `usage()` 里目前只列了 `strings`，
   **没列 `rock`**（`rock` 是能用的，只是没进帮助文本）。要看 `rock` 用法就照 §3.2 那份来。

## 8. Windows 自包含 EXE 打包

把工程打成**目标机器无需安装 Java** 的测试版，产物为 app-image 目录加一个 zip。

```bat
pack_exe.bat            :: 构建 jar、裁剪运行时、打 app-image、压缩
pack_exe.bat samples    :: 同上，并把 samples\ 一并塞进包里（体积增加约 88MB）
```

流程与产物：

| 步骤 | 做什么 | 产物 |
| --- | --- | --- |
| 1 | 调 `build.bat` 编译打包 | `dist\boot.jar`、`dist\main\*.jar`、`dist\other\*.jar` |
| 2 | `jlink` 裁剪运行时（`java.base` / `java.desktop` / `java.logging`） | `build\exe\runtime`（约 48MB） |
| 3 | 从 `build\bootjar` 重建 `boot.jar`，准备 `main\` / `other\` 布局 | `build\exe\pkg` |
| 4 | `jpackage --type app-image` | `build\exe\out\sbrkcp\` |
| 5 | 复制包内说明、可选样本 | `README_EXE.txt`、`samples\` |
| 6 | 压缩 | `dist\exe\sbrkcp-0.0.2Beta-win-x64.zip` |

打包要点：

1. **`--input` 只放 `boot.jar`，模块 jar 走 `--app-content`。** `jpackage` 会把 `--input`
   目录下所有 jar 写进 `app.classpath`；若模块 jar 也在其中，`ServiceLoader` 会先经 parent
   发现一遍、再经 `URLClassLoader` 发现一遍，同一模块登记两次，后半批全部被判成
   「模块 id 冲突」。`ModuleLoader` 另加了按 provider 类名去重，双保险。
2. **两个启动器**：`sbrkcp.exe`（GUI，无控制台）与 `sbrkcp-cli.exe`（`win-console=true`，
   供 `--list-modules` / `--selftest` / `--headless` 看输出）。
3. **运行时不匹配不影响兼容性**：字节码仍是 `--release 8`，只是运行在裁剪过的 Java 21
   runtime 上；`jlink` 出的镜像不是 JRE 8，若出现 AWT/Swing 外观差异属预期。
4. `boot.jar` 由 `build\bootjar` 重新打包而非复制 `dist\boot.jar`，因此 GUI 开着（占用
   `dist\boot.jar`）时本步骤仍可继续；但 `build.bat` 自身会失败，打包前请先关掉窗口。

验证口径（在剥离 `PATH` / `JAVA_HOME` 中所有 Java 项的环境下实测）：
`--list-modules` 4 加载 / 0 失败；`--selftest` PASS 6 / FAIL 0；
`--headless --module fuckbmp0 --in samples\BMP0.BIN` 输出 1270 张 PNG；`--smoke` 窗口可见、无残留非守护线程。

---

## 9. 许可

本项目以 **GNU General Public License v3.0（GPL-3.0）** 发布，全文见 [LICENSE](LICENSE)。

这意味着：

- 你可以自由使用、修改、分发本程序，包括商业用途；
- 但**分发修改版或衍生作品时，必须同样以 GPL-3.0 开源完整对应源代码**，并保留版权声明；
- 软件按「现状」提供，不附带任何担保。

如果你要把本项目的代码并入闭源产品，GPL-3.0 不允许；这种情况请联系作者另行协商授权。

> 仓库不附带任何厂商固件样本（`.RFW` / `.RKW` / `.BMP0.BIN` 等）。固件版权归各设备厂商所有，
> 请自行准备合法持有的样本，或在 `samples/` 下放置自己的测试文件后再跑 `--selftest`。
>
> 最后再啰嗦一次喵：本项目**只验证过部分固件的部分容器**（实测范围见第 6 节，边界见第 7 节），
> **无法保证 100% 成功，也不保证解出来的结果完美**。遇到解不动、解歪、花屏的固件都很正常，
> 欢迎把样本特征和日志一起提上来，大家一起把它补完善喵～
