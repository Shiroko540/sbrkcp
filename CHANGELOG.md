# 变更日志

本文件记录各版本的显著变更。格式参考 Keep a Changelog。

版本号规则：`0.0.x` 为 Beta 阶段（CLI 参数与模块 API 可能变动），首个对外版本为 `0.0.1Beta`。

## [0.0.2] - 2026-10-24

在 0.0.1Beta 基础上补充文本/字符串抽取与 ROCK 表分析能力，并调整界面文案。

### 新增

- **文本抽取**：`core/text` 新增 `StringsProbe` / `StringTable` / `StringsExport` / `StringsEntry`
  / `BareScanner` / `ArchiveProbe` / `CryptoDiag`，支持裸扫描、归档探测与加密判定。
- **ROCK 表分析**：`core/rock` 新增 `RockAnalyzer` / `RockTable` / `RockReport` / `RockStats`，
  输出索引命中、字模命中、文本命中与像素命中统计。
- **CLI 入口**：`RockCli`、`StringsCli`，可在命令行直接调用上述能力。
- **GUI**：新增 `StringsModule` / `StringsPanel`（字符串抽取模块），并调整主界面文案。
- **版本标识**：统一为 `0.0.2`，4 个模块的 `ModuleInfo.version` 同步。

### 变更

- `--headless` 新增 `strings` 与 `rock` 两个子命令；`strings` 支持
  `--format`（csv/txt/json/md，可逗号多选）/ `--lang` / `--strategies` / `--min-len` /
  `--bare-limit` / `--dedup` / `--no-dedup` / `--strip` / `--quiet`。
- 「界面文案」为 boot 内置模块（`StringsModule`，id `strings`），与 `dist/main`、`dist/other`
  的 jar 模块并列显示，但**不是独立 jar**，也**不会**出现在 `--list-modules` 输出里。

### 已知问题

- **RK26 系列等部分固件暂时不可用**：手上的 RK26 整机固件（`.RFW` 外壳）里，`ROCK` 头压在
  外层 `WFKR` 头之后的 `0x200`，而签名判定只认文件首 4 字节，`--headless rock` 直接以
  exit 1 结束、`notes` 为空。把该文件从 `0x200` 切出、丢掉外层头后再喂，同一份代码可解出
  67 条目录 / 2 个字库 / 1 个索引表 / 26815 条明文，说明卡在**入口不支持嵌套偏移的头**，
  而非解析内核；补上「自动定位 `ROCK` 头偏移」前，RK26 整包一律不可用。
  （详见 README §7 第 1 条、§5.7）
- `rock` 只有命令行入口，**无 GUI**；`usage()` 中也未列出 `--headless rock`（本项目无 `--help`）。
- 字符串抽取对加密镜像只能给出「疑似 / 非加密」判定，**抽不出可用文案**；S4/S5/S7 各有
  5000 条硬上限，达上限即截断；S9 的加密口径与 `fuckrkimg` 的 `ENCRYPTED` 判定不是同一套阈值
  （`样本 C` 两边结论相反），加密与否以 `fuckrkimg` 为准。
- `--selftest` 仍为 6 项（未覆盖 `strings` / `rock`），这两项回归需手工执行。

## [0.0.1Beta] - 2026-10-18

首个对外发布的 Beta 版，整合内部迭代（0.1.0 → 0.1.1）的全部成果。
boot + 模块插件式结构成型，4 个模块在 5 个真实样本上跑通，`--selftest` PASS 6 / FAIL 0。

### 新增

- **模块契约** `sbrkcp.api`：`SbrkcpModule` / `ModuleInfo` / `UnpackOptions` /
  `UnpackResult` / `Entry` / `ProgressListener` / `ModuleContext` / `AbstractModulePanel`。
- **解析内核** `sbrkcp.core`：
  - `BigFile` 大文件随机读写（>2GB 安全）。
  - `Firmware` 固件类型探测（RKRS / KRDL / WFKR / 加密镜像）与分区表、RKW 文件表解析。
  - `Rkrs` RKRS 容器识别与索引解码（含 `0x800` 索引表、8 字节索引项、尾签处理）。
  - `RawImage` 0x200 头裸图段扫描与 565/BGR 导出。
  - `Fat` FAT12/FAT16/FAT32 镜像 L1 解包：BPB 自适应、LFN 重排拼接、跨簇目录、簇链环保护、
    逐文件 MD5 清单（详见 README §5.5）。
  - `Engine` 各格式解包主流程与 `unpack_report.txt` 报告生成。
- **boot**：`BootMain`（GUI/CLI 双入口）、`Version`（版本单一来源）、`ModuleLoader`
  （独立 ClassLoader + `catch Throwable` 隔离）、`MainFrame`、`LogBus`、`SelfTest`、`MiniJson`。
- **版本标识**：统一为 `0.0.1Beta`，GUI 标题、状态栏、关于框、`--list-modules`、
  `--version` 全部取自 `Version.VERSION`；4 个模块的 `ModuleInfo.version` 同步。
- **`--version`**：打印版本号、通道、boot `apiVersion`、Java 版本，供脚本与打包流程校验。
- **可信段/疑似伪段诊断**：`RawImage.Segment` 记录段首 `f3` 与段内取值种类数，
  `unpack_report.txt` 逐段输出 `f3` 并与导出张数汇总为
  `裸图段 N 段 / M 张（可信段 a 段 / b 张）`。伪段照常导出，不静默删数据。
  实测 `样本 A`：`part2`/`part3` 各 53 张中可信 31 张，`part0`/`part1` 的 6+6 张全部不可信
  （人工图像判读为噪声，非 UI，印证诊断口径）。
- **模块**：
  - `fuckrkw` — `.RKW` 解包，KRDL 文件表 + 按原路径导出（不递归内嵌其它格式）。
  - `fuckrfw` — `.RFW` 整机固件，WFKR 分区 + 分区内 RKRS/裸图段。
  - `fuckrkimg` — FAT 镜像整盘解包（L1）与未知/加密镜像识别。
  - `fuckbmp0` — `BMP0.BIN` 解包，8/14 字节块头自适应 + 32bit BGRA + RLE + 品红透明。
- **对拍工具** `tools/bmp0_ref.py`：`BMP0.BIN` 的独立参考解码器，零第三方依赖，
  严格按 README §5.2 语义实现，`--out` 导出逐条像素 MD5、`--verify` 与 Java 侧清单逐条比对。
  用于换固件后快速回归，不参与产品路径。
- **对拍工具** `tools/fat_ref.py`：FAT12/16/32 镜像的独立参考解码器，零第三方依赖，
  `--manifest` 导出 `path,size,first_clus,read_bytes,md5` 五列清单、`--verify` 与 Java 侧
  `dump_manifest.csv` 逐行比对。真机镜像 292 条与合成镜像 22 条均 `diff=0`。不参与产品路径。
- **`--selftest` 新增 BigFile 读满校验**：对 `samples/` 下每个文件比对 `BigFile.size()`
  与 `Files.readAllBytes` 长度、并核对首尾 64 字节一致。短读会让资源头解析成 `w=0/h=0`，
  外部表现是后半批条目连续「非图条目/解码失败」，是最难定位的一类回归，故单列门禁。
- **构建**：`build.bat` / `build.sh`（编译、打包、模块自检、`clean`）。
- **自包含 EXE**：`pack_exe.bat`（`jlink` 裁剪运行时 + `jpackage` app-image + zip），
  产物 `dist\exe\sbrkcp-0.0.1Beta-win-x64.zip`，目标机器无需安装 Java。
  双启动器：`sbrkcp.exe`（GUI）、`sbrkcp-cli.exe`（带控制台，供 `--selftest` / `--headless`）。
  包内说明 `packaging/README_EXE.txt`。
- **文档**：`README.md`（含格式实测结论与已知限制）、`CONTRIBUTING.md`、`LICENSE`(GPL-3.0)。
- **回归**：`baseline/baseline.json` + `--selftest`，5 样本 PASS 5 / FAIL 0。

### 变更

- **模块职责硬隔离**：`fuckrkw` 不再自动递归解析内嵌的 `BMP0.BIN` / `.RKZ` / `.RKW`。
  外壳模块只做「KRDL 文件表 → 按原相对路径导出条目」，内嵌资源由对应模块单独解。
  动机是内嵌递归的偏移基准与完整性校验容易错位，且下层格式的解析缺陷会污染上层模块结果。
  - `UnpackOptions` 不再提供 `nested` / `maxDepth`（递归能力已彻底移除）；
    `Engine.runRkw` 在报告中记录「内嵌资源 N 个已原样导出，未做二次解析」并提示改用 `fuckbmp0`。
  - GUI `fuckrkw` 面板只保留「只列清单」「垂直翻转」，无任何递归开关。
  - CLI 不提供 `--nested`，传该参数会按未知参数报错。
  - 影响：`样本 B` 解包 PNG 由 618 变为 0、文件仍为 29；需要图片时对
    `files/RESOURCE/BID/BMP0.BIN` 单独跑 `fuckbmp0`（实测 618 张，与旧行为一致）。
- **`--selftest` 增加外壳内容门禁**：baseline 支持 `"filesFingerprint"`，对 `out/files/`
  按相对路径排序后逐条混入 `相对路径|字节数|内容` 做 MD5。外壳模块 PNG 恒为 0，
  仅比对计数无法发现内容漂移，故必须校验导出文件本身。
- **`--selftest` 增加二阶段参照解包**：baseline 的 `then` 块可指定「用模块 B 解 A 的产物」，
  两阶段产物分别落在 `out/` 与 `out/_stage2/<name>/`（避免污染 `files/` 指纹）。
  二阶段张数不匹配只 WARN 不判失败（`fuckbmp0` 解析已知有缺陷，见「已知问题」）。
- 裸图段记录长度改为 `(0x200 + w*h*2 + 3) & ~3`。原实现按 0x200 对齐，
  在 `part2` 上会因长度多算而提前断链，少收约 5 条；4 字节对齐后链能连续走到段尾。
- **宽度/高度下界由 1 提升到 4**，剔除由像素数据误判出的 1x1 / 1x2 退化伪头。
  实测 `样本 A` 原 151 张中，`part0`/`part3` 段尾孤立假头（+3/+4）属此类。
- **`f3` 标志位由"白名单"降级为"可信度诊断"**。`+4` 处的 u16 在真实 UI 记录上恒为 `0x0018`，
  但只覆盖 `part2`/`part3` 的前 31 张，其余资源取 `w`/`h`/随机值；作硬过滤会误杀真实资源，
  故仅在报告中标注，不参与判定。
- **`--selftest` 新增分区镜像门禁**：baseline 支持 `"mirror": "2,3"` 与 `"mirrorRate": 90`，
  要求两个分区导出的尺寸序列同位置一致率不低于阈值。裸图段头无魔数，单分区判据可能
  在某处切错同步点，而两个分区互为独立对照，一致率比张数更能反映判据稳定性。
- **`fuckrkimg` 由「只识别」升级为 FAT L1 解包**：命中 FAT12/16/32 时按目录树整盘解出
  `files/<原路径>`，非 FAT 结构仍只做加密/未知判定、不产出。
  `UnpackOptions` 新增 `fatManifest`（写 `dump_manifest.csv`）与 `fatTranscodeText`
  （`.txt/.ime/.cfg/.rkz` 另存 UTF-8 副本到 `text/`，原文件保留）；
  `Engine.runFat` 把统计写入 `unpack_report.txt`；GUI 面板新增「其他功能」区，
  放「导出清单」「文本转码」两个勾选项。

### 修复

- **`BMP0.BIN` 花屏（RLE 扩展计数 / 块头宽度 / alpha 位）**，`core/Rkrs.java` 五处：
  1. 块头 `w`/`h` 原按 `u8` 读，宽度 >255 的图块宽高全错（400×700 被读成 144×188 之类），
     改为 `u16le`（四字段均为 int16 LE）；
  2. `decodeRle` 重写：token = `B, G, R, count`，`count` 按**无符号**读；`0xFF` 后跟 4 字节
     小端 **uint32** 扩展数（原实现按单字节处理，`255 + v` 一旦超 255 就整幅错位）；
     `0x00` 为终结符；游程跨行**线性连写**到 `w*h` 缓冲，解完断言恰好铺满；
  3. 长度校验 `comp==1 && len > rawNeed + 8` 会误杀 RLE 图块（RLE 天然比原始小），
     收敛为仅判 `len <= 8`；
  4. `comp=0` 的每像素第 4 字节是**填充位**（实测全 0），原按 alpha 合成会让整幅图全透明，
     改为 `0xFF000000 | RGB`；
  5. 补洋红 `FF00FF` 色键：仅 `alpha = 0`，RGB 原样保留（`(255,0,255,0)`）。
  验证：1270 条逐条像素 MD5 与独立参考解码器 **0 条不一致**（comp=0 1063 / comp=1 207，
  含 `0xFF` 扩展 172 条）；抽 10 条（400×700、400×240、24×170、60×60 等）PNG 逐像素零差异；
  `BMP0.BIN` 指纹由 `791f7316…` 更新为 `3228c404…`（张数仍 1270）。
- **`BMP0.BIN` 兼容 RK28 SDK V1.2 的 14 字节块头（另一份 BMP0 归档，2624 条）**，`core/Rkrs.java` 三处：
  1. 新增 `headerLen()` 自适应：RAW（comp 0/5）由 `length - w*h*bpp` 反推（恰等 8 / 14，
     `(8,14]` 视为 8 字节头带尾部残留，`>14` 视为 14 字节头带残留）；RLE（comp 1）长度不可反推，
     改看 `+8` 处 6 字节是否全 `0xCD`，解码未铺满时 8/14 再互试一次。原先写死 8 字节，
     14 字节头样本会整体错位，表现为整批花屏；
  2. `comp = 5` 的第 4 字节是**真实 alpha**（0~255 梯度），不再按填充位补 `0xFF`，
     也不参与色键改写；仅 `comp = 0` 补 `A=255`；
  3. 洋红色键判定改为**逐像素、按 4 字节边界**：原用跨像素的字节模式扫描，会把上一像素的
     `A` 与下一像素的 `B/G` 凑成假洋红，连带把第三通道清成 0（样本 E-2 实测命中 20 条）。
  验证：样本 E-2 2624 条逐条像素 MD5 与独立参考解码器 **0 条不一致**
  （comp 0:842 / 1:402 / 5:1380），8 个金标准 MD5 探针全部命中，导 PNG 2624 张；
  视觉抽检 `comp=0/1/5` 各一张（800×480 启动图、散景图、均衡器界面）均无花屏与通道错乱。
  样本 E-1（1270 条）指纹保持 `3228c404…` 不变。
- **`Fat` 目录遍历三处错读**（`core/Fat.java`）：
  1. 短名大小写标志在 `+0x0C`，原实现把 `0x08`（仅基名小写）与 `0x10`（仅扩展名小写）
     一起套到整名上，`MUSIC1.MP3` 被误写成 `music1.mp3` 之类；改为两者独立处理，
     真机镜像 154 条路径大小写错位归零；
  2. 目录原按「首簇 + 顺序读下一簇」拼接，真机 `RKmidity/BANK0` 跨 2 簇且物理不相邻，
     会读到别的文件内容；改为严格按簇链逐簇拼接；
  3. LFN 片段原按磁盘顺序拼接，而片段是**从高序号往低写**的，长名会整串倒序；
     改为按 `byte0 & 0x1F` 升序重排后拼 UTF-16LE。
  另补簇链 `visited` 集合与 12 层深度上限，命中环记 `DIR-LOOP` 跳过（坏镜像存在 `A→B→A`）。
  验证：真机 FAT16 镜像 292 条与 `tools\fat_ref.py` 五列清单 **diff=0**（路径 / 大小 / 首簇 /
  实读字节 / MD5），合成镜像（LFN 中文长名 / 跨簇目录 / 空文件 / 目录环）同样 diff=0。
- **`BigFile.readAt` 零进展保护**：循环读从 `n < 0` 改为 `n <= 0` 退出，
  避免极端实现下 `read` 返回 0 造成死循环，短读仍按原约定交回调用方处理。
- `Engine.runRkw` 条目绝对地址未用容器起点校正，导致 PNG 计数为 0；
  改为按容器起点校正（条目 `size ≥ 4096` 且 `|container.totalSize - entry.size| ≤ 16` 时取容器起点）。
- 疑似加密判定阈值过严，随机数据被判为未知；改为
  `entropy > 7.7 && zeroRatio < 0.01 && printableRatio < 0.42 && runsPerMb < 2000`
  （随机字节可打印率期望约 0.371，不能用 `< 0.35` 排除随机数据）。
- `ModuleLoader` 使用 `List.of` 与 Java 8 目标不兼容，改为 `Collections.singletonList`。
- **EXE 版模块重复登记**：`jpackage` 会把 `--input` 目录下所有 jar 写入 `app.classpath`，
  模块 jar 既被 parent 发现一次、又被 `URLClassLoader` 发现一次，同一模块登记两次，
  后半批全部被判成「模块 id 冲突」（4 加载 / 12 失败）。打包侧改为模块 jar 走
  `--app-content`、`--input` 只留 `boot.jar`；`ModuleLoader.loadOne` 另按 provider 类名
  去重（`Set<String> seen`）作为兜底。
- RFW 裸图导出条目缺少分区归属，文件名增加 `partN_` 前缀，自检可还原分区分布。
- `SelfTest` 期望值随判据同步：`样本 A` 由 PNG 151 / `[15, 12, 59, 65]` 更新为
  PNG 118 / `[6, 6, 53, 53]`，指纹同步为 `96644919ea88d9f697c95469fffaec1f`。
  `part2` 与 `part3` 各 53 张、尺寸序列 94% 一致（差异集中在 idx 41~43）。

### 核实结论

- 可信段范围内 `part2`/`part3` **31/31 完全一致，连 PNG 字节数都相同**，说明判据在真实资源区是稳的。
- 余下 3 处不一致有两个独立成因：(1) 两分区逐字节比对在相对偏移 `0x850000..0xbef000`
  有约 3.5MB 真实内容差异，本身就不是 100% 同源；(2) 伪段区无 `0x0018` 头，链式收段会让
  两个分区的扫描进度产生相位差，同一区域被切成不同组合的伪头。两者都不是判据缺陷，
  因此 94% 作为门禁阈值应理解为"含伪段的全量口径"。
- 历史文档中的 149 / 151 张均不可复现为"正确结果"：149 来自早期链式脚本，
  混入 1x1(6)、1x2(4)、8x8(2) 等误判；151 是同一判据缺陷下的另一组取值。
  两者都只应视为历史快照，不作为验收标准。

### 已知问题

- **`BMP0.BIN` 花屏已于 2026-10-18 修复**（RLE `0xFF` 后 4 字节扩展数按单字节读、块头宽高按 u8 读、
  未压缩块第 4 字节当 alpha、块头写死 8 字节、色键跨像素误判）。当前两批真机样本逐条像素对拍
  均与参考解码器完全一致：样本 E-1 1270 条（8 字节头）、样本 E-2 2624 条（14 字节头，含 comp=5 真 alpha）；
  `--selftest` 的 `BMP0.BIN` 指纹门禁已更新为 `3228c404…`。二阶段
  `files/RESOURCE/BID/BMP0.BIN` 张数 618 仍只作参照告警（该内嵌样本未逐条对拍）。
- 不支持加密镜像（`样本 C`、`样本 D` 仅识别）。
- 裸图段判据为启发式，张数会随实现细节漂移（曾出现 118~183 张的不同结果，当前 118）。
- 裸图段像素格式（565/555/BGR）在不同分辨率下表现不一，未逐段自适应。
- `Fat` L1 已在真机 FAT16 镜像与合成镜像上实测（`tools\fat_ref.py` 全量对拍 diff=0），
  但 L2 未做：不探分区表（镜像须为分区起始处的裸 FAT 卷）、不恢复删除项、不处理多分区 .img。
- RLE 语义（无符号游程 / `0xFF` + 小端 u32 扩展 / `0x00` 终结符 / 洋红色键）为实测推断，缺第三方资料佐证。
