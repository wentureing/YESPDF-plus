# PDF 引擎能力与内存策略调研（含"分片读取 / 少读写硬盘 / 硬解"可行性）

> 结论先行：**当前实现已经不落盘缓存、文件也不是整份读入内存**。
> 大 PDF 的痛点是"渲染位图 + 页对象不释放 + 打开时遍历所有页"，而不是"文件读取方式"。
> 真正值得做的三件事：① 页级资源释放 ② 懒计算页尺寸 ③ 大文件切「单页 + Surface 直渲」。

---

## 1. 当前实现到底怎么占内存（代码事实）

| 环节 | 现状 | 内存/IO 影响 |
|---|---|---|
| 打开文档 | `PdfiumCore.newDocument(ParcelFileDescriptor)`（`FileSource`/`UriSource`） | pdfium 通过 **fd 按需 read/mmap**，**不会把整份 PDF 读进堆**；文件级"分片读取"实际由 pdfium + OS page cache 完成 |
| 页尺寸 | `PdfFile.setup()` 对 **每一页** 调 `getPageSize()` | 10000 页 = 10000 次跨 JNI；慢，且 pdfium 侧会为每页建立对象。**这是打开大文件卡顿/吃内存的第一元凶** |
| 页对象 | `PdfFile.openPage()` → `nativeLoadPage`，之后**从不单独关闭** | 文档存活期间页对象只增不减；`closeDocument()` 时才统一释放（已核实 `PdfiumCore.closeDocument` 会遍历 `mNativePagesPtr` 逐个 `nativeClosePage`） |
| 渲染 | 瓦片位图（`PagesLoader` 计算网格 → `renderPageBitmap`） | 位图在 Java 堆；已改为**字节预算淘汰**（1/8 堆，24~96MB）+ 单片 ≤2048px |
| 缓存落盘 | **无**。只有封面 `files/<书名>.jpg`（≤512px / JPEG q80） | 已经是"少读写硬盘" |
| 硬解 | 位图由 Canvas 绘制，系统负责上传 GPU | 位图必须先在 Java 堆生成 → 内存峰值由位图决定 |

**因此：用户提的"分片读取"如果指文件，那已经成立；如果指渲染，我们已在做瓦片级分片，还可以做得更彻底。**

---

## 2. 三个"分片"必须先分清

| 类型 | 含义 | 现状 | 是否值得做 |
|---|---|---|---|
| **文件分片** | 只读某段字节（自定义 `ReadBlock` 回调） | pdfium 通过 fd 自己按需读；Java 层无此 API | 仅当需要"容器内文件/网络流/加密壳"时才有价值 → 需 `PdfiumSource`（legere） |
| **页级分片** | 只打开/渲染当前页，离开即释放 | 打开不释放 ❌ | **最值得做**，直接解决原生内存只增 |
| **瓦片分片** | 一页切成 N×M 块渲染 | 已实现（`PagesLoader` 网格） | 保留，但要在"大文件"场景让位给单页直渲 |

---

## 3. 两个 PDF 引擎绑定的真实能力（已解析 aar 字节码）

| 能力 | `com.github.barteksc:pdfium-android:1.9.0`（现用） | `io.legere:pdfiumandroid:1.0.32` |
|---|---|---|
| 从 fd 打开 | ✅ `newDocument(ParcelFileDescriptor)` | ✅ |
| 从内存打开 | ✅ `newDocument(byte[])` | ✅ `nativeOpenMemDocument` |
| **自定义源（真·分片读取）** | ❌ 无 | ✅ **`nativeOpenCustomDocument` + `PdfiumSource`** |
| **单页关闭** | ⚠️ `nativeClosePage(long)` 存在但 **未公开**（只能反射） | ✅ `PdfPage.close()` / `closePage()` 公开 |
| 渲染到 Bitmap | ✅ `renderPageBitmap` | ✅ |
| **渲染到 Surface（硬件合成路径）** | ✅ **`renderPage(doc, Surface, …)` 已是公开 API** | ✅ `lockSurface` / `unlockSurface` / `nativeWindow` |
| 文本层（搜索/复制/选中） | ❌ | ✅ `PdfTextPage`（`textPageGetText` / `getBoundedText` / `findStart`） |
| 页面 box（media/crop/trim/bleed/art） | 仅 media | ✅ 全量 |
| 协程/异步 API | ❌ | ✅ `suspend/` 全套 |
| 体积 / ABI | aar 19.2 MB，armeabi/armeabi-v7a/arm64-v8a/mips/x86… | aar 10.2 MB，arm64-v8a/armeabi-v7a/x86/x86_64 |
| 语言 | Java | Kotlin（`io.legere.pdfiumandroid.*`，包名不同） |

> 参考：`PdfiumCore` 源码（1.9.0 上游分支，`renderPage(Surface)` 与 `nativeClosePage` 均在此可见）：
> <https://raw.githubusercontent.com/andob/PdfiumAndroid/master/src/main/java/com/shockwave/pdfium/PdfiumCore.java>

**关键判断**：`io.legere` 不是"另一个野库"，而是把同一套 pdfium 重新做了现代封装——**它同时给了我们页释放、自定义源、Surface 直渲、文本层**，正好覆盖用户三个诉求。

---

## 4. 同类项目的优秀设计（可借鉴的机制）

| 项目 | 机制 | 对我们的启示 |
|---|---|---|
| **PDF.js** | `tile` + 多级 LOD（先低清后高清）+ `LRU` + 显存/内存预算；曾专门讨论"canvas 切块"以降低单次分配上限（issue #6419） | 我们已有瓦片与字节预算；可补"先粗后精"的 LOD |
| **MuPDF** | 文档 → page → display list → pixmap 分层；`store` 缓存（内存为主，可选磁盘）；progressive rendering（先出低清轮廓） | **分层渲染 + 页级 pixmap 缓存**；我们缺的正是"页级"这层 |
| **AndroidPdfViewer（上游）** | 整页均分瓦片 + 数量淘汰 | 我们已升级为字节淘汰；上游对超大页无解 |
| **系统 PdfRenderer** | 整页渲染到 Bitmap，无分片、无缓存 | 只适合封面/单页，不能用于连续滚动 |
| **KOReader** | MuPDF + 页缓存 + 前后页预渲染 | 预渲染少量邻页 + 页级释放，是性价比最高的组合 |
| **商业阅读器**（Xodo/Acrobat） | 瓦片 + **磁盘 tile cache** | 我们**刻意不做**磁盘缓存（用户要求少读写硬盘；且写盘对闪存寿命与延迟都不友好） |

---

## 5. 硬解（GPU）路径：为什么现在用不上，什么条件下能用

- 系统硬解的本体是 **把页面内容直接合成到 Surface**，跳过"Java 堆位图"。
- pdfium 早就提供 `renderPage(doc, Surface, startX, startY, sizeX, sizeY, renderAnnot)`：
  它把页面绘制到 `ANativeWindow`（SurfaceView/TextureView/ImageReader 均可），**全程无 Bitmap 分配**。
- **冲突点**：当前 `PDFView` 是"一张大 Canvas 上按偏移绘制多页瓦片"的连续滚动模型，无法为每页分配一个 Surface（Surface 数量与开销不可接受）。
- **可行条件**：**单页模式**（横向翻页或"逐页竖向"）下，一屏只有一个 Surface，就可以走直渲路径。也就是：
  > 大文件 → 自动切换「单页 + Surface 直渲」；普通文件 → 保持现在的连续滚动瓦片。
- 另一条"伪硬解"手段：`Bitmap.Config.RGB_565`（已用于非最佳质量）把位图内存减半；`HARDWARE` config **不能**作为 pdfium 渲染目标（不可写），只能用于把已有位图转成 GPU 纹理。

---

## 6. 三个可落地方案（按风险递增）

### P0：不换引擎，立刻可做（收益确定、风险低）
1. **懒计算页尺寸**：`PdfFile.setup()` 不再遍历所有页，改为"按需计算 + 缓存"，首次只算当前页与邻页。
   预期：打开 5000 页文档的时间与内存显著下降。
2. **限制页对象数量**：对 `PdfDocument.mNativePagesPtr` 的页做 LRU（保留 ~24 页），超限时用反射调 `nativeClosePage(long)` 释放（1.9.0 有此 native 方法，签名 `(long)`）。
   预期：原生内存不再随滚动单调增长（这正是"滚到后段崩溃"的直接原因）。
3. 已有：字节预算缓存、单片上限、`trimMemory`、fd 正确关闭。

### P1：换 `io.legere:pdfiumandroid:1.0.32`（一次性拿到 4 项能力）
- 改动面：`PDFView` / `PdfFile` / `DecodingAsyncTask` / `source/*` 共 6 个文件的 import 与少量签名适配（包名 `com.shockwave.pdfium` → `io.legere.pdfiumandroid`）。
- 收益：`PdfPage.close()`（不再反射）、`PdfiumSource`（真分片/自定义源）、`PdfTextPage`（搜索/复制，为后续功能铺路）、aar 从 19.2MB 降到 10.2MB。
- 风险：Kotlin API 语义差异只能靠编译 + 真机验证；CI 能挡编译问题，**运行时行为需要你装机确认**。

### P2：大文件「单页 + Surface 直渲」
- 触发条件建议：页数 > 2000 **或** 单页像素 > 4096×4096 **或** 文件 > 200MB。
- 实现：新增 `SinglePageSurfaceView`（SurfaceView + pdfium `renderPage(Surface)`），`PreviewActivity` 按条件切换模式。
- 收益：位图缓存归零，硬解合成，滑动不再产生 Java 堆压力。
- 风险：与现有连续滚动、自动滚动、双指缩放都要重新适配（工作量大，建议独立里程碑）。

---

## 7. 建议的执行顺序（等用户确认）

1. **P0-1 + P0-2**（1 个提交，改动集中在 `android-pdf-viewer` 内部，不动依赖）
2. **P1 换 legere**（1 个提交，CI 编译 + 你装机验证基本阅读、夜间模式、书签、自动滚动、加密 PDF）
3. 视 P1 结果决定是否做 **P2**（大文件单页直渲）

> 关于"少读写硬盘"：当前除封面外无任何落盘缓存，P0/P1/P2 都不会新增磁盘写入；
> 若后续要做"页级 pixmap 缓存"，也应**只放内存**，落盘会让大文件翻页变成磁盘抖动。
