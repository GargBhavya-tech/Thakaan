"""Builds the tiny float32 test model used by ticket #6 (inference seam). Not a real Thakaan model.

Usage: python tools/make_tiny_model.py app/src/main/assets/tiny.tflite
Needs tensorflow (use a throwaway venv). Model files are gitignored; regenerate with this script.
"""
import sys

import numpy as np
import tensorflow as tf

out = sys.argv[1] if len(sys.argv) > 1 else "tiny.tflite"

rng = np.random.default_rng(7)
w1 = rng.standard_normal((16, 32)).astype("float32") * 0.2
b1 = np.zeros(32, "float32")
w2 = rng.standard_normal((32, 4)).astype("float32") * 0.2
b2 = np.zeros(4, "float32")


class Tiny(tf.Module):
    @tf.function(input_signature=[tf.TensorSpec([1, 16], tf.float32)])
    def __call__(self, x):
        h = tf.nn.relu(tf.matmul(x, w1) + b1)
        return tf.matmul(h, w2) + b2


m = Tiny()
conv = tf.lite.TFLiteConverter.from_concrete_functions([m.__call__.get_concrete_function()], m)
open(out, "wb").write(conv.convert())
print("wrote", out)
