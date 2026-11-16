"""Start private endpoints from one final trimmed artifact, with explicitly provided proxy Netty.

No classes directory, Gradle dependency cache, original MCPL SDK or old full artifact is a fallback.
Omitting --protocol requires all eleven engines and exercises the public startAll() facade.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess
import zipfile

from limbo_artifact_support import (PROTOCOLS, ROOT, add_netty_argument, artifact_protocols,
                                    resolve_netty_classpath, selected_protocols, sha256)


LINKAGE_FAILURE = re.compile(
    r'Failed to initialize a channel|Exception in thread|\b(?:LinkageError|IncompatibleClassChangeError|'
    r'NoClassDefFoundError|ClassNotFoundException|ExceptionInInitializerError|BootstrapMethodError|'
    r'AbstractMethodError|NoSuchMethodError|NoSuchFieldError|IllegalAccessError|VerifyError|'
    r'UnsupportedClassVersionError|ClassFormatError|UnsatisfiedLinkError)\b', re.IGNORECASE)


def assembly_verdict(returncode, stdout, stderr, protocols):
    """A JVM exit 0/closed-port summary cannot hide asynchronous Netty initialization failure."""
    failures = list(dict.fromkeys(match.group(0) for match in LINKAGE_FAILURE.finditer(stdout + '\n' + stderr)))
    if returncode:
        failures.insert(0, f'java-exit={returncode}')
    passes = [int(match[1]) for match in re.finditer(r'^ASSEMBLY PASS protocol=(\d+) private-loopback=true$', stdout, re.MULTILINE)]
    summaries = re.findall(r'^ASSEMBLY SUMMARY engines=(\d+) [^\n]*\ball-closed=true\b[^\n]*$', stdout, re.MULTILINE)
    if len(passes) != len(protocols) or set(passes) != set(protocols):
        failures.append('missing/duplicate/unexpected protocol endpoint results')
    if len(summaries) != 1 or int(summaries[0]) != len(protocols):
        failures.append('missing/invalid closed-endpoint summary')
    return returncode or (2 if failures else 0), failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=int, nargs='+', choices=PROTOCOLS,
                        help='Explicit prototype/subset scope, e.g. --protocol 767 770 777')
    parser.add_argument('--jar', type=Path, required=True, help='The immutable final aggregate/plugin JAR')
    parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Zulu/zulu-25'))
    add_netty_argument(parser)
    args = parser.parse_args()
    protocols = selected_protocols(parser, args.protocol)
    artifact = args.jar.resolve()
    try:
        available = artifact_protocols(artifact)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        parser.error(str(failure))
    if not set(protocols).issubset(available):
        parser.error(f'artifact missing required engines: {sorted(set(protocols) - available)}; partial prototypes require explicit --protocol')
    netty_cp = resolve_netty_classpath(parser, args.netty_classpath, artifact)
    digest = sha256(artifact)
    provided = {path: sha256(path) for path in netty_cp.split(os.pathsep)}
    output = ROOT / '.narrafork/multiversion/assembly' / artifact.stem
    output.mkdir(parents=True, exist_ok=True)
    extension = '.exe' if os.name == 'nt' else ''
    javac = args.jdk / 'bin' / ('javac' + extension)
    java = args.jdk / 'bin' / ('java' + extension)
    for tool in (javac, java):
        if not tool.is_file():
            parser.error(f'required JDK executable is missing: {tool}')
    subprocess.run([str(javac), '-encoding', 'UTF-8', '-d', str(output), str(ROOT / 'tools/NewExporter.java'),
                    str(ROOT / 'tools/LimboAssemblySmoke.java')], cwd=output, check=True)
    command = [str(java), '-Djava.io.tmpdir=' + str(output), '-Dfile.encoding=UTF-8', '-XX:ActiveProcessorCount=2',
               '-Xmx512M', '-cp', str(output), 'LimboAssemblySmoke', str(artifact), '--netty-classpath', netty_cp]
    if args.protocol is not None:
        command += ['--protocol', *map(str, protocols)]
    result = subprocess.run(command, cwd=output, check=False, capture_output=True, text=True, encoding='utf-8', errors='replace')
    log = output / 'assembly.log'
    log.write_text('--- STDOUT ---\n' + result.stdout + '\n--- STDERR ---\n' + result.stderr, encoding='utf-8')
    if sha256(artifact) != digest or any(sha256(path) != expected for path, expected in provided.items()):
        raise RuntimeError('Artifact/provided Netty changed during startup probe; rerun against immutable final JARs')
    code, failures = assembly_verdict(result.returncode, result.stdout, result.stderr, protocols)
    if failures:
        # Preserve the raw Java summary only in the diagnostic log; never print a false PASS to callers.
        print(f'ASSEMBLY FAIL diagnostics={"; ".join(failures)} log={log}', flush=True)
    else:
        print('\n'.join(line for line in result.stdout.splitlines() if line.startswith('ASSEMBLY ')), flush=True)
    print(f'ASSEMBLY ARTIFACT status={"FAIL" if code else "PASS"} sha256={digest} renderer=NOT_TESTED', flush=True)
    return code


if __name__ == '__main__':
    raise SystemExit(main())
