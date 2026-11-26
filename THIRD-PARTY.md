# 第三方组件与数据（Third-Party Notices）

本文件记录仓库中包含或依赖的第三方作品及其许可。
分发本项目（源码或 APK）时，请一并保留本文件。

本项目**代码**的许可是 GNU General Public License v3.0（`GPL-3.0-or-later`），
见 [LICENSE](LICENSE)。但仓库中含第三方数据与素材，其许可与代码许可相互独立，见下。

---

## 1. 数据（随仓库分发）

### HYG Database v4.1

| 项 | 内容 |
|---|---|
| 文件 | `catalog/hygdata_v41.csv` |
| 作者 | David Nash |
| 来源 | <https://github.com/astronexus/HYG-Database> |
| 许可 | **CC BY-SA 4.0** — <https://creativecommons.org/licenses/by-sa/4.0/> |

### 派生的星表二进制

| 项 | 内容 |
|---|---|
| 文件 | `app/src/main/assets/stars.bin`（及 J2016.0 传播版） |
| 生成方式 | 由 `catalog/build_catalog.py` 从 HYG v4.1 生成 |
| 性质 | HYG 数据库的**衍生作品** |
| 许可 | 同样为 **CC BY-SA 4.0** |

> **ShareAlike 的实际影响**：APK 内嵌了该数据，因此再分发 APK 时，数据部分仍需
> 保留署名并以 CC BY-SA 4.0 授权。
>
> **与代码许可的关系**：本项目代码为 GPL-3.0。Creative Commons 已声明
> CC BY-SA 4.0 与 GPLv3 **单向兼容**（CC BY-SA 4.0 的作品可以并入 GPLv3 作品），
> 因此把 HYG 数据与 GPL-3.0 代码一起分发是合规的，不需要额外授权。

---

## 2. 代码依赖（Gradle，不随仓库分发源码）

| 依赖 | 许可 |
|---|---|
| AndroidX：core-ktx、activity-compose、lifecycle-runtime-compose、Compose UI、Material3 | Apache-2.0 |
| Kotlin 标准库 | Apache-2.0 |
| Apache Commons Math3 | Apache-2.0 |
| JUnit 4（**仅测试**） | EPL-1.0 |
| Gradle Wrapper（`gradle/wrapper/gradle-wrapper.jar`） | Apache-2.0 |

> Apache-2.0 与 GPLv3 兼容，因此上述运行时依赖可与本项目一起分发。
> JUnit 仅用于测试，不进入发布产物。

---

## 3. 项目自制素材（随项目许可，GPL-3.0）

- `docs/ui/*.png` —— 真机界面截图；
- `calibration-pattern/*.pdf` / `*.png` —— 内参点阵标定图（程序生成）；
- `app/src/main/res/mipmap-*/*.webp` —— 应用图标。

---

## 4. 历史重写与许可范围说明

本项目在 **0.9.88** 进行过一次历史重写（提交 `7292190`，三条分支均已同步），
已从全部 ref 中清除早期调试用的第三方图片素材（原 `real-images/` 目录）、
真实坐标与设备信息。因此当前仓库**不存在来源不明的第三方图片**。

例外：`app/src/main/res/drawable/dsh_badge.png` —— DeepSeek Harness 徽标，
按其来源项目的条款使用，不在本项目 GPL-3.0 授权范围内。

---

## 5. 署名示例

分发本项目的修改版或二进制时，请在 README、关于页面或发布说明中保留：

```text
This product includes data from the HYG Database v4.1 by David Nash,
licensed under CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/).

Star Position (https://github.com/GoldenPants2011/star-positioning)
Copyright (C) 2026 GoldenPants2011 — licensed under GNU GPL v3.0.
```
