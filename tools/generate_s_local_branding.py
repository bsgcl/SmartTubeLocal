"""Rebuild S LOCAL branding from the existing SmartTube beta artwork.

The source artwork is reused to preserve its colours, rounded panels and stands.
Only the central 'S beta' lettering is replaced. Run from any working directory.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "smarttubetv" / "src" / "stbeta" / "res"
S_FONT = r"C:\Windows\Fonts\segoeuil.ttf"
LOCAL_FONT = r"C:\Windows\Fonts\segoeuib.ttf"

# Relative asset, central paint area, background sample column, S box, LOCAL box.
ASSETS = [
    ("mipmap-nodpi/app_icon.png",
     (65, 68, 252, 240), 40, (108, 69, 212, 184), (60, 190, 260, 231)),
    ("mipmap-nodpi-v30/app_icon.png",
     (86, 103, 236, 239), 58, (118, 102, 203, 189), (74, 192, 246, 230)),
    ("mipmap-nodpi/app_banner.png",
     (238, 88, 402, 260), 218, (264, 89, 376, 199), (220, 207, 420, 254)),
    ("mipmap-nodpi/app_logo.png",
     (35, 36, 147, 141), 22, (59, 39, 121, 104), (28, 110, 153, 136)),
    ("mipmap-nodpi/app_logo_semi_grey.png",
     (35, 36, 147, 141), 22, (59, 39, 121, 104), (28, 110, 153, 136)),
    ("mipmap-nodpi/app_logo_semi_red.png",
     (35, 36, 147, 141), 22, (59, 39, 121, 104), (28, 110, 153, 136)),
]

def draw_fitted(draw, text, font_file, box):
    x0, y0, x1, y1 = box
    for size in range(160, 7, -1):
        font = ImageFont.truetype(font_file, size)
        left, top, right, bottom = draw.textbbox((0, 0), text, font=font)
        width, height = right-left, bottom-top
        if width <= x1-x0 and height <= y1-y0:
            x = round((x0+x1-width)/2-left)
            y = round((y0+y1-height)/2-top)
            draw.text((x, y), text, font=font, fill=(255, 255, 255, 255))
            return
    raise ValueError("Text does not fit: " + text)

for name, area, sample_x, s_box, label_box in ASSETS:
    file_path = ROOT / name
    im = Image.open(file_path).convert("RGBA")
    draw = ImageDraw.Draw(im)
    left, top, right, bottom = area
    # Restore the untouched, solid original tile colours row by row.
    for y in range(top, bottom):
        draw.line((left, y, right-1, y), fill=im.getpixel((sample_x, y)))
    draw_fitted(draw, "S", S_FONT, s_box)
    draw_fitted(draw, "LOCAL", LOCAL_FONT, label_box)
    im.save(file_path, "PNG", optimize=True)
    print(f"{file_path.relative_to(ROOT)}: {im.width}x{im.height}")
