"""Converts SegFormer-B0 (Cityscapes) to a fixed-shape TFLite model that returns a per-pixel class map.

Source: nvidia/segformer-b0-finetuned-cityscapes-512-1024 (Hugging Face, TF weights). Licence: NVIDIA Source Code License,
non-commercial research or evaluation use only: fine for the hackathon prototype, to be replaced or licensed before any
commercial use (see docs/ASSETS.md).

Usage: python tools/models/convert_segformer.py <checkpoint_dir> <out_dir>
Input  [1, 3, 256, 512]  RGB, scaled to 0..1 then normalised with the ImageNet mean/std (done in the app)
Output [1, 64, 128]       Cityscapes class id per pixel (0 road ... 8 vegetation, 10 sky ... 13 car), int32
"""
import sys
from pathlib import Path

import numpy as np
import tensorflow as tf
from transformers import TFSegformerForSemanticSegmentation

H, W = 256, 512


def main(ckpt, out_dir):
    model = TFSegformerForSemanticSegmentation.from_pretrained(ckpt)

    @tf.function(input_signature=[tf.TensorSpec([1, 3, H, W], tf.float32)])
    def serve(x):
        logits = model(pixel_values=x, training=False).logits  # [1, 19, H/4, W/4]
        return tf.argmax(logits, axis=1, output_type=tf.int32)

    cf = serve.get_concrete_function()
    conv = tf.lite.TFLiteConverter.from_concrete_functions([cf], model)
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    data = conv.convert()
    p = Path(out_dir) / "segformer_b0_cityscapes.tflite"
    p.write_bytes(data)
    x = np.random.default_rng(1).standard_normal((1, 3, H, W)).astype("float32")
    ref = serve(tf.constant(x)).numpy()
    it = tf.lite.Interpreter(model_content=data); it.allocate_tensors()
    it.set_tensor(it.get_input_details()[0]["index"], x); it.invoke()
    got = it.get_tensor(it.get_output_details()[0]["index"])
    print("wrote", p, len(data), "bytes; pixels agreeing with the TF model: %.4f" % float((ref == got).mean()))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
