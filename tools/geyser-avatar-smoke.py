"""Pinned Geyser Avatar value/bytecode inspection and real 256MiB loader/base-pack smoke.

help/inspect/sdk-contract do not start a JVM. Only `run` starts one owned Geyser child, inside a
new .narrafork/geyser-avatar-smoke nonce directory. It never reads production config/key/DB/player
cache. Reuses the transfer smoke's literal public locale/default-skin allowlist only.
With --native-probe, one 128MiB test-only compiler JVM finishes before the Geyser JVM starts;
its original translator/ItemData assertions then execute inside real post-initialize registries.
No player/login is attempted; this is not a render/download ACK, Xbox proof or real-avatar test.
Official Geyser may fetch public resource/version/news manifests: this is not an OS network sandbox.
"""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("avatar_public_smoke_helpers", ROOT / "tools/geyser-transfer-smoke.py")
COMMON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMMON)
BASE = ROOT / ".narrafork/geyser-avatar-smoke"
ID = "kyokouauthavatar"
NAME = "KyokouAuthAvatar"
ENTRY = "moe.ryuu.kyokouauth.geyser.avatar.KyokouAvatarExtension"
READY = "KYOKOU_AVATAR_READY status=offered-only"
DEFINITIONS = "KYOKOU_AVATAR_DEFINITIONS_READY count=2"
BASE_READY = re.compile(r"KYOKOU_AVATAR_BASE_PACK_READY sha256=([0-9a-f]{64}) bytes=([0-9]+)")
LIMITATIONS = ["Real fixed loader, native v2 registration and native base PackCodec/manifest only; no player or authentication attempted.",
               "No Bedrock download/render ACK, phone avatar rendering or live-account skin assertion.",
               "Unmodified Geyser may fetch official public manifests/news; no OS-level network sandbox claimed.",
               "Every configured listener is a newly reserved loopback endpoint; production config/key/DB are not read."]

require = COMMON.require


def class_file(raw):
    stream = io.BytesIO(raw)
    def u2(): return struct.unpack(">H", stream.read(2))[0]
    def u4(): return struct.unpack(">I", stream.read(4))[0]
    require(stream.read(4) == b"\xca\xfe\xba\xbe", "Invalid native class")
    stream.read(4)
    pool = [None] * u2()
    index = 1
    while index < len(pool):
        tag = stream.read(1)[0]
        if tag == 1: pool[index] = stream.read(u2()).decode("utf-8", errors="replace")
        elif tag in (3, 4): stream.read(4)
        elif tag in (5, 6): stream.read(8); index += 1
        elif tag in (7, 8, 16, 19, 20): pool[index] = u2()
        elif tag in (9, 10, 11, 12, 17, 18): pool[index] = (u2(), u2())
        elif tag == 15: stream.read(3)
        else: raise COMMON.SmokeFailure("Unknown native constant-pool entry")
        index += 1
    def resolve(index):
        value = pool[index]
        if isinstance(value, int): return resolve(value)
        if isinstance(value, tuple): return tuple(resolve(item) for item in value)
        return value
    stream.read(6)
    stream.read(2 * u2())
    methods = {}
    for group in range(2):
        for _ in range(u2()):
            flags, name, descriptor, count = u2(), u2(), u2(), u2()
            attributes = {}
            for _ in range(count):
                key, size = u2(), u4()
                attributes[pool[key]] = stream.read(size)
            if group == 1 and "Code" in attributes:
                code = attributes["Code"]
                methods[(pool[name], pool[descriptor])] = code[8:8 + struct.unpack_from(">I", code, 4)[0]]
    return methods, resolve


def instructions(code):
    sizes = {0x10: 2, 0x11: 3, 0x12: 2, 0x13: 3, 0x14: 3, 0x84: 3, 0xA9: 2,
             0xB9: 5, 0xBA: 5, 0xBB: 3, 0xBC: 2, 0xBD: 3, 0xC0: 3, 0xC1: 3,
             0xC5: 4, 0xC6: 3, 0xC7: 3, 0xC8: 5, 0xC9: 5}
    for opcode in list(range(0x15, 0x1A)) + list(range(0x36, 0x3B)): sizes[opcode] = 2
    for opcode in list(range(0x99, 0xA9)) + list(range(0xB2, 0xB9)): sizes[opcode] = 3
    at = 0
    while at < len(code):
        opcode = code[at]
        size = sizes.get(opcode, 1)
        if opcode in (0xAA, 0xAB):
            padding = (4 - (at + 1) % 4) % 4
            base = at + 1 + padding
            if opcode == 0xAA:
                low, high = struct.unpack_from(">ii", code, base + 4)
                size = 1 + padding + 12 + (high - low + 1) * 4
            else:
                pairs = struct.unpack_from(">i", code, base + 4)[0]
                size = 1 + padding + 8 + pairs * 8
        elif opcode == 0xC4: size = 6 if code[at + 1] == 0x84 else 4
        require(size > 0 and at + size <= len(code), "Invalid native instruction boundary")
        reference = struct.unpack_from(">H", code, at + 1)[0] if 0xB2 <= opcode <= 0xB9 else None
        yield at, opcode, reference
        at += size


def sdk_contract(geyser):
    require(COMMON.digest(geyser) == COMMON.GEYSER_SHA256, "Official SDK SHA differs")
    prefix = "org/geysermc/geyser/translator/item/"
    with zipfile.ZipFile(geyser) as archive:
        raw = archive.read(prefix + "ItemTranslator.class")
        methods, resolve = class_file(raw)
        full = next(code for (name, signature), code in methods.items() if name == "translateToBedrock" and
                    "Lorg/geysermc/geyser/item/type/Item;Lorg/geysermc/geyser/registry/type/ItemMapping;I" in signature)
        calls = [(at, resolve(ref)) for at, opcode, ref in instructions(full) if ref is not None and opcode in (0xB6, 0xB7, 0xB8, 0xB9)]
        head = [at for at, value in calls if value[1][0] == "translatePlayerHead"]
        custom = [at for at, value in calls if value[1][0] == "translateCustomItem"]
        require(len(head) == len(custom) == 1 and head[0] < custom[0], "Native player-head/custom ordering differs")
        body = next(code for (name, signature), code in methods.items() if name == "translateCustomItem")
        decoded = list(instructions(body))
        get_at = definition_at = block_at = None
        for index, (at, opcode, ref) in enumerate(decoded):
            if ref is None or opcode not in (0xB6, 0xB8, 0xB9): continue
            owner, method = resolve(ref)
            if method[0] == "getCustomItem": get_at = at
            if method[0] == "definition": definition_at = at
            if method[0] == "blockDefinition":
                block_at = at
                require(index > 0 and decoded[index - 1][1] == 1, "Native custom mapping no longer explicitly clears blockDefinition")
        require(None not in (get_at, definition_at, block_at) and get_at < definition_at < block_at,
                "Native custom item selection/definition/clear path differs")
    return {"sdk_sha256": COMMON.GEYSER_SHA256, "item_translator_class_sha256": hashlib.sha256(raw).hexdigest(),
            "translate_player_head_pc": head[0], "translate_custom_item_pc": custom[0],
            "custom_get_pc": get_at, "custom_definition_pc": definition_at, "custom_clear_block_pc": block_at,
            "jvm_started": False, "kind": "fixed-native-bytecode-contract-not-rendering"}


def inspect(args):
    geyser = COMMON.public_file(args.geyser_jar, 256 * 1024 * 1024)
    proof = sdk_contract(geyser)
    extension = COMMON.public_file(args.extension_jar, 128 * 1024)
    with zipfile.ZipFile(extension) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate avatar Jar entries")
        descriptor = dict(re.findall(r"^(id|name|main|api|version):[ \t]*([^\r\n]+)", archive.read("extension.yml").decode(), re.M))
        require(all(descriptor.get(key) == value for key, value in {"id": ID, "name": NAME, "main": ENTRY, "api": "2.0.0"}.items()), "Avatar descriptor mismatch")
        require(descriptor.get("version") and "${" not in descriptor["version"], "Avatar Jar version not expanded")
        entry = archive.read(ENTRY.replace(".", "/") + ".class")
        require(all(marker.encode() in entry for marker in (READY, DEFINITIONS, "KYOKOU_AVATAR_BASE_PACK_READY sha256=")),
                "Rebuild avatar Jar: real lifecycle markers missing")
        require("avatar.properties" in names, "Avatar default config absent")
        require(not any(name.startswith(("org/geysermc/", "io/netty/", "kotlin/", "com/google/")) for name in names), "Avatar Jar bundles provided SDK")
    return {"geyser": geyser, "extension": extension, "sdk_contract": proof, "extension_sha256": COMMON.digest(extension), "descriptor": descriptor}


def trace(text, ports, native_probe=False):
    clean = COMMON.ANSI.sub("", text)
    require(COMMON.BAD_LOG.search(clean) is None, "Native avatar loader/resource log contains ERROR or exception")
    return {"enabled": re.search(r"(?m)Enabled extension " + NAME + r"[ \t]*$", clean) is not None,
            "expected_extensions": re.search(r"(?m)Loaded " + ("2" if native_probe else "1") + r" extension\(s\)[ \t]*$", clean) is not None,
            "runtime": READY in clean, "definitions": DEFINITIONS in clean,
            "native_base_codec": BASE_READY.search(clean) is not None,
            "native_custom": not native_probe or "KYOKOU_AVATAR_NATIVE_CUSTOM_PASS" in clean,
            "native_styles": not native_probe or "KYOKOU_AVATAR_NATIVE_STYLES_PASS" in clean,
            "listener": f"Started Geyser on 127.0.0.1:{ports['raknet_udp_and_builtin_tcp']}" in clean,
            "startup_done": re.search(r"Done \([0-9.,]+s\)!", clean) is not None}


def wait_ready(process, events, overflow, ports, timeout, native_probe=False):
    end = time.monotonic() + timeout
    text = ""
    while time.monotonic() < end:
        require(not overflow.is_set(), "Native avatar output exceeded bounded log queue")
        require(process.poll() is None, "Native Geyser exited before avatar readiness")
        try: line = events.get(timeout=max(0.01, end - time.monotonic()))
        except queue.Empty: break
        require(line is not None, "Native output ended before avatar readiness")
        text += line
        if all(trace(text, ports, native_probe).values()): return text
    raise COMMON.SmokeFailure("Native avatar startup/definitions/base codec readiness timed out")


def capture_base(run, text):
    cache = run / "extensions" / ID / "avatar-packs"
    files = list(cache.glob("avatar-base-*.mcpack"))
    require(len(files) == 1, "Expected one bounded owned generic base pack")
    source = files[0]
    require(source.stat().st_size <= 32768 and not source.is_symlink(), "Unsafe base-pack path or size")
    raw = source.read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw)) as pack:
        require(set(pack.namelist()) == {"manifest.json", "textures/item_texture.json", "textures/kyokouauth/role_u.png", "textures/kyokouauth/role_j.png"}, "Avatar base touches non-owned paths")
        manifest = json.loads(pack.read("manifest.json"))
        atlas = json.loads(pack.read("textures/item_texture.json"))
        require(manifest["format_version"] == 2 and manifest["header"]["version"] == [1, 0, 0], "Native base manifest version differs")
        require(manifest["header"]["uuid"] != manifest["modules"][0]["uuid"], "Base module UUID aliases its header")
        require(set(atlas["texture_data"]) == {"kyokouauth_role_u", "kyokouauth_role_j"}, "Base atlas overrides ordinary items")
        pngs = [pack.read("textures/kyokouauth/role_" + role + ".png") for role in ("u", "j")]
        for png in pngs:
            require(len(png) <= 8192 and png[:8] == b"\x89PNG\r\n\x1a\n" and struct.unpack_from(">II", png, 16) == (32, 32) and png[24:26] == bytes([8, 6]), "Base PNG is not bounded RGBA32")
        require(pngs[0] != pngs[1], "Unknown roles share an indistinguishable fallback icon")
    match = BASE_READY.search(COMMON.ANSI.sub("", text))
    sha = hashlib.sha256(raw).hexdigest()
    require(match is not None and match[1] == sha and int(match[2]) == len(raw), "Native public codec SHA/size differ from immutable ZIP bytes")
    target = run / "captured-avatar-base.mcpack"
    target.write_bytes(raw)
    return {"path": str(target.relative_to(ROOT)), "sha256": sha, "bytes": len(raw), "uuid": manifest["header"]["uuid"],
            "icons": [hashlib.sha256(png).hexdigest() for png in pngs]}


def compile_native_probe(run, args, geyser, evidence):
    """Test-only javac JVM ends before Geyser starts. No Gradle/build-file edits or SDK bundling."""
    javac = args.jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    require(javac.is_file(), "Missing explicit JDK compiler for native probe")
    package = "moe/ryuu/kyokouauth/geyser/avatar"
    source_root = ROOT / "platform-geyser-avatar/src/test/java" / package
    sources = [COMMON.public_file(source_root / name, 32768) for name in ("NativeAvatarOracle.java", "AvatarNativeProbeExtension.java")]
    classes = run / "native-probe-classes"
    classes.mkdir()
    command = [str(javac.resolve()), "-J-Xmx128M", "-proc:none", "--release", "17", "-encoding", "UTF-8",
               "-Xmaxerrs", "20", "-Xmaxwarns", "20", "-classpath", str(geyser), "-d", str(classes), *map(str, sources)]
    with subprocess.Popen(command, cwd=run, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, encoding="utf-8", errors="replace") as compiler:
        evidence.update({"jvm_started": True, "compiler_jvm_started": True, "compiler_pid": compiler.pid})
        try: output, _ = compiler.communicate(timeout=45)
        except subprocess.TimeoutExpired:
            compiler.kill(); output, _ = compiler.communicate(timeout=10)
            (run / "native-probe-compiler.log").write_text(output, encoding="utf-8")
            raise COMMON.SmokeFailure("Owned native-probe compiler exceeded 45 seconds")
        evidence["compiler_exit_code"] = compiler.returncode
        (run / "native-probe-compiler.log").write_text(output, encoding="utf-8")
        require(len(output.encode("utf-8")) <= COMMON.MAX_LOG_BYTES and compiler.returncode == 0,
                "Native probe did not compile against fixed SDK; see isolated compiler log")
    artifact = run / "extensions/KyokouAuthAvatarProbe.jar"
    descriptor = ("id: kyokouauthavatarprobe\nname: KyokouAuthAvatarProbe\n"
                  "main: moe.ryuu.kyokouauth.geyser.avatar.AvatarNativeProbeExtension\napi: 2.0.0\nversion: 0.1.0-smoke\n")
    with zipfile.ZipFile(artifact, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("extension.yml", descriptor)
        for path in sorted(classes.rglob("*.class")):
            name = path.relative_to(classes).as_posix()
            require(name.startswith(package + "/"), "Native probe attempted to package a provided dependency")
            archive.write(path, name)
    require(artifact.stat().st_size <= 128 * 1024, "Test-only native probe exceeded thin-jar bound")
    evidence["native_probe_jar_sha256"] = COMMON.digest(artifact)
    evidence["native_probe_sources"] = {path.name: COMMON.digest(path) for path in sources}


def run_smoke(args):
    BASE.mkdir(parents=True, exist_ok=True)
    run = BASE / uuid.uuid4().hex
    run.mkdir()
    evidence = {"kind": "native-avatar-loader-base-pack-smoke", "status": "failed", "jvm_started": False,
                "players_attempted": 0, "limitations": LIMITATIONS, "native_probe": args.native_probe,
                "compiler_jvm_started": False, "geyser_jvm_started": False, "run_directory": str(run.relative_to(ROOT))}
    process = reader = None
    reservations = []
    failure = None
    try:
        artifacts = inspect(args)
        resources = COMMON.resource_plan(args)
        java = args.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        require(java.is_file(), "Missing explicit JDK executable")
        evidence.update({key: value for key, value in artifacts.items() if key not in ("geyser", "extension")})
        evidence["public_resources"] = COMMON.copy_resources(run, resources, args)
        shutil.copyfile(artifacts["geyser"], run / "Geyser-Standalone.jar")
        ext = run / "extensions"
        ext.mkdir()
        shutil.copyfile(artifacts["extension"], ext / "KyokouAuthAvatar.jar")
        require(COMMON.digest(run / "Geyser-Standalone.jar") == COMMON.GEYSER_SHA256 and
                COMMON.digest(ext / "KyokouAuthAvatar.jar") == artifacts["extension_sha256"],
                "Public artifacts changed during isolated copy")
        if args.native_probe:
            compile_native_probe(run, args, artifacts["geyser"], evidence)
        data = ext / ID
        data.mkdir()
        reservations, ports = COMMON.reserve_ports()
        for field in ("avatar_proxy", "avatar_status"):
            reserved = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            reserved.bind(("127.0.0.1", 0)); reserved.listen(1)
            reservations.append(reserved); ports[field] = reserved.getsockname()[1]
        (data / "avatar.properties").write_text("enabled=true\n" +
                f"proxy-endpoint=http://127.0.0.1:{ports['avatar_proxy']}/v1/avatars\nstatus-host=127.0.0.1\nstatus-port={ports['avatar_status']}\n" +
                "snapshot-timeout-ms=200\nmax-packs=64\nmax-sessions=1024\n", encoding="utf-8")
        config = COMMON.configuration(ports).replace("advanced:\n", "gameplay:\n  enable-custom-content: true\n  force-resource-packs: true\n  enable-integrated-pack: true\nadvanced:\n")
        require("validate-bedrock-login: true" in config and "validate-bedrock-login: false" not in config, "Xbox validation must remain enabled")
        (run / "config.yml").write_text(config, encoding="utf-8")
        evidence["ports"] = ports
        env = COMMON.child_environment(run)
        command = [str(java.resolve()), "-Xms32M", "-Xmx256M", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                   "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", f"-Duser.home={run / 'home'}",
                   f"-Djava.io.tmpdir={run / 'tmp'}", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dio.netty.eventLoopThreads=2",
                   "-DGeyser.BedrockNetworkThreads=1", f"-Dsun.net.client.defaultConnectTimeout={args.resource_timeout_ms}",
                   f"-Dsun.net.client.defaultReadTimeout={args.resource_timeout_ms}", "-jar", str(run / "Geyser-Standalone.jar"),
                   "--nogui", "--config", str(run / "config.yml")]
        evidence["command"] = command
        # Retain only Java and snapshot placeholders; release Geyser/status once, no retry/rebind.
        for item in reservations[1:-2] + reservations[-1:]: item.close()
        process = subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        evidence.update({"jvm_started": True, "geyser_jvm_started": True, "owned_pid": process.pid})
        events = queue.Queue(maxsize=2048)
        overflow = threading.Event()
        reader = threading.Thread(target=COMMON.drain_output, args=(process, run / "console.log", events, overflow), daemon=True)
        reader.start()
        text = wait_ready(process, events, overflow, ports, args.timeout, args.native_probe)
        evidence["base_pack"] = capture_base(run, text)
        status_url = f"http://127.0.0.1:{ports['avatar_status']}/v1/packs/status?xuid=1"
        try: urllib.request.urlopen(status_url, timeout=1)
        except urllib.error.HTTPError as response:
            require(response.code == 404 and json.loads(response.read(1024)) == {"schema": 1, "state": "not-found"}, "No-session status is not strict read-only MISS")
        else: raise COMMON.SmokeFailure("Loader smoke must not fabricate a player/session offer")
        evidence["no_session_status"] = {"http": 404, "schema": 1, "state": "not-found"}
    except (Exception, KeyboardInterrupt) as caught:
        failure = str(caught) or type(caught).__name__
    finally:
        if process is not None:
            try:
                evidence["graceful_stop"] = COMMON.stop_owned(process, args.shutdown_timeout)
                evidence["exit_code"] = process.returncode
                if reader is not None: reader.join(timeout=5)
                require(reader is None or not reader.is_alive(), "Owned log reader did not terminate")
                require(evidence["graceful_stop"] and process.returncode == 0, "Native avatar child did not exit gracefully")
                final = (run / "console.log").read_text(encoding="utf-8")
                require(all(trace(final, ports, args.native_probe).values()) and "Geyser shutdown successfully." in final, "Native avatar lifecycle evidence incomplete")
                if args.native_probe:
                    for marker in ("KYOKOU_AVATAR_NATIVE_CUSTOM_PASS", "KYOKOU_AVATAR_NATIVE_STYLES_PASS"):
                        require(final.count(marker) == 1, "Missing/duplicate real native translation probe outcome")
                    evidence["native_translation_assertions"] = {"production_registry_custom_path": True, "final_itemdata_name_lore_style": True}
                for marker in (READY, DEFINITIONS): require(final.count(marker) == 1, "Duplicate avatar lifecycle marker")
                if failure is None: evidence["status"] = "passed"
            except Exception as caught: failure = failure or str(caught)
            if process.stdout: process.stdout.close()
            if process.stdin: process.stdin.close()
        for item in reservations: item.close()
        if failure is not None: evidence["failure"] = failure
        (run / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str((run / "evidence.json").relative_to(ROOT)),
                      "jvm_started": evidence["jvm_started"], "failure": failure}, ensure_ascii=False))
    return 0 if evidence["status"] == "passed" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("inspect", "sdk-contract", "run"))
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--geyser-jar", type=Path, default=ROOT / ".narrafork/floodgate-e2e/deps/Geyser-Standalone-2.12.1-b1303.jar")
    parser.add_argument("--extension-jar", type=Path, default=ROOT / "platform-geyser-avatar/build/libs/KyokouAuth-GeyserAvatar-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--client-assets-jar", type=Path)
    parser.add_argument("--native-probe", action="store_true", help="run: compile a test-only probe first, then verify real registry translation/ItemData styles after Geyser initialization")
    parser.add_argument("--timeout", type=int, choices=range(10, 91), default=90, metavar="10..90")
    parser.add_argument("--resource-timeout-ms", type=int, choices=range(1000, 30001), default=10000, metavar="1000..30000")
    parser.add_argument("--shutdown-timeout", type=int, choices=range(5, 31), default=20, metavar="5..30")
    args = parser.parse_args()
    try:
        if args.mode == "run": return run_smoke(args)
        result = sdk_contract(COMMON.public_file(args.geyser_jar, 256 * 1024 * 1024)) if args.mode == "sdk-contract" else inspect(args)
        result = {key: str(value) if isinstance(value, Path) else value for key, value in result.items()}
        print(json.dumps({"inspection": "passed", "jvm_started": False, **result}, ensure_ascii=False))
        return 0
    except Exception as caught:
        print(json.dumps({"inspection": "failed", "jvm_started": False, "failure": str(caught)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
