"""Test-only complete native Geyser GUI translation oracle, not a production extension.

inspect/self-test start no JVM. run must use the primary narrator's serial JVM window: finish one
128MiB compiler first, then one 256MiB isolated pinned Geyser. Only the tiny probe Jar is installed
inside this nonce test directory; no custom definitions, packs, avatar bridge or Transfer extension.
Replay the current native role client's actual role-selector.bin through complete ItemTranslator,
including registry defaults and final name/lore NBT per-character colors/nonitalic. No player/Xbox,
phone, client rendering or download acceptance is claimed. Official Geyser may fetch public manifests.
Never read user config/key/DB/player caches. Only literal public locale/default-skin paths are read.
"""
import argparse
from contextlib import ExitStack
import hashlib
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
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/geyser-gui-smoke"
PUBLIC = ROOT / ".narrafork/bedrock-gateway"
GEYSER_SHA256 = "1b0c239e97117853cc4618753e23a0e06c7dc351c81abdd46926012b9fce6710"
GEYSER_COMMIT = "440be82b0b2def7211504226d6ea5c37911a5643"
GEYSER_VERSION = "2.12.1-b1303 (git-master-440be82)"
SKINS = ("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")
MAX_LOG_BYTES = 8 * 1024 * 1024
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
BAD_LOG = re.compile(r"\b(?:ERROR|FATAL|SEVERE)\b|Exception in thread|Caused by:|(?:java|org|io)\.[\w.$]*(?:Exception|Error):")
PROBE = "KyokouAuthGuiProbe"
PASS = "KYOKOU_GUI_NATIVE_PASS"


class SmokeFailure(RuntimeError):
    pass


def require(value, message):
    if not value: raise SmokeFailure(message)


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream: return hashlib.file_digest(stream, algorithm).hexdigest()


def public_file(path, maximum):
    path = Path(path)
    require(path.is_file(), f"Missing explicit public fixture: {path}")
    for item in (path, *path.parents):
        require(not item.is_symlink() and not (hasattr(item, "is_junction") and item.is_junction()), "Public input traverses link/junction")
    require(path.stat().st_size <= maximum, "Public fixture exceeds bounded size")
    return path.resolve()


def properties(raw):
    result = {}
    for line in raw.decode("utf-8").splitlines():
        if not line or line.lstrip().startswith(("#", "!")) or "=" not in line: continue
        key, value = line.split("=", 1)
        require(key not in result, "Duplicate public metadata key")
        result[key] = re.sub(r"\\([:=! ])", r"\1", value)
    return result


def inspect_sdk(path):
    path = public_file(path, 256 * 1024 * 1024)
    require(digest(path) == GEYSER_SHA256, "Pinned official Geyser 2.12.1-b1303 SHA differs")
    with zipfile.ZipFile(path) as archive:
        metadata = properties(archive.read("git.properties"))
        require(metadata.get("git.build.number") == "1303" and metadata.get("git.build.version") == GEYSER_VERSION
                and metadata.get("git.commit.id") == GEYSER_COMMIT, "Official SDK build metadata differs")
        for name in ("org/geysermc/geyser/translator/item/ItemTranslator.class",
                     "org/geysermc/mcprotocollib/protocol/packet/ingame/clientbound/inventory/ClientboundContainerSetContentPacket.class"):
            require(name in archive.namelist(), "Missing complete native translation/official codec entry")
    return path


def asset_bytes(relative, entry, archive, maximum):
    source = PUBLIC / relative
    if source.is_file():
        source = public_file(source, maximum)
        return source.read_bytes(), source
    if archive is not None and entry in archive.namelist():
        require(archive.getinfo(entry).file_size <= maximum, "Explicit public client asset exceeds bound")
        return archive.read(entry), None
    raise SmokeFailure(f"Missing literal public asset {relative}; supply --client-assets-jar. No cache scan/private data read")


def resource_plan(args):
    resources = []
    with ExitStack() as stack:
        archive = None
        if args.client_assets_jar is not None:
            source = public_file(args.client_assets_jar, 256 * 1024 * 1024)
            require(source.suffix.lower() == ".jar", "Explicit client assets must be a public Jar")
            archive = stack.enter_context(zipfile.ZipFile(source))
        for locale in ("en_us", "deprecated"):
            relative = Path("locales") / (locale + ".json")
            data, source = asset_bytes(relative, f"assets/minecraft/lang/{locale}.json", archive, 4 * 1024 * 1024)
            require(isinstance(json.loads(data), dict), "Invalid literal public locale")
            resources.append((relative, data, source))
        for shape in ("slim", "wide"):
            for skin in SKINS:
                relative = Path("cache/default_player_skins") / shape / (skin + ".png")
                data, source = asset_bytes(relative, f"assets/minecraft/textures/entity/player/{shape}/{skin}.png", archive, 1024 * 1024)
                require(len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n", "Invalid public default-skin PNG")
                width, height = struct.unpack_from(">II", data, 16)
                require(0 < width <= 256 and 0 < height <= 256, "Public default-skin dimensions exceed bound")
                resources.append((relative, data, source))
    return resources


def copy_resources(run, resources, args):
    evidence = []
    for relative, data, source in resources:
        target = run / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if source is not None: shutil.copyfile(source, target)
        else: target.write_bytes(data)
        expected = hashlib.sha256(data).hexdigest()
        require(digest(target) == expected, "Public resource changed during copy")
        evidence.append({"path": relative.as_posix(), "sha256": expected,
                         "source": "literal-public-allowlist" if source else "explicit-public-client-jar"})
    if args.client_assets_jar is not None:
        (run / "cache/client_jar.hash").write_text(digest(args.client_assets_jar, "sha1"), encoding="utf-8")
    return evidence


def reserve_ports():
    sockets = []
    try:
        placeholder = socket.socket(); sockets.append(placeholder)
        placeholder.bind(("127.0.0.1", 0)); placeholder.listen(1)
        for _ in range(32):
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            tcp = socket.socket()
            udp.bind(("127.0.0.1", 0))
            try: tcp.bind(("127.0.0.1", udp.getsockname()[1]))
            except OSError:
                udp.close(); tcp.close(); continue
            sockets.extend((udp, tcp)); break
        else: raise SmokeFailure("No loopback TCP/UDP pair available")
        media = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); sockets.append(media); media.bind(("127.0.0.1", 0))
        return sockets, {"java": placeholder.getsockname()[1], "bedrock": udp.getsockname()[1], "media": media.getsockname()[1]}
    except BaseException:
        for item in sockets: item.close()
        raise


def configuration(ports):
    return f'''config-version: 12
default-locale: en_us
debug-mode: false
log-player-ip-addresses: false
saved-user-logins: []
notify-on-new-bedrock-update: false
enable-metrics: false
bedrock:
  address: 127.0.0.1
  port: {ports['bedrock']}
  webrtc-port: {ports['media']}
  transport: both
  clone-remote-port: false
  signaling:
    mode: builtin
    advertise-addresses: ["127.0.0.1:{ports['media']}"]
    stun-discovery: false
java:
  address: 127.0.0.1
  port: {ports['java']}
  auth-type: offline
  resolve-srv: false
  forward-hostname: false
motd:
  primary-motd: Isolated GUI oracle
  secondary-motd: No player login
  passthrough-motd: false
  passthrough-player-counts: false
  integrated-ping-passthrough: false
advanced:
  resource-pack-urls: []
  cache-images: 0
  java:
    use-haproxy-protocol: false
  bedrock:
    validate-bedrock-login: true
    haproxy-protocol-whitelisted-ips: []
    signaling-trusted-proxies: []
    webrtc-proxy-protocol-relays: []
'''


def selector_packet(path):
    source = public_file(path, 32768)
    isolated = (ROOT / ".narrafork/role-selection-e2e/runtime").resolve()
    require(source.name == "role-selector.bin" and source.parent.parent == isolated
            and re.fullmatch(r"floodgate-[ju]-[0-9a-f]{12}", source.parent.name), "Selector must come from the explicit current isolated FG native role run")
    fixture = json.loads(public_file(source.parent / "isolated-role-fixture.json", 32768).read_text(encoding="utf-8"))
    require(fixture.get("protocol") == 777 and fixture.get("mode") == "floodgate" and fixture.get("floodgateMessages") is True
            and fixture.get("relation") == "synthetic-current-fixture" and fixture.get("texture") == "synthetic-not-official-profile"
            and fixture.get("clientSourceSha256") == digest(ROOT / "tools/RoleSelectionClient.java")
            and fixture.get("realMicrosoftVerified") is False and fixture.get("realBedrockVerified") is False,
            "Selector is stale or not this current synthetic native client fixture")
    result = json.loads(public_file(source.parent / "result.json", 65536).read_text(encoding="utf-8"))
    require(result.get("nativeBackendHandshakes") == 1 and result.get("transferPackets") == 0 and result.get("cookiePackets") == 0,
            "Selector source did not pass native no-Transfer role evidence verification")
    require(source.stat().st_size > 0, "Empty GUI packet")
    return source


def environment(run):
    result = os.environ.copy()
    for key in list(result):
        if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"}: result.pop(key)
    for key in ("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME", "TMP", "TEMP"):
        target = run / key.lower(); target.mkdir(exist_ok=True); result[key] = str(target)
    return result


def compile_probe(run, args, geyser, evidence, env):
    javac = args.jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    require(javac.is_file(), "Missing explicit JDK compiler")
    sources = [public_file(ROOT / "tools/geyser" / name, 32768) for name in ("GeyserGuiOracle.java", "GeyserGuiProbe.java")]
    classes = run / "classes"; classes.mkdir()
    command = [str(javac.resolve()), "-J-Xmx128M", "-J-XX:ActiveProcessorCount=2", "-proc:none", "--release", "17", "-encoding", "UTF-8",
               "-classpath", str(geyser), "-d", str(classes), *map(str, sources)]
    with subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace") as child:
        evidence.update(compiler_jvm_started=True, compiler_pid=child.pid)
        try: output, _ = child.communicate(timeout=45)
        except subprocess.TimeoutExpired:
            child.kill(); output, _ = child.communicate(timeout=10)
            (run / "compiler.log").write_text(output, encoding="utf-8")
            raise SmokeFailure("Owned compiler exceeded finite 45s deadline")
        (run / "compiler.log").write_text(output, encoding="utf-8")
        require(child.returncode == 0 and len(output.encode("utf-8")) <= MAX_LOG_BYTES, "Probe compilation failed; see isolated compiler.log")
        evidence["compiler_exit_code"] = child.returncode
    package = "moe/ryuu/kyokouauth/tools/geyser/"
    jar = run / "extensions" / (PROBE + ".jar")
    with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("extension.yml", "id: kyokouauthguiprobe\nname: " + PROBE + "\nmain: moe.ryuu.kyokouauth.tools.geyser.GeyserGuiProbe\napi: 2.0.0\nversion: 0.1.0-test-only\n")
        for path in sorted(classes.rglob("*.class")):
            name = path.relative_to(classes).as_posix()
            require(name.startswith(package), "Probe attempted to bundle provided SDK/JUnit")
            archive.write(path, name)
    require(jar.stat().st_size <= 128 * 1024, "Test-only probe exceeds thin Jar bound")
    evidence.update(probe_jar_sha256=digest(jar), sources={source.name: digest(source) for source in sources})


def assert_trace(text, ports, shutdown=True):
    text = ANSI.sub("", text)
    require(BAD_LOG.search(text) is None, "Real native resource/loader/translation error")
    for marker in (PASS, "Enabled extension " + PROBE, "Loaded 1 extension(s)"):
        require(len(re.findall(r"(?m)" + re.escape(marker) + r"[ \t]*$", text)) == 1, "Missing/duplicate real native marker: " + marker)
    for slot, item in ((12, "name_tag"), (14, "diamond")):
        require(text.count(f"KYOKOU_GUI_NATIVE slot={slot} item=minecraft:{item} ") == 1, "Missing/duplicate final native icon/NBT observation")
    require(re.search(r"Done \([0-9.,]+s\)!", text), "Native initialization incomplete")
    require(re.search(re.escape(f"Started Geyser on 127.0.0.1:{ports['bedrock']}") + r"(?![0-9])", text), "Native listener differs from reserved loopback")
    if shutdown: require("Geyser shutdown successfully." in text, "Native graceful shutdown absent")
    return {"real_gate_payload": True, "complete_item_translator": True, "final_nbt_styles": True, "vanilla_icons": True}


def drain(child, path, events, overflow):
    total = 0
    with path.open("w", encoding="utf-8") as log:
        for line in child.stdout:
            total += len(line.encode("utf-8"))
            if total > MAX_LOG_BYTES: overflow.set(); continue
            log.write(line); log.flush()
            try: events.put_nowait(line)
            except queue.Full: overflow.set()


def wait_ready(child, events, overflow, ports, timeout):
    deadline = time.monotonic() + timeout
    text = ""
    while time.monotonic() < deadline:
        require(not overflow.is_set(), "Native stdout exceeds finite queue/log bound")
        require(child.poll() is None, "Owned Geyser exited before translation readiness")
        try: text += events.get(timeout=max(0.01, deadline - time.monotonic()))
        except queue.Empty: break
        require(BAD_LOG.search(ANSI.sub("", text)) is None, "Native startup/probe error")
        if PASS in text and "Done (" in text:
            assert_trace(text, ports, shutdown=False); return
    raise SmokeFailure("Native GUI oracle readiness deadline exceeded")


def stop_owned(child, timeout):
    if child.poll() is not None: return False
    try: child.stdin.write("geyser stop\n"); child.stdin.flush()
    except (BrokenPipeError, OSError): pass
    try: child.wait(timeout=timeout); return True
    except subprocess.TimeoutExpired:
        child.kill(); child.wait(timeout=10); return False


def run_smoke(args):
    geyser = inspect_sdk(args.geyser_jar)
    selector = selector_packet(args.selector_packet)
    resources = resource_plan(args)
    BASE.mkdir(parents=True, exist_ok=True)
    run = BASE / uuid.uuid4().hex; run.mkdir()
    evidence = {"kind": "test-only-complete-native-geyser-gui", "status": "failed", "compiler_jvm_started": False,
                "geyser_jvm_started": False, "production_extension_required": False, "players_attempted": 0,
                "rendering_verified": False, "xbox_verified": False, "fully_offline": False}
    child = reader = None
    reservations = []
    failure = None
    try:
        java = args.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        require(java.is_file(), "Missing explicit JDK runtime")
        evidence["public_resources"] = copy_resources(run, resources, args)
        shutil.copyfile(geyser, run / "Geyser-Standalone.jar")
        require(digest(run / "Geyser-Standalone.jar") == GEYSER_SHA256, "Copied public SDK changed")
        shutil.copyfile(selector, run / "role-selector.bin")
        require(digest(run / "role-selector.bin") == digest(selector), "Copied GUI payload changed")
        evidence["selector_packet"] = {"source": str(selector.relative_to(ROOT)), "sha256": digest(selector)}
        (run / "extensions").mkdir()
        env = environment(run)
        compile_probe(run, args, run / "Geyser-Standalone.jar", evidence, env)  # Wait/reap BEFORE Geyser JVM.
        reservations, ports = reserve_ports(); evidence["ports"] = ports
        (run / "config.yml").write_text(configuration(ports), encoding="utf-8")
        command = [str(java.resolve()), "-Xms32M", "-Xmx256M", "-XX:ActiveProcessorCount=2", "-Djava.awt.headless=true",
                   "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US", f"-Duser.home={run / 'home'}",
                   f"-Djava.io.tmpdir={run / 'tmp'}", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-Dio.netty.eventLoopThreads=2",
                   "-DGeyser.BedrockNetworkThreads=1", f"-Dsun.net.client.defaultConnectTimeout={args.resource_timeout_ms}",
                   f"-Dsun.net.client.defaultReadTimeout={args.resource_timeout_ms}", f"-Dkyokou.gui.selectorPacket={run / 'role-selector.bin'}",
                   "-jar", str(run / "Geyser-Standalone.jar"), "--nogui", "--config", str(run / "config.yml")]
        evidence["command"] = command
        for item in reservations[1:]: item.close()
        child = subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        evidence.update(geyser_jvm_started=True, owned_pid=child.pid)
        events = queue.Queue(maxsize=2048); overflow = threading.Event()
        reader = threading.Thread(target=drain, args=(child, run / "console.log", events, overflow), daemon=True); reader.start()
        wait_ready(child, events, overflow, ports, args.timeout)
    except (Exception, KeyboardInterrupt) as caught: failure = str(caught) or type(caught).__name__
    finally:
        if child is not None:
            try:
                evidence["graceful_stop"] = stop_owned(child, args.shutdown_timeout)
                evidence["exit_code"] = child.returncode
                if reader: reader.join(timeout=5)
                require(not reader or not reader.is_alive(), "Owned stdout reader failed to terminate")
                require(evidence["graceful_stop"] and child.returncode == 0, "Native child failed graceful stop")
                evidence["observations"] = assert_trace((run / "console.log").read_text(encoding="utf-8"), ports)
                if failure is None: evidence["status"] = "passed"
            except Exception as caught: failure = failure or str(caught)
            if child.stdout: child.stdout.close()
            if child.stdin: child.stdin.close()
        for item in reservations: item.close()
        if failure: evidence["failure"] = failure
        (run / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str((run / "evidence.json").relative_to(ROOT)), "failure": failure}))
    return 0 if evidence["status"] == "passed" else 1


def self_test():
    ports = {"java": 30121, "bedrock": 30123, "media": 30124}
    config = configuration(ports)
    require("validate-bedrock-login: true" in config and "validate-bedrock-login: false" not in config, "Xbox verification default regressed")
    lines = ["Enabled extension " + PROBE, "Loaded 1 extension(s)", PASS, "Done (1.234s)!", "Started Geyser on 127.0.0.1:30123",
             "KYOKOU_GUI_NATIVE slot=12 item=minecraft:name_tag Name=synthetic Lore=synthetic",
             "KYOKOU_GUI_NATIVE slot=14 item=minecraft:diamond Name=synthetic Lore=synthetic", "Geyser shutdown successfully."]
    assert_trace("\n".join(lines), ports)
    invalid = ["\n".join(lines[:at] + lines[at + 1:]) for at in range(len(lines))]
    invalid += ["\n".join(lines + [PASS]), "\n".join(lines + ["[ERROR] native failure"]), "\n".join(lines).replace(":30123", ":301234")]
    for text in invalid:
        try: assert_trace(text, ports)
        except SmokeFailure: continue
        raise AssertionError("Synthetic invalid trace accepted")
    return {"kind": "synthetic-verifier-self-test-only", "rejected": len(invalid), "jvm_started": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("inspect", "self-test", "run"))
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--geyser-jar", type=Path, default=ROOT / ".narrafork/floodgate-e2e/deps/Geyser-Standalone-2.12.1-b1303.jar")
    parser.add_argument("--client-assets-jar", type=Path)
    parser.add_argument("--selector-packet", type=Path, help="Explicit current successful isolated FG role-selector.bin")
    parser.add_argument("--timeout", type=int, choices=range(10, 91), default=90)
    parser.add_argument("--shutdown-timeout", type=int, choices=range(5, 31), default=20)
    parser.add_argument("--resource-timeout-ms", type=int, choices=range(1000, 30001), default=10000)
    args = parser.parse_args()
    try:
        if args.mode == "run":
            require(args.selector_packet is not None, "run requires current --selector-packet")
            return run_smoke(args)
        result = self_test() if args.mode == "self-test" else {"sdk": str(inspect_sdk(args.geyser_jar)), "sha256": GEYSER_SHA256, "jvm_started": False}
        print(json.dumps(result)); return 0
    except Exception as caught:
        print(json.dumps({"status": "failed", "failure": str(caught)})); return 1


if __name__ == "__main__":
    sys.exit(main())
