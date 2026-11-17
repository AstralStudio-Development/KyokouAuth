"""Export numeric selector IDs from SHA-pinned, official vanilla registries; no server/socket is started.

Runs one JVM at a time. Only generated classes/logs live under .narrafork; fixture properties live in limbo.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / '.narrafork/multiversion'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parse_fixture_output(text):
    expected = {'minecraft:player_head', 'minecraft:generic_9x3', 'item-key', 'menu-key'}
    values = {}
    for line in text.splitlines():
        marker = 'ROLE_FIXTURE '
        if marker not in line:
            continue
        if line.count(marker) != 1:
            raise ValueError('Ambiguous official registry output')
        key, separator, value = line.split(marker, 1)[1].partition('=')
        if not separator or key not in expected or key in values or not value:
            raise ValueError(f'Invalid or duplicate official registry field: {key}')
        values[key] = value
    if values.keys() != expected:
        raise ValueError(f'Incomplete official registry output: {sorted(values)}')
    for key in ('minecraft:player_head', 'minecraft:generic_9x3'):
        if not values[key].isascii() or not values[key].isdigit():
            raise ValueError(f'Invalid official registry numeric ID: {key}')
    return values


def extract(protocol, java, javac):
    vanilla = CACHE / str(protocol) / 'vanilla'
    provenance = json.loads((vanilla / 'provenance.json').read_text(encoding='utf-8'))
    original = vanilla / 'server.jar'
    if sha(original) != provenance['server_sha256']:
        raise ValueError(f'Official server checksum differs for {protocol}')
    server = original if protocol >= 775 else vanilla / 'server-named.jar'
    if protocol <= 774:
        remap = json.loads((vanilla / 'remap-provenance.json').read_text(encoding='utf-8'))
        if (remap.get('protocol') != protocol or remap.get('input-sha256') != sha(original)
                or remap.get('output-sha256') != sha(server)):
            raise ValueError(f'Official mapped server provenance differs for {protocol}')
    output = CACHE / str(protocol) / 'role-selector-fixture'
    output.mkdir(parents=True, exist_ok=True)
    libraries = sorted((vanilla / 'libraries').glob('*.jar'))
    cp = os.pathsep.join(map(str, [server, *libraries]))
    source = ROOT / 'tools/RoleSelectorRegistryFixture.java'
    compile_result = subprocess.run([javac, '-J-Xmx192m', '-J-XX:ActiveProcessorCount=2', '-encoding', 'UTF-8',
                                     '-cp', cp, '-d', str(output), str(source)], capture_output=True, text=True, encoding='utf-8')
    (output / 'compile.log').write_text(compile_result.stdout + compile_result.stderr, encoding='utf-8')
    compile_result.check_returncode()
    result = subprocess.run([java, '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', os.pathsep.join([str(output), cp]),
                             'RoleSelectorRegistryFixture'], cwd=output, capture_output=True, text=True, encoding='utf-8')
    (output / 'export.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    result.check_returncode()
    values = parse_fixture_output(result.stdout)
    if values.get('item-key') != 'minecraft:player_head' or values.get('menu-key') != 'minecraft:generic_9x3':
        raise ValueError(f'Official registry key mismatch: {values}')
    properties = {
        'protocol': protocol,
        'version': provenance['version'],
        'source': 'official-server-bootstrap-built-in-registries',
        'server-sha256': sha(original),
        'registry-server-sha256': sha(server),
        'exporter-sha256': sha(source),
        'player-head-id': int(values['minecraft:player_head']),
        'generic-9x3-id': int(values['minecraft:generic_9x3']),
    }
    target = ROOT / 'limbo/protocols' / str(protocol) / 'src/main/resources/limbo' / f'role-selector-{protocol}.properties'
    target.write_text(''.join(f'{key}={value}\n' for key, value in properties.items()), encoding='utf-8')
    print(f'ROLE_FIXTURE protocol={protocol} head={properties["player-head-id"]} menu={properties["generic-9x3-id"]}', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=int, nargs='+', choices=range(767, 778), default=list(range(767, 778)))
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    args = parser.parse_args()
    for protocol in args.protocol:
        extract(protocol, args.java, args.javac)
