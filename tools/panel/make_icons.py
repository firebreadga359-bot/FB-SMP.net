from PIL import Image, ImageDraw, ImageFont


def icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    radius = int(size * 0.22)
    grad = Image.new("RGB", (size, size))
    gd = ImageDraw.Draw(grad)
    top, bot = (240, 189, 97), (189, 119, 39)
    for y in range(size):
        t = y / size
        gd.line([(0, y), (size, y)], fill=(
            int(top[0] + (bot[0] - top[0]) * t),
            int(top[1] + (bot[1] - top[1]) * t),
            int(top[2] + (bot[2] - top[2]) * t),
        ))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    img.paste(grad, (0, 0), mask)
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(size * 0.42))
    except Exception:
        font = ImageFont.load_default()
    bb = d.textbbox((0, 0), "FB", font=font)
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.text(((size - tw) / 2 - bb[0], (size - th) / 2 - bb[1]), "FB", font=font, fill=(32, 23, 11, 255))
    return img


for s in (192, 512, 180):
    path = f"/workspace/fb-smp/tools/panel/icon-{s}.png"
    icon(s).save(path)
    print("wrote", path)
