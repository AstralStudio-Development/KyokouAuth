"""Run the independent matching official-codec oracle for all eleven engines of one final artifact."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from limbo_artifact_support import (PROTOCOLS, add_netty_argument, artifact_protocols,
                                    resolve_netty_classpath, selected_protocols, sha256)

ROOT=Path(__file__).resolve().parent.parent

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--protocol',type=int,nargs='+',choices=PROTOCOLS,
                        help='Explicit prototype/subset scope; omitted means all eleven engines, never autodetected')
    parser.add_argument('--workers',type=int,default=1)
    parser.add_argument('--jdk',type=Path,default=Path('C:/Program Files/Zulu/zulu-25'))
    add_netty_argument(parser)
    args=parser.parse_args()
    protocols=selected_protocols(parser,args.protocol)
    if args.workers < 1:
        parser.error('--workers must be positive')
    artifact=args.jar.resolve()
    try:
        available=artifact_protocols(artifact)
    except (OSError,ValueError,KeyError,zipfile.BadZipFile) as failure:
        parser.error(str(failure))
    if not set(protocols).issubset(available):
        parser.error(f'artifact missing required engines: {sorted(set(protocols)-available)}; partial prototypes require explicit --protocol')
    netty_cp=resolve_netty_classpath(parser,args.netty_classpath,artifact)
    netty_provenance={path:sha256(path) for path in netty_cp.split(os.pathsep)}
    digest=sha256(artifact)
    output=ROOT/'.narrafork/multiversion'
    output.mkdir(parents=True,exist_ok=True)
    environment=dict(os.environ,PYTHONIOENCODING='utf-8')
    def verify(protocol):
        command=[sys.executable,str(ROOT/'tools/verify-limbo.py'),'--jar',str(artifact),'--protocol',str(protocol),
                 '--jdk',str(args.jdk),'--netty-classpath',netty_cp]
        result=subprocess.run(command,cwd=ROOT,env=environment,capture_output=True,text=True,encoding='utf-8',errors='replace')
        folder=output/str(protocol)/'oracle'
        folder.mkdir(parents=True,exist_ok=True)
        log=result.stdout+result.stderr
        (folder/'matrix.log').write_text(log,encoding='utf-8')
        summary=next((line for line in log.splitlines() if line.startswith('ORACLE SUMMARY ')), 'NO SUMMARY; inspect compile.log/matrix.log')
        print(f'MATRIX protocol={protocol} exit={result.returncode} {summary}',flush=True)
        return {'protocol':protocol,'exit':result.returncode,'summary':summary}
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results=list(pool.map(verify,protocols))
    if sha256(artifact)!=digest or any(sha256(path)!=expected for path,expected in netty_provenance.items()):
        raise RuntimeError('Artifact/provided Netty changed during oracle matrix; rerun against immutable final JARs')
    report={'artifact':str(artifact),'artifact-sha256':digest,'scope':'production-eleven' if args.protocol is None else 'explicit-protocol-subset',
            'provided-netty-sha256':netty_provenance,'protocols':results,'renderer':'NOT_TESTED'}
    report_path=output/(artifact.stem+'-oracle-matrix.json')
    report_path.write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(f'MATRIX SUMMARY passed={sum(x["exit"]==0 for x in results)}/{len(results)} report={report_path.relative_to(ROOT)}')
    return 0 if all(x['exit']==0 for x in results) else 1

if __name__=='__main__':
    raise SystemExit(main())
