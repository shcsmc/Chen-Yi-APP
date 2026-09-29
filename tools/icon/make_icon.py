"""
生成 App 图标的两层安卓矢量图（改颜色/粗细后重新跑一次即可）：
  android/app/src/main/res/drawable/ic_launcher_background.xml  纸色背景
  android/app/src/main/res/drawable/ic_launcher_foreground.xml  朱砂 C + 墨蓝 Y 花押（单色主题图标也用这层）

需要：pip install shapely
用法：python3 tools/icon/make_icon.py

设计：背景取自方案 L（纸色），图形取自方案 O（CY 花押），Y 用 L 水滴的墨蓝，C 用朱砂红。
C 的两头收成圆头、和 Y 留出匀称的缝（不去「切」C，切口会在浅色底上留下尖角）。
自适应图标画布 108，桌面上看得到中间 72，安全区半径 33：图形控制在半径 31 以内。
"""
import math
import os
from shapely.geometry import LineString
from shapely.ops import unary_union

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res", "drawable")

PAPER = ("#FBF7EF", "#EEE4D2")     # 背景：上浅下暖
RED = ("#E4573D", "#BE3A26")       # C：朱砂
INK = ("#34457A", "#141B36")       # Y：墨蓝
W = 5.2                            # 笔画粗细
GAP = 2.4                          # C 和 Y 之间的缝
S, DX, DY = 0.93, 2.5, -1.0        # 整体缩放、挪到正中


def mv(pts):
    return [(54 + (x + DX - 54) * S, 54 + (y + DY - 54) * S) for x, y in pts]


def arc(cx, cy, r, a0, a1, n=96):
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n)))
            for i in range(n + 1)]


def stroke(pts):
    return LineString(mv(pts)).buffer(W / 2, quad_segs=24, cap_style="round", join_style="round")


y_parts = [[(58.5, 34), (66, 49.5)], [(73.5, 34), (66, 49.5), (66, 76)]]
Y = unary_union([stroke(p) for p in y_parts])
keep_out = unary_union([LineString(mv(p)).buffer(W / 2 + GAP, quad_segs=24, cap_style="round", join_style="round") for p in y_parts])


def c_shape(a0, a1):
    return stroke(arc(50, 55, 20, a0, a1))


# C 从 O 原来的 38°～322° 开始，两头各自往回收，直到圆头离 Y 正好一条缝
a0, a1 = 38.0, 322.0
while c_shape(a0, 180).intersects(keep_out):
    a0 += 0.25
while c_shape(180, a1).intersects(keep_out):
    a1 -= 0.25
C = c_shape(a0, a1)


def path(geom):
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    out = []
    for p in polys:
        for ring in [p.exterior, *p.interiors]:
            out.append("M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in list(ring.coords)[:-1]) + " Z")
    return " ".join(out)


def argb(c):
    return "#FF" + c[1:].upper()


def gradient_path(d, x1, y1, x2, y2, colors):
    return f'''    <path android:pathData="{d}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="{x1}" android:startY="{y1}"
                android:endX="{x2}" android:endY="{y2}" android:startColor="{argb(colors[0])}" android:endColor="{argb(colors[1])}" />
        </aapt:attr>
    </path>
'''


HEAD = '''<?xml version="1.0" encoding="utf-8"?>
{comment}
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''

background = HEAD.format(comment="<!-- 辰Yi记 图标背景：纸色（上浅下暖）。由 tools/icon/make_icon.py 生成，别手改 -->") \
    + gradient_path("M0,0h108v108h-108z", 54, 18, 54, 90, PAPER) + "</vector>\n"
foreground = HEAD.format(comment="""<!--
  辰Yi记 图标图形：C（Chen，朱砂红）+ Y（Yi，墨蓝）的字母花押。由 tools/icon/make_icon.py 生成，别手改。
  C 两头收成圆头、和 Y 之间留缝（真的缺口），所以安卓 13 的单色「主题图标」直接用这一层。
-->""") + gradient_path(path(C), 30, 35, 70, 75, RED) + gradient_path(path(Y), 58, 32, 78, 78, INK) + "</vector>\n"

far = max(math.hypot(x - 54, y - 54) for g in (C, Y) for x, y in g.exterior.coords)
assert far < 32, f"图形超出安全区：最远 {far:.1f}"
for name, text in (("ic_launcher_background.xml", background), ("ic_launcher_foreground.xml", foreground)):
    with open(os.path.join(RES, name), "w", encoding="utf-8") as f:
        f.write(text)
print(f"C {a0:.2f}°～{a1:.2f}°，最远点离中心 {far:.1f}（安全区 33），已写入 {RES}")
