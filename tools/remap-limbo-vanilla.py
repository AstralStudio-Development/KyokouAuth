"""Apply official mappings to old vanilla jars for independent named-codec verification only."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT/'.narrafork/multiversion'
JAVA = Path('C:/Program Files/Zulu/zulu-25/bin/java.exe')
TOOL = CACHE/'AutoRenamingTool-2.0.18-all.jar'
URL = 'https://maven.neoforged.net/releases/net/neoforged/AutoRenamingTool/2.0.18/AutoRenamingTool-2.0.18-all.jar'

def remap(protocol):
    folder = CACHE/str(protocol)/'vanilla'
    mappings = folder/'server-mappings.txt'
    if not mappings.exists():
        return
    output = folder/'server-named.jar'
    if not output.exists():
        (folder/'tmp').mkdir(exist_ok=True)
        command = [str(JAVA),'-Djava.io.tmpdir='+str(folder/'tmp'),'-Xmx1G','-jar',str(TOOL),'--input',str(folder/'server.jar'),
            '--output',str(output),'--map',str(mappings),'--reverse','--threads','2','--strip-sigs']
        for library in (folder/'libraries').glob('*.jar'):
            command += ['--lib',str(library)]
        with (folder/'remap.log').open('w',encoding='utf-8') as log:
            subprocess.run(command,cwd=folder,stdout=log,stderr=subprocess.STDOUT,check=True)
    metadata = {'tool-url':URL,'tool-sha256':hashlib.sha256(TOOL.read_bytes()).hexdigest(),
        'input-sha256':hashlib.sha256((folder/'server.jar').read_bytes()).hexdigest(),
        'official-mappings-sha256':hashlib.sha256(mappings.read_bytes()).hexdigest(),
        'output-sha256':hashlib.sha256(output.read_bytes()).hexdigest(),'protocol':protocol}
    (folder/'remap-provenance.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    print(f'MAPPED protocol={protocol} official-mappings=true output-sha256={metadata["output-sha256"]}',flush=True)

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol',type=int,nargs='*',default=list(range(767,775)))
    args=parser.parse_args()
    if not TOOL.exists():
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({'https':'http://127.0.0.1:7890'}))
        with opener.open(URL,timeout=60) as response:
            TOOL.write_bytes(response.read())
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for _ in pool.map(remap,args.protocol):
            pass
