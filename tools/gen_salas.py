#!/usr/bin/env python3
"""Convierte tools/salas.txt en mapas de Tiled (assets/rooms/*.tmx) y valida cada sala.

Uso: python3 tools/gen_salas.py

Validación: con las 4 puertas abiertas, todas las apariciones (S) y pedestales (P)
deben ser alcanzables a pie desde cualquier puerta (el agua, los muros y los candelabros
bloquean; el vitral se pisa).
"""
import json
import os
import sys
from collections import deque

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "tools", "salas.txt")
OUT = os.path.join(ROOT, "assets", "rooms")

WALL, WATER, SPAWN, PEDESTAL, GLASS, CANDLE = 1, 2, 3, 4, 5, 6
SHELF, RAIL, ATRIL, MOLTEN = 7, 8, 9, 10
EYE = 5  # marcador (capa de marcadores)
WALKABLE = (0, GLASS, RAIL, MOLTEN)
SIZES = {"jefe": (30, 17), "cat_jefe": (30, 17), "arc_jefe": (30, 17), "raz_jefe": (30, 17), "grande_h": (52, 14), "grande_v": (26, 28)}
DEFAULT = (26, 14)


def parse(text):
    rooms, cur = [], None
    for raw in text.splitlines():
        line = raw.rstrip()
        if line.startswith("==="):
            kind, name = line[3:].split()
            cur = {"kind": kind, "name": name, "rows": []}
            rooms.append(cur)
        elif cur is not None and line and not line.startswith("#"):
            cur["rows"].append(line)
    return rooms


def build(room):
    cols, rows = SIZES.get(room["kind"], DEFAULT)
    inner = room["rows"]
    errs = []
    if len(inner) != rows - 2:
        errs.append(f"tiene {len(inner)} filas, se esperaban {rows - 2}")
    for i, r in enumerate(inner):
        if len(r) != cols - 2:
            errs.append(f"fila {i} mide {len(r)}, se esperaban {cols - 2}: '{r}'")
    if errs:
        return None, errs
    # Rejilla con origen abajo a la izquierda (como el juego).
    terrain = [[0] * cols for _ in range(rows)]
    marks = [[0] * cols for _ in range(rows)]
    for y in range(rows):
        for x in range(cols):
            if x in (0, cols - 1) or y in (0, rows - 1):
                terrain[y][x] = WALL
    for i, r in enumerate(inner):
        y = rows - 2 - i
        for j, ch in enumerate(r):
            x = j + 1
            if ch == "#": terrain[y][x] = WALL
            elif ch == "~": terrain[y][x] = WATER
            elif ch == "V": terrain[y][x] = GLASS
            elif ch == "K": terrain[y][x] = CANDLE
            elif ch == "E": terrain[y][x] = SHELF
            elif ch == "=": terrain[y][x] = RAIL
            elif ch == "A": terrain[y][x] = ATRIL
            elif ch == "F": terrain[y][x] = MOLTEN
            elif ch == "O": marks[y][x] = EYE
            elif ch == "S": marks[y][x] = SPAWN
            elif ch == "P": marks[y][x] = PEDESTAL
            elif ch != ".": errs.append(f"carácter desconocido '{ch}'")
    return (cols, rows, terrain, marks), errs


def door_tiles(cols, rows):
    """Tiles de cada puerta + pasillo de 3 tiles hacia dentro (igual que Room.kt).
    Las salas grandes tienen una puerta por subcelda en cada lado exterior."""
    nx = 2 if cols >= 52 else 1
    ny = 2 if rows >= 28 else 1
    sc, sr = cols // nx, rows // ny
    out = {}
    for sx in range(nx):
        for sy in range(ny):
            ry = (sy * sr + sr // 2 - 1, sy * sr + sr // 2)
            cx = (sx * sc + sc // 2 - 1, sx * sc + sc // 2)
            if sx == 0:
                out[f"L{sy}"] = [(x, y) for y in ry for x in range(0, 4)]
            if sx == nx - 1:
                out[f"R{sy}"] = [(x, y) for y in ry for x in range(cols - 4, cols)]
            if sy == 0:
                out[f"D{sx}"] = [(x, y) for x in cx for y in range(0, 4)]
            if sy == ny - 1:
                out[f"U{sx}"] = [(x, y) for x in cx for y in range(rows - 4, rows)]
    return out


def validate(cols, rows, terrain, marks):
    # El vitral se pisa (cuenta como suelo para la validación).
    # Las estanterías (E) cuentan como muro en su posición inicial.
    t = [[0 if v in WALKABLE else v for v in row] for row in terrain]
    doors = door_tiles(cols, rows)
    for tiles in doors.values():
        for x, y in tiles:
            t[y][x] = 0
    start = doors["L0"][0]
    seen = {start}
    q = deque([start])
    while q:
        x, y = q.popleft()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            nx, ny = x + dx, y + dy
            if 0 <= nx < cols and 0 <= ny < rows and (nx, ny) not in seen and t[ny][nx] == 0:
                seen.add((nx, ny))
                q.append((nx, ny))
    errs = []
    for side, tiles in doors.items():
        if tiles[0] not in seen:
            errs.append(f"puerta {side} desconectada")
    for y in range(rows):
        for x in range(cols):
            if marks[y][x] and (x, y) not in seen:
                errs.append(f"marcador en ({x},{y}) inalcanzable")
            if marks[y][x] and terrain[y][x] not in (0, RAIL) and not (terrain[y][x] in (GLASS, MOLTEN) and marks[y][x] == SPAWN):
                errs.append(f"marcador sobre muro/agua/vitral en ({x},{y})")
    return errs


def tmx(name, kind, cols, rows, terrain, marks):
    def csv(grid):
        lines = []
        for y in range(rows - 1, -1, -1):  # Tiled escribe de arriba hacia abajo
            lines.append(",".join(str(v) for v in grid[y]))
        return ",\n".join(lines)

    return f'''<?xml version="1.0" encoding="UTF-8"?>
<map version="1.10" tiledversion="1.11.0" orientation="orthogonal" renderorder="right-down" width="{cols}" height="{rows}" tilewidth="16" tileheight="16" infinite="0" nextlayerid="3" nextobjectid="1">
 <properties>
  <property name="tipo" value="{kind}"/>
  <property name="nombre" value="{name}"/>
 </properties>
 <tileset firstgid="1" source="muelle.tsx"/>
 <layer id="1" name="terreno" width="{cols}" height="{rows}">
  <data encoding="csv">
{csv(terrain)}
</data>
 </layer>
 <layer id="2" name="marcadores" width="{cols}" height="{rows}">
  <data encoding="csv">
{csv(marks)}
</data>
 </layer>
</map>
'''


TSX = '''<?xml version="1.0" encoding="UTF-8"?>
<tileset version="1.10" tiledversion="1.11.0" name="muelle" tilewidth="16" tileheight="16" tilecount="10" columns="10">
 <image source="muelle.png" width="160" height="16"/>
 <tile id="0"><properties><property name="tipo" value="muro"/></properties></tile>
 <tile id="1"><properties><property name="tipo" value="agua"/></properties></tile>
 <tile id="2"><properties><property name="tipo" value="aparicion"/></properties></tile>
 <tile id="3"><properties><property name="tipo" value="pedestal"/></properties></tile>
 <tile id="4"><properties><property name="tipo" value="vitral"/></properties></tile>
 <tile id="5"><properties><property name="tipo" value="candelabro"/></properties></tile>
 <tile id="6"><properties><property name="tipo" value="estanteria"/></properties></tile>
 <tile id="7"><properties><property name="tipo" value="riel"/></properties></tile>
 <tile id="8"><properties><property name="tipo" value="atril"/></properties></tile>
 <tile id="9"><properties><property name="tipo" value="vidrio_fundido"/></properties></tile>
</tileset>
'''


def tileset_png(path):
    from PIL import Image, ImageDraw
    img = Image.new("RGBA", (160, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 15, 15], fill="#2c3946"); d.rectangle([0, 0, 15, 2], fill="#4a5b6a")
    d.rectangle([16, 0, 31, 15], fill="#1b4a60"); d.line([19, 6, 25, 6], fill="#2f7590"); d.line([22, 11, 28, 11], fill="#2f7590")
    d.line([34, 2, 45, 13], fill="#e57a5c", width=2); d.line([45, 2, 34, 13], fill="#e57a5c", width=2)
    d.rectangle([52, 4, 59, 15], fill="#9b7a52"); d.rectangle([54, 1, 57, 4], fill="#ffb547")
    d.rectangle([64, 0, 79, 15], fill="#3e6a78"); d.rectangle([66, 2, 71, 7], fill="#9fd6d0"); d.rectangle([72, 8, 77, 13], fill="#7a5aa0")
    d.rectangle([86, 6, 89, 15], fill="#9b917a"); d.rectangle([84, 3, 91, 5], fill="#d8cfb4"); d.rectangle([87, 0, 88, 2], fill="#ffb547")
    d.rectangle([96, 0, 111, 15], fill="#4a3424"); d.rectangle([98, 2, 100, 13], fill="#8e3b36"); d.rectangle([102, 2, 104, 13], fill="#3b5a7a"); d.rectangle([106, 2, 108, 13], fill="#b08a3e")
    d.line([112, 8, 127, 8], fill="#4a5f8e", width=2)
    d.rectangle([134, 6, 137, 15], fill="#6b5a3e"); d.rectangle([132, 3, 139, 5], fill="#e8dcc0"); d.rectangle([135, 0, 136, 2], fill="#8fd694")
    d.rectangle([144, 0, 159, 15], fill="#ff7a2f"); d.rectangle([147, 4, 152, 8], fill="#ffc36b")
    img.save(path)


def main():
    rooms = parse(open(SRC, encoding="utf-8").read())
    os.makedirs(OUT, exist_ok=True)
    index, failed = {}, False
    for r in rooms:
        built, errs = build(r)
        if built:
            errs += validate(*built)
        label = f"{r['kind']}/{r['name']}"
        if errs:
            failed = True
            print(f"✗ {label}")
            for e in errs:
                print(f"    {e}")
            continue
        fname = f"{r['kind']}_{r['name']}.tmx"
        with open(os.path.join(OUT, fname), "w", encoding="utf-8") as f:
            f.write(tmx(r["name"], r["kind"], *built))
        index.setdefault(r["kind"], []).append(fname)
        print(f"✓ {label}")
    with open(os.path.join(OUT, "muelle.tsx"), "w", encoding="utf-8") as f:
        f.write(TSX)
    tileset_png(os.path.join(OUT, "muelle.png"))
    with open(os.path.join(OUT, "index.json"), "w", encoding="utf-8") as f:
        json.dump(index, f, indent=2, ensure_ascii=False)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
