"""Capture full configuration directly from each SHA-verified official offline server.
Only this research tool handles minimal socket framing; production packets use MCProtocolLib.
All servers, worlds, logs, downloaded artifacts and transient files stay under .narrafork.
"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import io
import json
import re
import socket
import struct
import subprocess
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / '.narrafork/multiversion'
JAVA = Path('C:/Program Files/Zulu/zulu-25/bin/java.exe')

def varint(value):
    out = bytearray()
    while True:
        b = value & 127
        value >>= 7
        out.append(b | (128 if value else 0))
        if not value:
            return bytes(out)

def read_exact(stream, n):
    out = bytearray()
    while len(out) < n:
        data = stream.read(n-len(out)) if hasattr(stream,'read') else stream.recv(n-len(out))
        if not data:
            raise EOFError('Connection closed during configuration')
        out += data
    return bytes(out)

def read_varint(stream):
    value = 0
    for shift in range(0,35,7):
        b = read_exact(stream,1)[0]
        value |= (b & 127) << shift
        if not b & 128:
            return value
    raise ValueError('Oversized varint')

def string(value):
    data = value.encode()
    return varint(len(data))+data

def read_string(stream):
    return read_exact(stream,read_varint(stream)).decode()

def packet(sock, packet_id, data=b''):
    body = varint(packet_id)+data
    sock.sendall(varint(len(body))+body)

def packet_ids(protocol, state, direction):
    source = (CACHE/str(protocol)/'mcpl/sources/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.java').read_text()
    block = source.split(f'.state(ProtocolState.{state},',1)[1].split(').state(',1)[0]
    return {name: index for index,name in enumerate(re.findall(r'\.register'+direction+r'Packet\((\w+)\.class',block))}

def extract(protocol):
    official = CACHE/str(protocol)/'vanilla'
    provenance = json.loads((official/'provenance.json').read_text())
    work = CACHE/str(protocol)/'fixture-server'
    work.mkdir(parents=True,exist_ok=True)
    with socket.socket() as probe:
        probe.bind(('127.0.0.1',0))
        port = probe.getsockname()[1]
    (work/'eula.txt').write_text('eula=true\n')
    (work/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nnetwork-compression-threshold=-1\nview-distance=2\nsimulation-distance=2\nmax-players=2\nspawn-protection=0\ngenerate-structures=false\nlevel-type=minecraft:flat\n')
    log = (work/'capture-server.log').open('w',encoding='utf-8')
    (work/'tmp').mkdir(exist_ok=True)
    process = subprocess.Popen([str(JAVA),'-Djava.io.tmpdir='+str(work/'tmp'),'-Xms128M','-Xmx768M','-XX:ActiveProcessorCount=2','-jar',str(official/'server-bundler.jar'),'nogui'],cwd=work,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
    ready = threading.Event()
    def consume():
        for line in process.stdout:
            log.write(line)
            log.flush()
            if 'Done (' in line:
                ready.set()
    reader = threading.Thread(target=consume,daemon=True)
    reader.start()
    try:
        if not ready.wait(180):
            raise RuntimeError(f'Official server {protocol} did not start; see {work}/capture-server.log')
        cids = packet_ids(protocol,'CONFIGURATION','Clientbound')
        sids = packet_ids(protocol,'CONFIGURATION','Serverbound')
        login = packet_ids(protocol,'LOGIN','Serverbound')
        login_client = packet_ids(protocol,'LOGIN','Clientbound')
        frames = []
        registries = 0
        entries = 0
        tag_count = 0
        tag_registries = 0
        with socket.create_connection(('127.0.0.1',port),timeout=30) as sock:
            packet(sock,0,varint(protocol)+string('localhost')+struct.pack('>H',port)+varint(2))
            packet(sock,login['ServerboundHelloPacket'],string('FixtureProbe')+uuid.UUID(int=1).bytes)
            body = io.BytesIO(read_exact(sock,read_varint(sock)))
            pid = read_varint(body)
            if pid != login_client.get('ClientboundLoginFinishedPacket',login_client.get('ClientboundGameProfilePacket')):
                raise RuntimeError(f'Unexpected official login packet: {protocol}/{pid}')
            packet(sock,login['ServerboundLoginAcknowledgedPacket'])
            while True:
                raw = read_exact(sock,read_varint(sock))
                body = io.BytesIO(raw)
                pid = read_varint(body)
                if pid == cids['ClientboundSelectKnownPacks']:
                    packet(sock,sids['ServerboundSelectKnownPacks'],varint(0))
                elif pid in [cids[x] for x in ('ClientboundUpdateEnabledFeaturesPacket','ClientboundRegistryDataPacket','ClientboundUpdateTagsPacket')]:
                    frames.append(varint(len(raw))+raw)
                    if pid == cids['ClientboundRegistryDataPacket']:
                        key = read_string(body)
                        count = read_varint(body)
                        registries += 1
                        entries += count
                    elif pid == cids['ClientboundUpdateTagsPacket']:
                        tag_registries = read_varint(body)
                        for _ in range(tag_registries):
                            read_string(body)
                            count = read_varint(body)
                            tag_count += count
                            for _ in range(count):
                                read_string(body)
                                for _ in range(read_varint(body)):
                                    read_varint(body)
                elif pid == cids['ClientboundFinishConfigurationPacket']:
                    break
                elif pid == cids.get('ClientboundKeepAlivePacket'):
                    packet(sock,sids['ServerboundKeepAlivePacket'],body.read())
        fixture = b''.join(frames)
        dest = ROOT/'limbo'/'protocols'/str(protocol)/'src/main/resources/limbo'
        dest.mkdir(parents=True,exist_ok=True)
        (dest/f'configuration-{protocol}.bin').write_bytes(fixture)
        metadata = {
            'protocol':protocol,'version':provenance['version'],
            'source':'official-offline-server-empty-known-packs',
            'server-bundler-url':provenance['downloads']['server']['url'],
            'server-bundler-sha1':provenance['downloads']['server']['sha1'],
            'server-bundler-sha256':provenance['downloads']['server']['sha256'],
            'server-sha256':provenance['server_sha256'],
            'sha256':hashlib.sha256(fixture).hexdigest(),
            'registries':registries,'entries':entries,'tag-registries':tag_registries,'tags':tag_count,
            'known-packs':'empty-response-full-registry-nbt',
        }
        (dest/f'configuration-{protocol}.properties').write_text(''.join(f'{k}={v}\n' for k,v in metadata.items()),encoding='utf-8')
        print(f'FIXTURE protocol={protocol} registries={registries} entries={entries} tags={tag_count} sha256={metadata["sha256"]}',flush=True)
    finally:
        try:
            process.stdin.write('stop\n')
            process.stdin.flush()
            process.wait(timeout=30)
        except (OSError,subprocess.TimeoutExpired):
            process.kill()
            process.wait()
        reader.join(timeout=5)
        log.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=int,nargs='*',default=list(range(767,778)))
    parser.add_argument('--workers',type=int,default=2)
    args = parser.parse_args()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        for result in pool.map(extract,args.protocol):
            pass
