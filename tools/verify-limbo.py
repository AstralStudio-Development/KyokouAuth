"""Compile/run the independent vanilla 26.3 oracle against raw exported packets.

Usage:
  python tools/verify-limbo.py --jar platform-velocity/build/libs/KyokouAuth-Velocity-0.1.1.jar
  python tools/verify-limbo.py <capture-directory>/manifest.tsv

No server is started; dependencies are read-only. Compilation and vanilla logs remain
under .narrafork/multiversion/<protocol>/oracle. Exit 0 means packet/semantic checks passed,
NOT that a Minecraft client rendered the world. Exit 1 preserves a protocol failure.
"""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import zipfile

from limbo_artifact_support import add_netty_argument, artifact_protocols, resolve_netty_classpath, sha256


def main():
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, nargs="?")
    parser.add_argument("--jar", type=Path, help="Export and verify packets from the actual isolated/plugin artifact")
    parser.add_argument("--protocol", type=int, default=777, choices=range(767, 778))
    parser.add_argument("--vanilla-dir", type=Path, default=None)
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    add_netty_argument(parser)
    args = parser.parse_args()
    workspace = Path(__file__).resolve().parent.parent
    output = workspace / ".narrafork/multiversion" / str(args.protocol) / "oracle"
    classes = output / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    if bool(args.manifest) == bool(args.jar):
        parser.error("provide either manifest.tsv or --jar artifact.jar")
    manifest = args.manifest.resolve() if args.manifest else output / "artifact-packets/manifest.tsv"
    vanilla_dir = args.vanilla_dir.resolve() if args.vanilla_dir else workspace / ".narrafork/multiversion" / str(args.protocol) / "vanilla"
    server = vanilla_dir / ("server-named.jar" if args.protocol <= 774 else "server.jar")
    libraries = server.parent / "libraries"
    extension = ".exe" if os.name == "nt" else ""
    javac = args.jdk / "bin" / ("javac" + extension)
    java = args.jdk / "bin" / ("java" + extension)
    for required in ((args.jar.resolve(),) if args.jar else (manifest,)) + (server, libraries, javac, java):
        if not required.exists():
            parser.error(f"required path does not exist: {required}")
    artifact_sha = None
    netty_provenance = {}
    if args.jar:
        try:
            available = artifact_protocols(args.jar)
        except (OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
            parser.error(str(failure))
        if args.protocol not in available:
            parser.error(f"artifact has no GeneratedCodec/engine for protocol {args.protocol}")
        netty_cp = resolve_netty_classpath(parser, args.netty_classpath, args.jar)
        artifact_sha = sha256(args.jar)
        netty_provenance = {path: sha256(path) for path in netty_cp.split(os.pathsep)}
        subprocess.run([str(javac), "-encoding", "UTF-8", "-d", str(classes),
                        str(workspace / "tools/NewExporter.java")], cwd=output, check=True)
        subprocess.run([str(java), "-Xmx256M", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=" + str(output), "-cp", str(classes), "NewExporter", str(args.jar.resolve()),
                        str(manifest.parent), str(args.protocol), "--netty-classpath", netty_cp], cwd=output, check=True)
    dependency_cp = os.pathsep.join((str(server), str(libraries / "*")))
    source = workspace / "tools/ProtocolOracle.java"
    if args.protocol != 777:
        source = output / "ProtocolOracle.java"
        subprocess.run([sys.executable, str(workspace / "tools/prepare-limbo-oracle.py"), str(args.protocol), str(source)], check=True)
    compile_result = subprocess.run(
        [str(javac), "-J-Duser.language=en", "-encoding", "UTF-8", "-cp", dependency_cp, "-d", str(classes), str(source)],
        cwd=output, check=False, text=True, encoding="utf-8", errors="replace", capture_output=True)
    (output / "compile.log").write_text(compile_result.stdout + compile_result.stderr, encoding="utf-8")
    if compile_result.returncode:
        print(compile_result.stdout + compile_result.stderr, end="")
        return compile_result.returncode
    digest = hashlib.sha256(manifest.read_bytes())
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            parser.error("manifest requires state, decimal packet id, relative payload filename")
        payload = (manifest.parent / fields[2]).resolve()
        if not payload.is_relative_to(manifest.parent):
            parser.error("manifest payload escapes capture directory")
        digest.update(payload.read_bytes())
    with server.open("rb") as server_file:
        server_sha = hashlib.file_digest(server_file, "sha256").hexdigest()
    print(f"ORACLE vanilla-server-sha256={server_sha}", flush=True)
    print(f"ORACLE capture-sha256={digest.hexdigest()}", flush=True)
    result = subprocess.run(
        [str(java), "-Djava.io.tmpdir=" + str(output), "-Dfile.encoding=UTF-8", "-XX:ActiveProcessorCount=2", "-Xmx512M", "-cp",
         os.pathsep.join((str(classes), dependency_cp)), "ProtocolOracle", str(manifest)],
        cwd=output, check=False, text=True, encoding="utf-8", errors="replace", capture_output=True)
    (output / "oracle.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if artifact_sha is not None and (sha256(args.jar) != artifact_sha or any(sha256(path) != expected for path, expected in netty_provenance.items())):
        raise RuntimeError("Artifact/provided Netty changed during export/oracle; rerun against immutable final JARs")
    # Only emit the oracle's controlled summaries, never vanilla stack messages/NBT/chat dumps.
    lines = []
    for line in result.stdout.splitlines():
        marker = line.find("ORACLE ")
        if marker >= 0:
            lines.append(line[marker:])
    print("\n".join(lines), flush=True)
    if not any(line.startswith("ORACLE SUMMARY ") for line in lines):
        print(f"ORACLE ERROR no summary (java-exit={result.returncode}; raw logs intentionally suppressed)")
        return result.returncode or 2
    return result.returncode


if __name__ == "__main__":
    sys.exit(main())
