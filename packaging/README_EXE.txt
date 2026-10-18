sbrkcp 0.0.1Beta  —  Windows x64 自包含测试版
================================================

本测试包已内置 Java 运行时（由 JDK 21 jlink 裁剪：java.base / java.desktop / java.logging），
目标机器无需安装任何 Java 环境，解压即用。

【运行方式】

1) 图形界面
   双击 sbrkcp.exe

2) 命令行（会附带控制台窗口，便于看输出）
   sbrkcp-cli.exe --list-modules                     列出模块与加载状态
   sbrkcp-cli.exe --selftest [--samples <目录>]      基线自检（默认读 .\samples）
   sbrkcp-cli.exe --headless --module <id> --in <固件> --out <目录> [--list] [--flip]
   sbrkcp-cli.exe --smoke [--in <固件>]              GUI 冒烟测试

   模块 id：fuckrkw / fuckrfw / fuckrkimg / fuckbmp0

【目录结构】

   sbrkcp.exe            图形界面启动器
   sbrkcp-cli.exe        命令行启动器
   app\boot.jar          主程序
   main\*.jar            主分类模块（fuckrkw / fuckrfw / fuckrkimg）
   other\fuckbmp0.jar    BMP0 模块
   runtime\              内置 Java 运行时（勿删）
   samples\              样本目录：本包未附带样本，自检前请放入
                         BMP0.BIN / ROCK.RFW / OPPOS9.RKW / U6.BIN / Image.code.bin

   模块搜索路径为启动器所在目录下的 main\ 与 other\，新增模块 jar 直接丢进对应目录即可。

【与 java -jar 版本的关系】

   字节码仍为 Java 8（--release 8），逻辑与 dist\boot.jar 完全一致；
   区别仅是运行环境随包内置（Java 21 运行时）。已在剥离系统 PATH 中 Java 的情况下验证：
   --list-modules 4 加载 / 0 失败；--selftest PASS 5 / FAIL 0；--headless BMP0.BIN 输出 1270 张 PNG。

【已知限制】

   · 仅 Windows x64；exe 未做代码签名，首次运行可能触发 SmartScreen，选择"仍要运行"。
   · 内置运行时是 JDK 21 的 java.desktop，不是 JRE 8；如遇 AWT/Swing 外观差异属预期。
   · 图形界面与命令行拆成两个 exe：GUI 启动器不带控制台，命令行启动器会显示黑窗。
