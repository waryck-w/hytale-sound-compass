#!/usr/bin/env python3
"""Catch missing/escaping custom-UI texture references in the built JAR."""
import hashlib
import json
from pathlib import PurePosixPath
import re
import sys
import zipfile


def validate(package):
    count = 0
    root = 'Common/UI/Custom/SoundCompass/'
    with zipfile.ZipFile(package) as archive:
        names = set(archive.namelist())
        for document in ('Large.ui', 'Standard.ui'):
            filename = root + document
            contents = archive.read(filename).decode()
            textures = set(re.findall(r'Background:\s*"([^"]+)"', contents))
            assert len(textures) == 18, f'{document}: expected 8 arcs and 10 icons'
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
        assert len(sources) == 10
        for target, source in sources.items():
            assert hashlib.sha256(archive.read(root + target)).hexdigest() == source['sha256'], target
    return count


if __name__ == '__main__':
    print(f'Validated {validate(sys.argv[1])} UI texture references and 10 exact native icon copies; both layouts are icon-only.')
