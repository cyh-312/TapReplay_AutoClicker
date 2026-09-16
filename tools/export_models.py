# -*- coding: utf-8 -*-
"""GitHub Actions: generate the two FP16 ONNX files used by the APK."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

import onnx
import onnxruntime as ort
import torch
import torch.nn as nn
from transformers import AutoImageProcessor, AutoModelForImageClassification, CLIPModel, CLIPProcessor
from onnxruntime.transformers.float16 import convert_float_to_float16

SIG_REPO = "prithivMLmods/siglip2-x256p32-explicit-content"
SIG_SUBFOLDER = "checkpoint-1656"
CLIP_REPO = "openai/clip-vit-base-patch32"
OPSET = 17

PROMPTS = [
    "a photo of a woman", "a female person is visible", "a woman is the main person in the image", "a video frame showing a woman",
    "a photo of a man", "a male person is visible", "a man is the main person in the image", "a video frame showing a man",
    "a photo with no person", "no human is visible", "a landscape or object without people", "a scene with no visible person",
]


class RawSiglip(nn.Module):
    def __init__(self, model):
        super().__init__(); self.model = model
    def forward(self, pixel_values):
        return self.model(pixel_values=pixel_values).logits


class ClipFemale(nn.Module):
    def __init__(self, model, text_features, logit_scale):
        super().__init__()
        self.vision_model = model.vision_model
        self.visual_projection = model.visual_projection
        self.register_buffer("text_features", text_features)
        self.register_buffer("logit_scale", logit_scale)
    def forward(self, pixel_values):
        out = self.vision_model(pixel_values=pixel_values, return_dict=False)
        pooled = out[1]
        image_features = self.visual_projection(pooled)
        image_features = image_features / image_features.norm(dim=-1, keepdim=True)
        prompt_logits = self.logit_scale * image_features @ self.text_features.T
        group_logits = prompt_logits.reshape(-1, 3, 4).mean(dim=-1)
        return torch.softmax(group_logits, dim=-1)


def fp16_convert(src: Path, dst: Path):
    model = onnx.load(str(src))
    model16 = convert_float_to_float16(
        model, keep_io_types=False, disable_shape_infer=True,
        min_positive_val=5.96e-8, max_finite_val=65504.0,
    )
    onnx.save(model16, str(dst))
    ort.InferenceSession(str(dst), providers=["CPUExecutionProvider"])


def export_siglip(out_dir: Path):
    print("[SigLIP] load checkpoint-1656")
    processor = AutoImageProcessor.from_pretrained(SIG_REPO, subfolder=SIG_SUBFOLDER)
    model = AutoModelForImageClassification.from_pretrained(SIG_REPO, subfolder=SIG_SUBFOLDER)
    model.eval().to("cpu", dtype=torch.float32)
    wrapper = RawSiglip(model).eval()
    dummy = torch.randn(1, 3, 256, 256, dtype=torch.float32)
    fp32 = out_dir / "siglip2_raw_logits_fp32.onnx"
    fp16 = out_dir / "siglip2_raw_logits_fp16.onnx"
    with torch.inference_mode():
        torch.onnx.export(
            wrapper, (dummy,), str(fp32), input_names=["pixel_values"], output_names=["logits"],
            dynamic_axes={"pixel_values": {0: "batch"}, "logits": {0: "batch"}},
            opset_version=OPSET, do_constant_folding=True,
        )
    fp16_convert(fp32, fp16)
    fp32.unlink(missing_ok=True)
    id2label = {int(k): str(v) for k, v in model.config.id2label.items()}
    assert id2label[4] == "Enticing or Sensual"
    return {
        "repo": SIG_REPO, "subfolder": SIG_SUBFOLDER, "input_hw": [256, 256],
        "sensual_label_index": 4, "postprocess": "sigmoid",
        "image_mean": [0.5, 0.5, 0.5], "image_std": [0.5, 0.5, 0.5],
        "rescale_factor": 1.0 / 255.0, "resample": "bilinear",
    }


def export_clip(out_dir: Path):
    print("[CLIP] load")
    proc = CLIPProcessor.from_pretrained(CLIP_REPO)
    model = CLIPModel.from_pretrained(CLIP_REPO).eval().to("cpu", dtype=torch.float32)
    text = proc(text=PROMPTS, padding=True, return_tensors="pt")
    with torch.inference_mode():
        tf = model.get_text_features(**text)
        tf = tf / tf.norm(dim=-1, keepdim=True)
        scale = model.logit_scale.exp().detach()
    wrapper = ClipFemale(model, tf.detach(), scale).eval()
    dummy = torch.randn(1, 3, 224, 224, dtype=torch.float32)
    fp32 = out_dir / "clip_female_vision_fp32.onnx"
    fp16 = out_dir / "clip_female_vision_fp16.onnx"
    with torch.inference_mode():
        torch.onnx.export(
            wrapper, (dummy,), str(fp32), input_names=["pixel_values"], output_names=["probs"],
            dynamic_axes={"pixel_values": {0: "batch"}, "probs": {0: "batch"}},
            opset_version=OPSET, do_constant_folding=True,
        )
    fp16_convert(fp32, fp16)
    fp32.unlink(missing_ok=True)
    return {
        "repo": CLIP_REPO, "input_hw": [224, 224], "labels": ["woman", "man", "no_person"],
        "prompts_per_group": 4,
        "image_mean": [0.48145466, 0.4578275, 0.40821073],
        "image_std": [0.26862954, 0.26130258, 0.27577711], "resample": "bicubic",
    }


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("output"); args = ap.parse_args()
    out_dir = Path(args.output); out_dir.mkdir(parents=True, exist_ok=True)
    sig = export_siglip(out_dir); clip = export_clip(out_dir)
    manifest = {
        "version": 1, "siglip": sig, "clip": clip,
        "notes": [
            "SigLIP ONNX outputs raw logits; app applies sigmoid to class index 4.",
            "CLIP ONNX already outputs grouped woman/man/no_person softmax probabilities.",
            "Both ONNX files use FLOAT16 input/output where applicable.",
        ],
    }
    (out_dir / "model_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    for p in out_dir.iterdir(): print(p.name, f"{p.stat().st_size / 1024 / 1024:.2f} MB")


if __name__ == "__main__": main()
