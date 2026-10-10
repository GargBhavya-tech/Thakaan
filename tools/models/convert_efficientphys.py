"""Converts the pretrained EfficientPhys weights (rPPG-Toolbox, UBFC-rPPG / PURE checkpoints) to a fixed-shape TFLite model.

The network is re-built in Keras from the published architecture (neural_methods/model/EfficientPhys.py in
ubicomplab/rPPG-Toolbox), the PyTorch weights are copied over, and the result is checked against PyTorch on random input
before it is written. Licence: the toolbox uses a Responsible-AI source-code licence and the checkpoints were trained on
public face datasets with their own terms: fine for a hackathon prototype, to be cleared before commercial use.

Usage: python tools/models/convert_efficientphys.py <checkpoint.pth> <out_dir>
Input  [21, S, S, 3]  21 consecutive face crops (RGB, 0..255 floats), S = 36 or 72 (read from the checkpoint)
Output [20, 1]        one pulse-wave sample per frame difference; the app reads the rate from its spectrum.
"""
import sys
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn

T = 20


class Attn(nn.Module):
    def forward(self, x):
        s = x.sum(dim=2, keepdim=True).sum(dim=3, keepdim=True)
        return x / s * x.shape[2] * x.shape[3] * 0.5


class TSM(nn.Module):
    def __init__(self, n=T, fold_div=3):
        super().__init__(); self.n = n; self.fd = fold_div

    def forward(self, x):
        nt, c, h, w = x.size(); b = nt // self.n
        x = x.view(b, self.n, c, h, w); fold = c // self.fd
        out = torch.zeros_like(x)
        out[:, :-1, :fold] = x[:, 1:, :fold]
        out[:, 1:, fold:2 * fold] = x[:, :-1, fold:2 * fold]
        out[:, :, 2 * fold:] = x[:, :, 2 * fold:]
        return out.view(nt, c, h, w)


class EfficientPhys(nn.Module):
    def __init__(self, img):
        super().__init__()
        self.TSM_1 = TSM(); self.TSM_2 = TSM(); self.TSM_3 = TSM(); self.TSM_4 = TSM()
        self.motion_conv1 = nn.Conv2d(3, 32, 3, padding=1); self.motion_conv2 = nn.Conv2d(32, 32, 3)
        self.motion_conv3 = nn.Conv2d(32, 64, 3, padding=1); self.motion_conv4 = nn.Conv2d(64, 64, 3)
        self.apperance_att_conv1 = nn.Conv2d(32, 1, 1); self.attn_mask_1 = Attn()
        self.apperance_att_conv2 = nn.Conv2d(64, 1, 1); self.attn_mask_2 = Attn()
        self.avg_pooling_1 = nn.AvgPool2d(2); self.avg_pooling_3 = nn.AvgPool2d(2)
        self.final_dense_1 = nn.Linear({36: 3136, 72: 16384, 96: 30976}[img], 128); self.final_dense_2 = nn.Linear(128, 1)
        self.batch_norm = nn.BatchNorm2d(3)

    def forward(self, x):  # x [21, 3, S, S]
        x = torch.diff(x, dim=0); x = self.batch_norm(x)
        d1 = torch.tanh(self.motion_conv1(self.TSM_1(x))); d1 = self.TSM_2(d1)
        d2 = torch.tanh(self.motion_conv2(d1))
        g1 = self.attn_mask_1(torch.sigmoid(self.apperance_att_conv1(d2)))
        d3 = self.avg_pooling_1(d2 * g1)
        d4 = self.TSM_3(d3)
        d5 = self.TSM_4(torch.tanh(self.motion_conv3(d4)))
        d6 = torch.tanh(self.motion_conv4(d5))
        g2 = self.attn_mask_2(torch.sigmoid(self.apperance_att_conv2(d6)))
        d7 = self.avg_pooling_3(d6 * g2)
        d10 = torch.tanh(self.final_dense_1(d7.reshape(d7.size(0), -1)))
        return self.final_dense_2(d10)


def build_keras(sd, img):
    import tensorflow as tf

    def conv_w(name):
        w = sd[name + ".weight"].numpy().transpose(2, 3, 1, 0); b = sd[name + ".bias"].numpy(); return [w, b]

    def tsm(x):
        c = x.shape[-1]; fold = c // 3
        left = tf.pad(x[1:, :, :, :fold], [[0, 1], [0, 0], [0, 0], [0, 0]])
        right = tf.pad(x[:-1, :, :, fold:2 * fold], [[1, 0], [0, 0], [0, 0], [0, 0]])
        return tf.concat([left, right, x[:, :, :, 2 * fold:]], axis=-1)

    def attn(x):
        s = tf.reduce_sum(x, axis=[1, 2], keepdims=True)
        return x / s * float(x.shape[1] * x.shape[2]) * 0.5

    inp = tf.keras.Input(batch_shape=(T + 1, img, img, 3))
    x = tf.keras.layers.Lambda(lambda z: z[1:] - z[:-1])(inp)
    bn = tf.keras.layers.BatchNormalization(epsilon=1e-5); x = bn(x)
    x = tf.keras.layers.Lambda(tsm)(x)
    c1 = tf.keras.layers.Conv2D(32, 3, padding="same"); d1 = tf.keras.layers.Activation("tanh")(c1(x))
    d1 = tf.keras.layers.Lambda(tsm)(d1)
    c2 = tf.keras.layers.Conv2D(32, 3); d2 = tf.keras.layers.Activation("tanh")(c2(d1))
    a1 = tf.keras.layers.Conv2D(1, 1, activation="sigmoid"); g1 = tf.keras.layers.Lambda(attn)(a1(d2))
    d3 = tf.keras.layers.AveragePooling2D(2)(d2 * g1)
    d4 = tf.keras.layers.Lambda(tsm)(d3)
    c3 = tf.keras.layers.Conv2D(64, 3, padding="same"); d5 = tf.keras.layers.Activation("tanh")(c3(d4))
    d5 = tf.keras.layers.Lambda(tsm)(d5)
    c4 = tf.keras.layers.Conv2D(64, 3); d6 = tf.keras.layers.Activation("tanh")(c4(d5))
    a2 = tf.keras.layers.Conv2D(1, 1, activation="sigmoid"); g2 = tf.keras.layers.Lambda(attn)(a2(d6))
    d7 = tf.keras.layers.AveragePooling2D(2)(d6 * g2)
    flat = tf.keras.layers.Flatten()(d7)
    h, w, ch = d7.shape[1], d7.shape[2], d7.shape[3]
    dn1 = tf.keras.layers.Dense(128, activation="tanh"); dn2 = tf.keras.layers.Dense(1)
    out = dn2(dn1(flat))
    m = tf.keras.Model(inp, out)

    bn.set_weights([sd["batch_norm.weight"].numpy(), sd["batch_norm.bias"].numpy(), sd["batch_norm.running_mean"].numpy(), sd["batch_norm.running_var"].numpy()])
    c1.set_weights(conv_w("motion_conv1")); c2.set_weights(conv_w("motion_conv2"))
    c3.set_weights(conv_w("motion_conv3")); c4.set_weights(conv_w("motion_conv4"))
    a1.set_weights(conv_w("apperance_att_conv1")); a2.set_weights(conv_w("apperance_att_conv2"))
    w1 = sd["final_dense_1.weight"].numpy().reshape(128, ch, h, w).transpose(2, 3, 1, 0).reshape(h * w * ch, 128)  # NCHW -> NHWC flatten
    dn1.set_weights([w1, sd["final_dense_1.bias"].numpy()])
    dn2.set_weights([sd["final_dense_2.weight"].numpy().T, sd["final_dense_2.bias"].numpy()])
    return m


def main(ckpt, out_dir):
    import tensorflow as tf
    sd = torch.load(ckpt, map_location="cpu", weights_only=True)
    sd = {k.removeprefix("module."): v for k, v in sd.items()}
    img = {3136: 36, 16384: 72, 30976: 96}[sd["final_dense_1.weight"].shape[1]]
    print("checkpoint image size", img)
    ref = EfficientPhys(img); ref.load_state_dict(sd); ref.eval()
    km = build_keras(sd, img)
    rng = np.random.default_rng(0)
    x = rng.uniform(80, 200, (T + 1, img, img, 3)).astype("float32")
    with torch.no_grad():
        a = ref(torch.from_numpy(x.transpose(0, 3, 1, 2))).numpy()
    b = km(x, training=False).numpy()
    err = float(np.abs(a - b).max()); print("max |torch - keras| = %.2e" % err)
    assert err < 1e-3, "conversion does not match PyTorch"
    conv = tf.lite.TFLiteConverter.from_keras_model(km)
    data = conv.convert()
    p = Path(out_dir) / "efficientphys.tflite"; p.write_bytes(data)
    interp = tf.lite.Interpreter(model_content=data); interp.allocate_tensors()
    interp.set_tensor(interp.get_input_details()[0]["index"], x); interp.invoke()
    c = interp.get_tensor(interp.get_output_details()[0]["index"])
    print("max |torch - tflite| = %.2e" % float(np.abs(a - c).max()), "wrote", p, len(data), "bytes")
    (Path(out_dir) / "efficientphys_size.txt").write_text(str(img))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
