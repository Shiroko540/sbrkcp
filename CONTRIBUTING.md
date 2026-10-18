# 贡献指南

## 1. 环境

- JDK 9+（编译期）；运行期兼容 Java 8。
- 任一 IDE，仓库自带 `.vscode/settings.json`（UTF-8 / LF / 排除 build 与 samples）。

## 2. 构建与自检

```bat
build.bat selftest
```

要求：构建 0 错误，`--list-modules` 显示 `已加载 4 / 失败 0`，
`--selftest` 显示 `PASS 5 / FAIL 0`（退出码 0）。

## 3. 代码约定

- **编码**：所有源码 UTF-8 无 BOM，缩进 4 空格，行尾 LF。
- **语言级别**：`--release 8`。不要使用 `List.of` / `var` / `record` / switch 表达式等 8 以上语法；
  集合字面量用 `Collections.singletonList` / `Arrays.asList`。
- **模块隔离**：模块 jar 中不得打入 `sbrkcp/api`、`sbrkcp/core` 的任何 class；
  模块只通过 `SbrkcpModule` 契约与 `Engine` 等 core 公共方法工作。
- **失败隔离**：模块加载、单模块解包均需 `catch Throwable`，一个模块崩溃不得拖垮 boot。
- **无子进程**：boot 进程内不得 `exec` 外部程序。
- **不写死偏移**：容器起点、`delta`、压缩标志位（本批固件在 `+6`，旧固件在 `+5`）等
  必须运行时探测或带回退，代码里附注释说明依据。
- **产物命名**：多分区解包统一用 `partN_` 前缀，便于回溯条目归属；报告统一 `unpack_report.txt`。

## 4. 新增一个模块

1. 建 `src/modules/<name>/sbrkcp/modules/<pkg>/`，实现 `SbrkcpModule`：
   `info()`、`unpack(...)`、`createPanel(...)`。
2. 面板继承 `AbstractModulePanel`，实现 `collectOptions()` 与 `setInputFile(File)`。
3. 在 `src/modules/<name>/resources/META-INF/services/sbrkcp.api.SbrkcpModule`
   写明实现类全名（单行，文件必须在 jar 根下的 `META-INF/services/`）。
4. 在 `build.bat` / `build.sh` 里加一条 `build_module` 调用，产物放 `dist/main` 或 `dist/other`。
5. 在 `baseline/baseline.json` 与 `SelfTest.defaults()` 中同步期望值（两处必须一致）。
6. 在 README 的模块能力表与格式要点中补一条说明。

## 5. 修改解析内核

`core` 的改动影响所有模块，必须同步跑完整自检，并在 CHANGELOG 说明影响面。
若改动会导致样本解包计数变化（尤其是裸图段启发式），**先人工核对产物可读性**，
再决定是修实现还是更新基线——不要在产物不可读的情况下直接抬基线。

## 6. 提交规范

```
<scope>: <一句话说明>

<body：为什么改、依据的实测证据、影响面>
```

`scope` 取 `core` / `api` / `boot` / 模块名 / `docs` / `build`。

## 7. 样本与版权

固件样本体积大且受版权保护，**不入库**。提 issue 描述格式问题时，请给出
偏移、字段值、判定依据，以及可复现的样本 MD5，不要直接上传整包固件。

## 8. 许可与贡献授权

本项目以 **GNU General Public License v3.0** 发布（见 `LICENSE`）。

提交 PR 即表示你同意：

- 你的贡献以 GPL-3.0 授权并入本项目，不附加额外条款；
- 你有权提交这段代码（自行编写，或来自同样兼容 GPL-3.0 的来源）；
- 不要粘贴来源不明或许可不兼容的代码（包括从反编译产物直接抄来的实现），
  请以自己的理解重新实现，并在 PR 描述里写明依据（格式文档、样本特征、实测结论）。

**关于逆向工程**：本项目只处理用户自己合法持有的固件文件，不包含、不分发任何
厂商的私有代码或固件内容。请在你所在司法辖区法律允许的范围内使用。
