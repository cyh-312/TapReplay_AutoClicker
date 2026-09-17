# DouyinFilter Android V0.7.5

一个在 Android 手机上本地运行的抖音视频筛选与分享实验项目。

程序通过 Android 屏幕采集获取当前视频画面，在本机使用 SigLIP2 与 OpenAI CLIP 两个视觉模型进行多帧分析；只有当视频满足设定的识别规则时，才会通过无障碍服务尝试分享给用户事先指定的联系人。

> **当前公开代码仅对应 V0.7.5。** 公开目录只保留当前版本实际需要的源码、模型导出脚本和构建流程，不展示早期分享流程与无关实验代码。

## 下载最新版

当前公开版本：**V0.7.5**

APK：

`https://github.com/cyh-312/TapReplay_AutoClicker/releases/download/douyin-filter-v0.7.5/DouyinFilter-v0.7.5.apk`

SHA256：

`a8b8b15d9705bb387dfd4c85b9f0d87983cfbc9e6a2c090da5b813c988d09646`

## 这个项目做什么

运行后，程序会循环执行以下流程：

1. 自动切换到下一条抖音视频；
2. 等待视频页面稳定；
3. 在约 5 秒时间内采集目标 18 帧画面；
4. 使用 SigLIP2 对各帧进行视觉内容分类；
5. 从代表帧中使用 CLIP 辅助判断女性 / 男性 / 无明显人物；
6. 使用多帧规则得到 `positive / border / negative / insufficient` 结果；
7. 只有 `positive` 才进入分享流程；
8. 重新定位当前视频的真实分享按钮，精确匹配联系人并只发送一次；
9. 单次分享失败时尝试恢复视频页，然后继续下一条，不让整个任务因为一条视频中断。

## 使用的模型

### 1. SigLIP2 显式内容分类模型

使用：

`prithivMLmods/siglip2-x256p32-explicit-content/checkpoint-1656`

其基础模型属于 Google SigLIP2 系列；本项目使用的是第三方基于 SigLIP2 微调后的图像分类模型，主要读取其中 `Enticing or Sensual` 类别，并在 Android 端对对应 raw logit 使用 sigmoid 得到分数。

### 2. OpenAI CLIP

使用：

`openai/clip-vit-base-patch32`

本项目在构建阶段预先计算女性、男性和无人场景的文字特征，Android 运行时主要保留 CLIP 视觉分支及三分类所需内容，不需要在手机上重复运行完整文本编码器。

## 为什么转换成 ONNX FP16

原始模型主要面向 Python / PyTorch 环境。为了能够在 Android 上通过 ONNX Runtime 本地运行，构建脚本会：

`Hugging Face / PyTorch → FP32 ONNX → FP16 ONNX → 打入 APK`

当前生成的两个手机模型约为：

- `siglip2_raw_logits_fp16.onnx`：约 167 MB；
- `clip_female_vision_fp16.onnx`：约 168 MB。

两个模型合计约 335 MB，因此 APK 本身体积较大。

## 当前识别参数

V0.7.5 当前保留的主要参数：

- 目标采样：18 帧；
- 最大采样：20 帧；
- 采样间隔：300 ms；
- 最大采样窗口：8 秒；
- 切换视频后等待：950 ms；
- SigLIP2：两批 9 帧流水线推理；
- CLIP：从均匀时间帧和 SigLIP2 高分帧中选择代表帧；
- 最终使用女性 Gate 与 A/B/C/D 多帧规则进行判断。

公开版本没有为了移动端进一步减少帧数、降低输入分辨率或修改识别阈值。

## 手机端实现

- **屏幕采集：** Android MediaProjection；
- **本地推理：** ONNX Runtime Android；
- **自动操作：** Android AccessibilityService；
- **默认推理：** CPU；
- **试验选项：** NNAPI；
- **架构：** `arm64-v8a`；
- **最低 Android API：** 28。

模型输入画面仅在内存中用于推理，本项目不会主动把模型截图写入手机相册。

程序会在应用私有外部目录保存有限大小的运行诊断文本日志，主要记录采样耗时、模型耗时、分享步骤和异常状态。日志采用滚动方式保存，总量约 5 MiB，不用于云端分析。

## 首次使用

1. 安装 APK；
2. 打开“抖音筛选分享”；
3. 填写并保存需要分享的联系人昵称；
4. 开启无障碍服务；
5. 授权屏幕捕获；
6. 等待本地模型加载完成；
7. 打开抖音；
8. 点击悬浮条上的“开始”。

联系人昵称采用精确匹配。当前程序不会为了寻找联系人而无限横向滚动联系人列表。

## 源码构建

模型权重不直接提交进 Git 仓库。GitHub Actions / `tools/export_models.py` 会从第三方公开模型来源获取权重，生成 Android 需要的 FP16 ONNX，再编译 APK。

主要源码：

- `AutomationController.java`：自动循环、18 帧采样、SigLIP 流水线；
- `ModelEngine.java`：SigLIP2 / CLIP 推理和视频级判断；
- `ScreenCaptureService.java`：内存屏幕采集；
- `TapAccessibilityService.java`：悬浮条、上滑和系统手势；
- `ShareFlowV7.java` / `ShareFlowV8.java`：当前分享流程与失败恢复；
- `TraceLogger.java`：有限大小滚动诊断日志；
- `tools/export_models.py`：模型导出与 FP16 转换。

## 免责声明

**本项目定位为个人学习、技术研究、娱乐测试和非商业实验用途。**

本项目与抖音 / 字节跳动、OpenAI、Google、Hugging Face，以及所使用第三方模型的作者、维护者均无隶属、合作、授权或官方关联关系。

本项目包含屏幕采集、无障碍服务和第三方应用界面自动化。第三方应用的页面结构、风控策略、服务条款及技术实现可能随时变化，运行本项目可能出现误操作、分享失败、账号限制、耗电、设备发热、系统兼容性异常等情况。

使用者应自行确认并遵守所在地适用法律法规、第三方平台服务条款以及设备和账号的相关规则。因安装、运行、修改、分发或使用本项目产生的账号风险、数据损失、设备异常、第三方权益争议或其他直接、间接损失，由实际使用者自行承担。

本项目按“现状”提供，不对识别准确性、稳定性、适用性、持续可用性或特定用途作明示或默示保证。

请勿将本项目用于未经授权的批量运营、骚扰、垃圾信息发送、侵犯他人隐私、绕过平台安全机制或其他违法违规行为。

## 开源许可

本项目中由本项目作者原创并有权授权的代码，以 **MIT License** 开放。

“学习、研究、娱乐测试”的描述是本项目的用途定位，不替代或修改 MIT License 对原创代码的授权范围。

第三方模型、第三方库、商标、论文及相关材料不因本项目采用 MIT License 而自动变为 MIT 授权；请分别遵守原权利人的许可证和使用要求。具体见：

`第三方组件与模型说明.md`

## 说明

这是个人实验项目，不是抖音插件、官方客户端或官方推荐工具。使用前请先理解无障碍自动化、屏幕采集、本地 AI 推理和第三方平台自动操作可能带来的风险。
