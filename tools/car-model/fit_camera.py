#!/usr/bin/env python3
# Recovers where each reference photograph was taken from, so a render can be laid over it.
# Points on the car (metres; front towards -Y, car's right is -X) are matched by hand to pixels;
# Gauss-Newton on camera position, aim, roll and focal length. Writes cameras.json.
import json
import os

import numpy as np

W = 0.962  # wheel face from the centreline
FRONT, REAR = -1.445, 1.445  # axles
R = 0.356  # tyre radius

# Phone main cameras, in 35 mm terms; the others are fitted freely.
LENS_HINT = {"red2": 34.0, "blue3": 30.0}

# photo: (width, height, [(xyz, pixel), ...], first guess of the camera)
PHOTOS = {
    "red2": (
        1600, 1204,
        [
            ((-W, FRONT, R), (1325, 712)), ((-W, REAR, R), (315, 732)),
            ((-W, FRONT, 0), (1320, 838)), ((-W, REAR, 0), (315, 848)),
            ((-1.03, -0.62, 1.145), (1040, 447)),  # mirror cap top
        ],
        (-5.0, 0.0, 0.9),
    ),
    "blue3": (
        1400, 1050,
        [
            ((-W, FRONT, R), (1155, 692)), ((-W, REAR, R), (265, 712)),
            ((-W, FRONT, 0), (1152, 796)), ((-W, REAR, 0), (268, 815)),
            ((-1.03, -0.62, 1.145), (905, 442)),
        ],
        (-6.0, 0.0, 0.9),
    ),
    "red1": (
        1600, 1066,
        [
            ((-W, FRONT, R), (745, 622)), ((-W, REAR, R), (405, 566)),
            ((-W, FRONT, 0), (745, 740)), ((-W, REAR, 0), (405, 642)),
            ((0.0, -2.19, 0.815), (1092, 497)),  # hood badge
            ((-1.03, -0.62, 1.145), (615, 385)),
            ((-0.60, -2.3, 0.33), (975, 665)),  # grille's near corner
        ],
        (-4.0, -7.0, 1.1),
    ),
    "red3": (
        1200, 800,
        [
            ((-W, FRONT, R), (483, 540)), ((-W, REAR, R), (183, 462)),
            ((-W, FRONT, 0), (483, 655)), ((-W, REAR, 0), (195, 530)),
            ((0.0, -2.19, 0.815), (877, 386)),
            ((-1.03, -0.62, 1.145), (355, 297)),
            ((-0.60, -2.3, 0.33), (760, 585)),
        ],
        (-3.0, -6.0, 1.6),
    ),
    "blue1": (
        1500, 1000,
        [
            ((-W, REAR, R), (925, 685)), ((-W, FRONT, R), (1305, 625)),
            ((-W, REAR, 0), (925, 830)), ((-W, FRONT, 0), (1300, 757)),
            ((0.0, 2.31, 1.135), (383, 368)),  # trunk badge
            ((0.0, 2.32, 0.80), (395, 472)),  # plate centre
            ((-1.03, -0.62, 1.145), (1270, 375)),
        ],
        (-4.0, 7.0, 0.9),
    ),
}


def rotation(params):
    """World-to-camera rotation from position, aim and roll. Camera looks along -Z, up is +Y."""
    position, target, roll = params[:3], params[3:6], params[6]
    forward = target - position
    forward /= np.linalg.norm(forward)
    right = np.cross(forward, (0, 0, 1))
    right /= np.linalg.norm(right)
    up = np.cross(right, forward)
    c, s = np.cos(roll), np.sin(roll)
    return np.array([c * right + s * up, -s * right + c * up, -forward])


def project(params, points, width, height):
    rot = rotation(params)
    focal = params[7]
    cam = (points - params[:3]) @ rot.T
    x = -focal * cam[:, 0] / cam[:, 2] + width / 2 + params[8] * 0
    y = focal * cam[:, 1] / cam[:, 2] + height / 2
    return np.stack([x, y], axis=1)


def fit(name, width, height, pairs, start):
    points = np.array([p for p, _ in pairs], dtype=float)
    pixels = np.array([q for _, q in pairs], dtype=float)
    params = np.array([*start, 0, 0, 0.7, 0, width * 1.3, 0.0], dtype=float)
    damping = 1e-2

    def residual(p):
        prior = (p[3:6] - (0, 0, 0.7)) * 2  # the aim only matters up to the line of sight
        lens = 36.0 * p[7] / width
        hints = [(lens - LENS_HINT.get(name, lens)) * 40.0, p[6] * 300.0]  # phones, held about level
        return np.concatenate([(project(p, points, width, height) - pixels).ravel(), prior, hints])

    error = residual(params)
    for _ in range(400):
        jac = np.zeros((error.size, params.size))
        for i in range(params.size):
            step = np.zeros_like(params)
            step[i] = 1e-4 * max(1.0, abs(params[i]))
            jac[:, i] = (residual(params + step) - error) / step[i]
        delta = np.linalg.solve(jac.T @ jac + damping * np.eye(params.size), -jac.T @ error)
        trial = params + delta
        trial_error = residual(trial)
        if trial_error @ trial_error < error @ error:
            params, error, damping = trial, trial_error, max(damping * 0.5, 1e-6)
        else:
            damping *= 3
    pixel_error = np.sqrt(np.mean((project(params, points, width, height) - pixels) ** 2))
    return params, pixel_error


def main():
    out = {}
    for name, (width, height, pairs, start) in PHOTOS.items():
        params, err = fit(name, width, height, pairs, start)
        lens = 36.0 * params[7] / width
        print(f"{name}: position {np.round(params[:3], 2)} lens {lens:.1f} mm roll {np.degrees(params[6]):.2f} deg, error {err:.1f} px of {width}")
        out[name] = {
            "position": params[:3].tolist(),
            "target": params[3:6].tolist(),
            "roll": float(params[6]),
            "lens": float(lens),
            "aspect": width / height,
        }
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cameras.json")
    with open(path, "w") as f:
        json.dump(out, f, indent=1)


if __name__ == "__main__":
    main()
