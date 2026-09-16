# 抖音筛选分享 Android V0.1

开发分支：`douyin-filter-android`

这条分支把已经在 PC + ADB 上验证通过的流程迁移到 Android 原生 APK：MediaProjection 只在内存采集画面；SigLIP2 FP16 ONNX 对完整屏幕输出 RAW LOGITS 后按 `sigmoid` 取 Sensual 索引 4；CLIP ViT-B/32 FP16 ONNX 使用 `full / upper / middle / lower` 四视图；保留女性门控、动态 Top-K 和 A/B/C/D 视频级规则；AccessibilityService 负责上滑、严格确认真实分享面板、精确匹配联系人并发送。

安全逻辑继续保留：横向寻找联系人只允许发生在真实分享联系人 RecyclerView 内；“某某分享 分享给你 / 私信某某”不会被当成分享面板；不保存截图、CSV、检测历史或分享历史，只保存少量设置。

## 不需要本机 Android Studio

GitHub Actions 会在云端下载公开 Hugging Face 原模型，自动导出修正后的两个 FP16 ONNX，把模型打入 APK，编译 `arm64-v8a` 调试 APK，并生成 Actions Artifact：`DouyinFilter-debug-apk`。

两个模型合计约 335 MB，所以不会把 ONNX 或大 APK 提交回 Git 仓库；构建产物只放在 Actions Artifact 中下载。

## 首次使用

安装 APK 后，打开“抖音筛选分享”，依次开启无障碍服务、授权屏幕捕获、等待模型加载完成，再打开抖音，点击悬浮按钮“开始”。默认分享目标是 `老张分享`，可以在 App 内修改。

模型来源：`prithivMLmods/siglip2-x256p32-explicit-content/checkpoint-1656` 与 `openai/clip-vit-base-patch32`。SigLIP checkpoint 的实际 Hugging Face pipeline 分数与 `sigmoid(logits)` 对齐，Android 端不能改用 softmax。
