"""Generate Limbo block state fixtures from the workspace's pinned official vanilla servers."""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'tools/BlockStateFixture.java'


def digest(path):
    return hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()


def extract(protocol, java, javac):
    vanilla = ROOT / '.narrafork/multiversion' / str(protocol) / 'vanilla'
    provenance = json.loads((vanilla / 'provenance.json').read_text(encoding='utf-8'))
    original = vanilla / 'server.jar'
    original_hash = digest(original)
    if original_hash != provenance['server_sha256']:
        raise ValueError(f'Official server checksum differs for {protocol}')
    server = original
    if protocol <= 774:
        server = vanilla / 'server-named.jar'
        remap = json.loads((vanilla / 'remap-provenance.json').read_text(encoding='utf-8'))
        if (remap['protocol'] != protocol or remap['input-sha256'] != original_hash
                or remap['output-sha256'] != digest(server)):
            raise ValueError(f'Remapped server checksum differs for {protocol}')
    output = vanilla.parent / 'block-state-fixture'
    output.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, [server, *sorted((vanilla / 'libraries').glob('*.jar'))]))
    subprocess.run([javac, '-J-Xmx192m', '-J-XX:ActiveProcessorCount=2', '-cp', cp, '-d', str(output), str(SOURCE)], check=True)
    args = []
    if protocol >= 777:
        def members(tag):
            namespace, name = tag.split(':', 1)
            return {block for entry in json.loads(archive.read(f'data/{namespace}/tags/block/{name}.json'))['values']
                    for block in (members(entry[1:]) if entry.startswith('#') else (entry,))}

        with ZipFile(original) as archive:
            tag_file = output / 'motion-blocking.txt'
            tag_file.write_text('\n'.join(sorted(members('minecraft:blocks_motion_in_heightmap'))), encoding='utf-8')
            args.append(str(tag_file))
    export = subprocess.run([java, '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', os.pathsep.join([str(output), cp]),
                             'BlockStateFixture', *args], cwd=output, check=True, capture_output=True, encoding='utf-8').stdout
    records = export.split('BLOCK_STATES_BEGIN\n', 1)[1].split('BLOCK_STATES_END\n', 1)[0].splitlines()
    if not 1 < len(records) < 65536 or not records[0].startswith('minecraft:air\t'):
        raise ValueError(f'Invalid block state export for {protocol}')
    data = ('\n'.join([f'{protocol}\t{original_hash}', *records]) + '\n').encode('utf-8')
    target = ROOT / 'limbo/protocols' / str(protocol) / 'src/main/resources/limbo' / f'block-states-{protocol}.txt.gz'
    target.write_bytes(gzip.compress(data, mtime=0))
    print(f'protocol={protocol} states={len(records)} bytes={target.stat().st_size}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', nargs='+', type=int, choices=range(767, 778), default=list(range(767, 778)))
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    for protocol in args.protocol:
        extract(protocol, args.java, args.javac)
