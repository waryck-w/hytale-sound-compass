#!/usr/bin/env python3
"""Catch missing/escaping custom-UI texture references in the built JAR."""
import hashlib
import json
import math
from pathlib import PurePosixPath
import re
import struct
import sys
import zipfile
import zlib


def validate_arc_centres(archive, root):
    for degrees in range(0, 360, 45):
        data = archive.read(root + f'Arcs/{degrees:03}.png')
        width, height = struct.unpack('>II', data[16:24])
        assert (width, height) == (256, 256), 'Arc frames must use the full square canvas'
        compressed = bytearray()
        offset = 8
        while offset < len(data):
            length = struct.unpack('>I', data[offset:offset + 4])[0]
            if data[offset + 4:offset + 8] == b'IDAT':
                compressed.extend(data[offset + 8:offset + 8 + length])
            offset += length + 12
        pixels = zlib.decompress(compressed)
        visible = 0
        for y in range(height):
            row = y * (width * 4 + 1)
            assert pixels[row] == 0, 'Generated PNG uses unfiltered rows'
            for x in range(width):
                if pixels[row + 1 + x * 4 + 3] == 0:
                    continue
                visible += 1
                dx, dy = (x + .5 - width / 2) * 500 / width, (y + .5 - height / 2) * 500 / height
                assert 185 < math.hypot(dx, dy) < 195, 'Arc pixels must share the fixed circle centre'
                delta = (math.atan2(dx, -dy) - math.radians(degrees) + math.pi) % (2 * math.pi) - math.pi
                assert abs(delta) < math.radians(18), 'Arc frame must match its icon bearing'
        assert visible > 0, 'Missing visible arc pixels'


def validate(package):
    count = 0
    root = 'Common/UI/Custom/SoundCompass/'
    with zipfile.ZipFile(package) as archive:
        names = set(archive.namelist())
        for document in ('Large.ui', 'Standard.ui'):
            filename = root + document
            contents = archive.read(filename).decode()
            textures = set(re.findall(r'(?:Background|TexturePath):\s*"([^"]+)"', contents))
            assert len(textures) == 370, f'{document}: expected 360 arc patches and 10 icons'
            arc_values = re.findall(r'@Arc(\d{3})\s*=\s*PatchStyle', contents)
            assert arc_values == [f'{i:03}' for i in range(360)], f'{document}: incomplete rotation styles'
            assert re.search(r'Group #SoundCompassRoot\s*\{\s*Anchor: \(Full: 0\);\s*LayoutMode: CenterMiddle;', contents), f'{document}: root must fill the viewport before centering'
            assert re.search(r'Group #CompassCanvas\s*\{\s*Anchor: \(Width: \d+, Height: \d+\);\s*LayoutMode: Full;', contents), f'{document}: compass must use absolute positioning'
            for slot in range(9):
                assert f'Group #Marker{slot} ' in contents, f'{document}: missing movable marker'
                assert re.search(rf'Group #Cue{slot}\s*\{{\s*Anchor: \(Full: 0\);\s*LayoutMode: Full;', contents), f'{document}: sound visibility must not reflow the canvas'
                if slot < 8:
                    assert re.search(rf'Group #Arc{slot}\s*\{{\s*Anchor: \(Full: 0\);', contents), f'{document}: arc must share the fixed canvas centre'
            assert not re.search(r'\bLabel\b|\bText\s*:', contents), f'{document}: icon-only UI must not contain text'
            for texture in textures:
                path = PurePosixPath(texture)
                # The client custom-UI loader cannot resolve the original parent paths.
                assert not path.is_absolute() and '..' not in path.parts, f'Unsupported custom UI path: {texture}'
                target = root + str(path)
                assert target in names, f'Missing packaged texture: {target}'
                assert archive.read(target).startswith(b'\x89PNG\r\n\x1a\n'), target
                count += 1
        sources = json.loads(archive.read(root + 'icon-sources.json'))
        validate_arc_centres(archive, root)
        assert len(sources) == 10
        for target, source in sources.items():
            assert hashlib.sha256(archive.read(root + target)).hexdigest() == source['sha256'], target
    return count


if __name__ == '__main__':
    print(f'Validated {validate(sys.argv[1])} UI texture references and 10 exact native icon copies; both layouts are icon-only.')
