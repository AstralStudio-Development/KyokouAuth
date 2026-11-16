"""Member-shrink the private Limbo graph into Java classfiles, keeping all runtime resources.

The core and platform adapters are not input. All intermediates stay in the selected build directory.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


# These belong solely to the removed MCPL default server listener, not our versioned snapshots.
REMOVED_DEFAULT_RESOURCES = frozenset(("networkCodec.nbt", "networkTags.nbt"))


def verify_resource_pruning(class_name, data):
    for name in REMOVED_DEFAULT_RESOURCES:
        if name.encode("ascii") in data:
            raise RuntimeError(f"Cannot prune still-referenced default resource {name}: {class_name}")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def argument(value):
    return '"' + str(value).replace("\\", "/").replace('"', '\\"') + '"'


def relocated_rules(path):
    content = path.read_text(encoding="utf-8")
    if path.name != "keep-rules.pro":
        return content
    manifests = sorted((path.parent / "resources/limbo").glob("codec-*.json"))
    if len(manifests) != 1:
        raise ValueError(f"Generated keep rules require exactly one adjacent codec manifest: {path}")
    metadata = json.loads(manifests[0].read_text(encoding="utf-8"))
    protocol = metadata.get("protocol")
    if type(protocol) is not int or protocol not in range(767, 778) or manifests[0].name != f"codec-{protocol}.json":
        raise ValueError(f"Invalid protocol identity in generated codec manifest: {manifests[0]}")
    family = "legacy" if protocol <= 769 else "json" if protocol <= 775 else "nbt"
    base = f"moe.ryuu.kyokouauth.limbo.engine.p{protocol}"
    content = content.replace("moe.ryuu.kyokouauth.limbo.session", base + ".session")
    content = content.replace("org.geysermc", base + ".libs.org.geysermc")
    for namespace in ("org.cloudburstmc", "it.unimi.dsi.fastutil", "com.nukkitx", "com.google.gson", "org.slf4j"):
        content = content.replace(namespace, "moe.ryuu.kyokouauth.limbo.shared." + namespace)
    return content.replace("net.kyori", f"moe.ryuu.kyokouauth.limbo.text.{family}.net.kyori")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--r8", type=Path, required=True)
    parser.add_argument("--r8-sha256", required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--rules", type=Path, action="append", default=[])
    parser.add_argument("--library", type=Path, action="append", default=[])
    parser.add_argument("--bundle-library", type=Path, action="append", default=[])
    args = parser.parse_args()
    expected = args.r8_sha256.lower()
    if not re.fullmatch("[0-9a-f]{64}", expected) or sha256(args.r8) != expected:
        raise RuntimeError("Pinned R8 tool checksum differs")
    work = args.output.resolve().parent.parent / "shrink" / args.output.stem
    work.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    merged = work / "keep.pro"
    services = []
    with zipfile.ZipFile(args.input) as source:
        for name in source.namelist():
            if name.startswith("META-INF/services/") and not name.endswith("/"):
                for line in source.read(name).decode("utf-8").splitlines():
                    provider = line.split("#", 1)[0].strip()
                    if provider:
                        if not re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", provider):
                            raise RuntimeError("Invalid service provider name")
                        services.append(provider)
    content = "\n".join(relocated_rules(path) for path in args.rules)
    content += "\n# Provider entry points are enumerated from the actual input archive.\n"
    content += "\n".join(f"-keep class {provider} {{ *; }}" for provider in sorted(set(services)))
    content += "\n-printusage " + argument(work / "removed.txt") + "\n"
    merged.write_text(content, encoding="utf-8")
    classes = work / "classes.jar"
    classes.unlink(missing_ok=True)
    r8_args = ["--release", "--classfile", "--no-minification", "--no-desugaring", "--pg-conf", merged,
               "--lib", args.java_home, "--output", classes]
    for library in dict.fromkeys(path.resolve() for path in args.library):
        r8_args += ["--lib", library]
    r8_args.append(args.input.resolve())
    launch = ["-Xmx1536m", "-XX:ActiveProcessorCount=2", f"-Djava.io.tmpdir={work}", "-cp", args.r8.resolve(),
              "com.android.tools.r8.R8", *r8_args]
    response = work / "java-arguments.txt"
    response.write_text("\n".join(argument(value) for value in launch), encoding="utf-8")
    java = args.java_home / "bin" / ("java.exe" if (args.java_home / "bin/java.exe").exists() else "java")
    log = work / "r8.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run([str(java), "@" + str(response)], cwd=work, stdout=output, stderr=subprocess.STDOUT, check=False)
    if result.returncode:
        diagnostics = log.read_text(encoding="utf-8", errors="replace").splitlines()
        print("\n".join(diagnostics[:60]))
        print(f"SHRINK FAILED full-diagnostics={log}")
        raise SystemExit(result.returncode)
    stage = args.output.with_suffix(".pending")
    class_count = 0
    with zipfile.ZipFile(classes) as code, zipfile.ZipFile(args.input) as source, zipfile.ZipFile(stage, "w", zipfile.ZIP_DEFLATED, allowZip64=True) as output:
        seen = set()
        for entry in code.infolist():
            if entry.filename.endswith(".class"):
                data = code.read(entry)
                verify_resource_pruning(entry.filename, data)
                output.writestr(entry.filename, data)
                seen.add(entry.filename)
                class_count += 1
        for entry in source.infolist():
            name = entry.filename
            if name in seen or name in REMOVED_DEFAULT_RESOURCES or name.endswith((".class", ".kotlin_module")) or name.endswith("/"):
                continue
            if re.fullmatch(r"META-INF/[^/]+\.(SF|DSA|RSA)", name, re.IGNORECASE):
                continue
            output.writestr(name, source.read(entry))
            seen.add(name)
        # The same unmodified Kotlin standard library also reaches platform/core. Never member-shrink
        # a second incompatible copy: preserve it verbatim and let final assembly deduplicate it.
        for library in args.bundle_library:
            with zipfile.ZipFile(library) as bundled:
                for entry in bundled.infolist():
                    name = entry.filename
                    if name in seen or name.endswith("/") or name.endswith("module-info.class"):
                        continue
                    if re.fullmatch(r"META-INF/[^/]+\.(SF|DSA|RSA)", name, re.IGNORECASE):
                        continue
                    output.writestr(name, bundled.read(entry))
                    seen.add(name)
    stage.replace(args.output)
    report = {"input": str(args.input), "input-bytes": args.input.stat().st_size,
              "output": str(args.output), "bytes": args.output.stat().st_size, "sha256": sha256(args.output),
              "classfiles": class_count, "r8-sha256": expected, "mode": "classfile-member-shrink-not-obfuscation"}
    (work / "result.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"SHRINK PASS input-bytes={report['input-bytes']} output-bytes={report['bytes']} classes={class_count} sha256={report['sha256']}")


if __name__ == "__main__":
    main()
