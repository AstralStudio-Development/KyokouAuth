"""Fetch pinned primary-source protocol artifacts and official vanilla distributions into .narrafork."""
import argparse
import concurrent.futures
import hashlib
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / '.narrafork' / 'multiversion'
VERSIONS = {
  767: ('1.21.1', '1.21-20241010.155958-24', '1.21-SNAPSHOT'),
  768: ('1.21.3', '1.21.2-20241209.154112-6', '1.21.2-SNAPSHOT'),
  769: ('1.21.4', '1.21.4-20250612.174129-27', '1.21.4-SNAPSHOT'),
  770: ('1.21.5', '1.21.5-1', None),
  771: ('1.21.6', '1.21.6-1', None),
  772: ('1.21.8', '1.21.7-1', None),
  773: ('1.21.10', '1.21.9-20251210.010914-21', '1.21.9-SNAPSHOT'),
  774: ('1.21.11', '1.21.11-1', None),
  775: ('26.1.2', '26.1-1', None),
  776: ('26.2', '26.2', None),
  777: ('26.3', '26.3-4', None),
}
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({'http':'http://127.0.0.1:7890','https':'http://127.0.0.1:7890'}))

def fetch(url, path, expected=None, algorithm='sha1'):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with OPENER.open(urllib.request.Request(url, headers={'User-Agent':'Mozilla/5.0'}), timeout=120) as response:
            data = response.read()
        if expected and hashlib.new(algorithm, data).hexdigest() != expected:
            raise ValueError(f'Checksum mismatch: {url}')
        path.write_bytes(data)
    data = path.read_bytes()
    if expected and hashlib.new(algorithm, data).hexdigest() != expected:
        raise ValueError(f'Cached checksum mismatch: {path}')
    return data

def mcpl(protocol):
    vanilla, fixed, snapshot = VERSIONS[protocol]
    repo = 'maven-snapshots' if snapshot else 'maven-releases'
    base = f'https://repo.opencollab.dev/{repo}/org/geysermc/mcprotocollib/protocol/{snapshot or fixed}/protocol-{fixed}'
    dest = CACHE / str(protocol) / 'mcpl'
    artifacts = []
    for suffix in ('.pom', '.jar', '-sources.jar'):
        url = base + suffix
        path = dest / ('protocol-' + fixed + suffix)
        data = fetch(url, path)
        artifacts.append({'url':url, 'sha256':hashlib.sha256(data).hexdigest(), 'filename':path.name})
        if suffix == '-sources.jar':
            with zipfile.ZipFile(path) as archive:
                archive.extractall(dest / 'sources')
    codec = (dest/'sources/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.java').read_text()
    actual = int(re.search(r'\.protocolVersion\((\d+)\)',codec).group(1))
    if actual != protocol:
        raise ValueError(f'MCPL codec mismatch: {protocol} versus {actual}')
    ns = {'m':'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(dest/('protocol-'+fixed+'.pom')).getroot()
    licenses = [e.findtext('m:name',namespaces=ns) for e in pom.findall('m:licenses/m:license',ns)]
    if 'The MIT License' not in licenses:
        raise ValueError('Unexpected MCProtocolLib license')
    dependencies = [{key:e.findtext('m:'+key,namespaces=ns) for key in ('groupId','artifactId','version','scope')} for e in pom.findall('m:dependencies/m:dependency',ns)]
    record = {'protocol':protocol,'fixed':fixed,'maven-directory':snapshot or fixed,'pom-version':pom.findtext('m:version',namespaces=ns),
        'unique-snapshot':bool(snapshot),'licenses':licenses,'dependencies':dependencies,'artifacts':artifacts,
        'dependency-overrides':({'adventure-text-serializer-gson':'4.25.0','adventure-text-serializer-json-legacy-impl':'4.25.0'} if protocol in (770,771,772) else {})}
    content = json.dumps(record,indent=2)+'\n'
    (dest/'provenance.json').write_text(content,encoding='utf-8')
    delivery = ROOT/f'limbo-protocol-{protocol}/src/main/resources/limbo'
    delivery.mkdir(parents=True,exist_ok=True)
    (delivery/f'mcpl-{protocol}.json').write_text(content,encoding='utf-8')
    print(f'PINNED protocol={protocol} version={fixed} license=MIT codec-verified=true',flush=True)

def vanilla(protocol, manifest):
    version = VERSIONS[protocol][0]
    entry = next(x for x in manifest['versions'] if x['id'] == version)
    dest = CACHE / str(protocol) / 'vanilla'
    metadata = json.loads(fetch(entry['url'], dest / 'version.json', entry['sha1']))
    files = {}
    for kind in ('server','server_mappings'):
        if kind not in metadata['downloads']:
            continue
        info = metadata['downloads'][kind]
        path = dest / ('server-bundler.jar' if kind == 'server' else 'server-mappings.txt')
        data = fetch(info['url'], path, info['sha1'])
        files[kind] = {'url':info['url'],'sha1':info['sha1'],'sha256':hashlib.sha256(data).hexdigest()}
    with zipfile.ZipFile(dest / 'server-bundler.jar') as archive:
        version_entry = archive.read('META-INF/versions.list').decode().strip().split('\t')
        name = version_entry[2]
        (dest / 'server.jar').write_bytes(archive.read('META-INF/versions/' + name))
        libs = dest / 'libraries'
        libs.mkdir(exist_ok=True)
        for line in archive.read('META-INF/libraries.list').decode().splitlines():
            sha, coordinate, relative = line.split('\t')
            data = archive.read('META-INF/libraries/' + relative)
            if hashlib.sha256(data).hexdigest() != sha:
                raise ValueError('Vanilla bundled library checksum differs')
            (libs / Path(relative).name).write_bytes(data)
    with zipfile.ZipFile(dest / 'server.jar') as archive:
        actual = json.loads(archive.read('version.json'))
        if actual['protocol_version'] != protocol:
            raise ValueError(f'Official protocol mismatch: {actual}')
    (dest / 'provenance.json').write_text(json.dumps({'version':version,'protocol':protocol,'downloads':files,'server_sha256':hashlib.sha256((dest/'server.jar').read_bytes()).hexdigest()},indent=2),encoding='utf-8')
    print(f'OFFICIAL protocol={protocol} version={version} mappings={"server_mappings" in files}',flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=int,nargs='*',default=list(VERSIONS))
    parser.add_argument('--mcpl-only',action='store_true')
    args = parser.parse_args()
    CACHE.mkdir(parents=True,exist_ok=True)
    manifest = None if args.mcpl_only else json.loads(fetch('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',CACHE/'version-manifest.json'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        jobs = [pool.submit(mcpl,p) for p in args.protocol]
        if manifest:
            jobs += [pool.submit(vanilla,p,manifest) for p in args.protocol]
        for job in jobs:
            job.result()
