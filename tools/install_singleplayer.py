#!/usr/bin/env python3
"""Install one Sound Compass build into a closed singleplayer save, with backups."""
import argparse
from datetime import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--save', type=Path, required=True)
parser.add_argument('--jar', type=Path, required=True)
parser.add_argument('--backup-dir', type=Path, required=True)
args = parser.parse_args()
config_file = args.save / 'config.json'
mods = args.save / 'mods'
if not config_file.is_file() or not mods.is_dir():
    raise SystemExit('Expected an existing Hytale save with config.json and mods/.')
with zipfile.ZipFile(args.jar) as archive:
    manifest = json.loads(archive.read('manifest.json'))
if (manifest.get('Group'), manifest.get('Name')) != ('Deaftale', 'SoundCompass'):
    raise SystemExit('The supplied JAR is not Sound Compass.')
lock = None
try:
    # Java's world-file locks use POSIX record locks on Linux.
    lock_file = args.save / '.world.lock'
    if lock_file.exists():
        lock = lock_file.open('r+')
        try:
            fcntl.lockf(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise SystemExit('The world is open. Save and return to the main menu first.')
    config = json.loads(config_file.read_text())
    older = []
    for candidate in mods.glob('SoundCompass-*.jar'):
        with zipfile.ZipFile(candidate) as archive:
            old_manifest = json.loads(archive.read('manifest.json'))
        if (old_manifest.get('Group'), old_manifest.get('Name')) == ('Deaftale', 'SoundCompass'):
            older.append(candidate)
    backup = args.backup_dir / datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup.mkdir(parents=True, exist_ok=False)
    shutil.copy2(config_file, backup / config_file.name)
    for candidate in older:
        shutil.copy2(candidate, backup / candidate.name)

    def atomic_write(destination, data, mode):
        fd, name = tempfile.mkstemp(prefix='.soundcompass-', suffix='.tmp', dir=destination.parent)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(name, mode)
            os.replace(name, destination)
        finally:
            Path(name).unlink(missing_ok=True)

    target = mods / args.jar.name
    contents = args.jar.read_bytes()
    atomic_write(target, contents, 0o644)
    config.setdefault('Mods', {}).setdefault('Deaftale:SoundCompass', {})['Enabled'] = True
    atomic_write(config_file, (json.dumps(config, indent=2) + '\n').encode(), config_file.stat().st_mode & 0o777)
    for candidate in older:
        if candidate != target:
            candidate.unlink()
    assert json.loads(config_file.read_text())['Mods']['Deaftale:SoundCompass']['Enabled'] is True
    assert target.read_bytes() == contents
    print(json.dumps({'installed': str(target), 'version': manifest['Version'], 'enabled': True,
                      'backup': str(backup), 'sha256': hashlib.sha256(contents).hexdigest()}, indent=2))
finally:
    if lock is not None:
        lock.close()
