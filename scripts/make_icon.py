"""生成启动图标：粉色底 + 白色暂停双竖条 + 右上角白色叉。运行： python scripts/make_icon.py"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PINK = (251, 114, 153, 255)
WHITE = (255, 255, 255, 255)
SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}
SS = 8  # 超采样倍数


def draw(size):
    s = size * SS
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(s * 0.22)
    d.rounded_rectangle([0, 0, s - 1, s - 1], radius=radius, fill=PINK)

    # 暂停双竖条（居中偏左一点，给右上角的叉留位置）
    bar_w = int(s * 0.15)
    bar_h = int(s * 0.40)
    gap = int(s * 0.09)
    cx = s * 0.46
    cy = s * 0.55
    r = bar_w // 2
    left_x = cx - gap / 2 - bar_w
    right_x = cx + gap / 2
    for x in (left_x, right_x):
        d.rounded_rectangle(
            [x, cy - bar_h / 2, x + bar_w, cy + bar_h / 2], radius=r, fill=WHITE
        )

    # 右上角白色叉
    cx2, cy2 = s * 0.76, s * 0.26
    arm = int(s * 0.105)
    for dx in (1, -1):
        d.line(
            [(cx2 - arm, cy2 + dx * arm), (cx2 + arm, cy2 - dx * arm)],
            fill=WHITE,
            width=max(2, int(s * 0.045)),
        )
    return img.resize((size, size), Image.LANCZOS)


def main():
    for folder, size in SIZES.items():
        out_dir = os.path.join(ROOT, "res", folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.png")
        draw(size).save(path, "PNG", optimize=True)
        print(f"{path}  {size}x{size}")


if __name__ == "__main__":
    main()
