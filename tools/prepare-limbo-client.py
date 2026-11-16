"""Prepare a credential-free vanilla client; installed Minecraft is read-only, all downloads stay in .narrafork."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import urllib.request

ROOT=Path(__file__).resolve().parent.parent
CACHE=ROOT/'.narrafork/multiversion'
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({'https':'http://127.0.0.1:7890','http':'http://127.0.0.1:7890'}))

def checked(path,sha1):
    return path.is_file() and (not sha1 or hashlib.sha1(path.read_bytes()).hexdigest()==sha1)

def acquire(info,dest,installed=None):
    if installed and checked(installed,info.get('sha1')):
        return installed
    dest.parent.mkdir(parents=True,exist_ok=True)
    if not checked(dest,info.get('sha1')):
        for attempt in range(3):
            try:
                with OPENER.open(urllib.request.Request(info['url'],headers={'User-Agent':'Mozilla/5.0'}),timeout=30) as response:
                    data=response.read()
                break
            except (OSError, urllib.error.URLError) as failure:
                if attempt == 2: raise RuntimeError('Official download failed: '+info['url']) from failure
        if info.get('sha1') and hashlib.sha1(data).hexdigest()!=info['sha1']:
            raise ValueError('Official client checksum mismatch: '+info['url'])
        dest.write_bytes(data)
    return dest

def allowed(rules):
    result=not rules
    for rule in rules:
        system=rule.get('os',{})
        if system.get('name','windows')!='windows' or system.get('arch','amd64') not in ('amd64','x86_64'):
            continue
        if rule.get('features'):
            continue
        result=rule['action']=='allow'
    return result

def prepare(version,minecraft):
    folder=CACHE/'clients'/version
    folder.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((CACHE/'version-manifest.json').read_text())
    entry=next(x for x in manifest['versions'] if x['id']==version)
    path=acquire(entry,folder/'version.json',minecraft/f'versions/{version}/{version}.json')
    metadata=json.loads(path.read_text(encoding='utf-8'))
    client=acquire(metadata['downloads']['client'],folder/'client.jar',minecraft/f'versions/{version}/{version}.jar')
    libraries=[]
    jobs=[]
    for library in metadata['libraries']:
        if not allowed(library.get('rules',[])):
            continue
        info=library.get('downloads',{}).get('artifact')
        if info:
            jobs.append((info,folder/'libraries'/info['path'],minecraft/'libraries'/info['path']))
    print(f'CLIENT LIBRARIES version={version} count={len(jobs)}',flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        libraries=list(pool.map(lambda job:acquire(*job),jobs))
    index_info=metadata['assetIndex']
    index_path=acquire(index_info,folder/f'assets/indexes/{index_info["id"]}.json',minecraft/f'assets/indexes/{index_info["id"]}.json')
    index=json.loads(index_path.read_text())
    objects=index['objects']
    print(f'CLIENT ASSETS version={version} objects={len(objects)}',flush=True)
    installed_assets=minecraft/'assets'
    all_installed=all((installed_assets/'objects'/info['hash'][:2]/info['hash']).is_file() for info in objects.values())
    assets=installed_assets if all_installed else folder/'assets'
    if not all_installed:
        (assets/'indexes').mkdir(parents=True,exist_ok=True)
        target=assets/'indexes'/f'{index_info["id"]}.json'
        if index_path!=target:
            shutil.copyfile(index_path,target)
        def asset(info):
            digest=info['hash']
            dest=assets/'objects'/digest[:2]/digest
            source=acquire({'url':f'https://resources.download.minecraft.net/{digest[:2]}/{digest}','sha1':digest},dest,installed_assets/'objects'/digest[:2]/digest)
            if source!=dest:
                dest.parent.mkdir(parents=True,exist_ok=True)
                if not dest.exists(): shutil.copyfile(source,dest)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(asset,objects.values()))
    names={'client.class':'net.minecraft.client.Minecraft','client.instance':'getInstance','client.screen':'screen','client.level':'level','client.player':'player'}
    mapping_info=metadata['downloads'].get('client_mappings')
    if mapping_info:
        mapping=acquire(mapping_info,folder/'client-mappings.txt')
        content=mapping.read_text(encoding='utf-8')
        block=re.search(r'^net\.minecraft\.client\.Minecraft -> ([^:]+):\n(.*?)(?=^[^\s#][^\n]+ -> [^:\n]+:$|\Z)',content,re.M|re.S)
        if not block:
            raise RuntimeError('Official mapping omits client observer class')
        names['client.class']=block[1]
        for name,key in [('screen','client.screen'),('level','client.level'),('player','client.player')]:
            field=re.search(r'^    \S+ '+name+r' -> (\S+)$',block[2],re.M)
            if not field: raise RuntimeError('Official mapping omits observer field '+name)
            names[key]=field[1]
        method=re.search(r'^    (?:\d+:\d+:)?net\.minecraft\.client\.Minecraft getInstance\(\)(?::\d+(?::\d+)?)? -> (\S+)$',block[2],re.M)
        if not method: raise RuntimeError('Official mapping omits observer getInstance')
        names['client.instance']=method[1]
    if version == '26.3':
        names['client.screen']='gui.screen'  # Official 26.3 moved screen ownership into Minecraft.gui.
    observer=folder/'observer.properties'
    observer.write_text(''.join(f'{key}={value}\n' for key,value in names.items()),encoding='utf-8')
    result={'version':version,'metadata':str(path),'client':str(client),'libraries':list(map(str,libraries)),
        'assets':str(assets),'observer':str(observer),'assetIndex':index_info['id'],
        'client-sha256':hashlib.sha256(client.read_bytes()).hexdigest()}
    (folder/'launch.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(f'CLIENT PREPARED version={version} libraries={len(libraries)} assets={len(objects)} workspace-only-downloads=true',flush=True)
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',required=True)
    parser.add_argument('--minecraft',type=Path,default=Path(os.environ['APPDATA'])/'.minecraft')
    args=parser.parse_args()
    prepare(args.version,args.minecraft)
