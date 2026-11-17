"""Real fixed Geyser Standalone + transfer-extension loader smoke, NOT a player/login test.

help, inspect and self-test never start a JVM. Only run starts one 256 MiB child, in the primary
narrator's reserved JVM window. It writes exclusively to a new .narrafork/geyser-transfer-smoke
nonce directory and never reads a gateway config, key, identity DB, login cache or player cache.
Only explicitly allowlisted public locale/default-skin files and an optional explicit public
Minecraft client asset Jar are copied; files are copied, never hardlinked.

This is NOT fully offline or an OS network sandbox: unmodified official Geyser may fetch public
version/resource manifests and official news. No Bedrock/Java client, account authentication or
device-code flow is attempted. Missing/corrupt public resources and ERRORs fail the smoke.
The pinned build supports BOTH or NETHERNET, not RakNet-only: BOTH + builtin signaling + no STUN
is used, with every listener on fresh loopback ports. Success proves real loader metadata/API,
entry class and initialization/event registration, NOT Bedrock Transfer, role login or rendering.
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
BASE = ROOT / ".narrafork/geyser-transfer-smoke"
PUBLIC = ROOT / ".narrafork/bedrock-gateway"
GEYSER_SHA256 = "1b0c239e97117853cc4618753e23a0e06c7dc351c81abdd46926012b9fce6710"
GEYSER_VERSION = "2.12.1-b1303 (git-master-440be82)"
GEYSER_COMMIT = "440be82b0b2def7211504226d6ea5c37911a5643"
EXTENSION_ID = "kyokouauthtransfer"
EXTENSION_NAME = "KyokouAuthTransfer"
EXTENSION_MAIN = "moe.ryuu.kyokouauth.geyser.KyokouRoleTransferExtension"
READY = "KYOKOU_ROLE_TRANSFER_READY routes=1"
SKINS = ("alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri")
MAX_LOG_BYTES = 8 * 1024 * 1024
ANSI = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]")
BAD_LOG = re.compile(r"\b(?:ERROR|FATAL|SEVERE)\b|Exception in thread|Caused by:|(?:java|org|io)\.[\w.$]*(?:Exception|Error):")
LIMITATIONS = [
    "Real unmodified Standalone loader/initialization only; no player or authentication attempted.",
    "Not fully offline: official public version/resource manifests and news may be requested.",
    "No OS-level network isolation or complete external-request audit is claimed.",
    "BOTH transport, not RakNet-only; builtin signaling with explicit loopback and STUN disabled.",
    "No Bedrock native Transfer, GUI/skin rendering, role data or real-account acceptance is proven.",
]


class SmokeFailure(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def digest(path, algorithm="sha256"):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def public_file(path, maximum):
    """Reject links/junctions rather than accidentally following a public-cache alias to private data."""
    path = Path(path)
    require(path.is_file(), f"Missing public resource: {path}")
    for item in (path, *path.parents):
        require(not item.is_symlink() and not (hasattr(item, "is_junction") and item.is_junction()),
                f"Public resource must not traverse a link/junction: {path}")
    require(path.stat().st_size <= maximum, f"Public resource exceeds its size limit: {path}")
    return path.resolve()


def properties(raw):
    result = {}
    for line in raw.decode("utf-8").splitlines():
        if not line or line.lstrip().startswith(("#", "!")) or "=" not in line:
            continue
        key, value = line.split("=", 1)
        require(key not in result, "Duplicate public build-metadata key")
        result[key] = re.sub(r"\\([:=! ])", r"\1", value)
    return result


def inspect_jars(args):
    geyser = public_file(args.geyser_jar, 256 * 1024 * 1024)
    extension = public_file(args.extension_jar, 128 * 1024)
    require(digest(geyser) == GEYSER_SHA256, "Pinned official Geyser 2.12.1-b1303 SHA256 differs")
    with zipfile.ZipFile(geyser) as archive:
        metadata = properties(archive.read("git.properties"))
        require(metadata.get("git.build.number") == "1303" and metadata.get("git.build.version") == GEYSER_VERSION
                and metadata.get("git.commit.id") == GEYSER_COMMIT, "Pinned Geyser build metadata differs")
        locale = properties(archive.read("languages/texts/en_US.properties"))
        require(locale.get("geyser.extensions.enable.success") == "Enabled extension {0}",
                "Pinned official extension-enabled log template changed")
        require(locale.get("geyser.core.shutdown.done") == "Geyser shutdown successfully.",
                "Pinned official graceful-shutdown template changed")
    with zipfile.ZipFile(extension) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate extension archive entries")
        text = archive.read("extension.yml").decode("utf-8")
        descriptor = {}
        for key, raw in re.findall(r"^(id|name|main|api|version):[ \t]*([^\r\n#]+)", text, re.M):
            require(key not in descriptor, "Duplicate extension descriptor key")
            descriptor[key] = raw.strip().strip("\"'")
        expected = {"id": EXTENSION_ID, "name": EXTENSION_NAME, "main": EXTENSION_MAIN, "api": "2.0.0"}
        require(all(descriptor.get(key) == value for key, value in expected.items()),
                "Extension descriptor id/name/main/api does not match the real-loader contract")
        require(descriptor.get("version") and "${" not in descriptor["version"], "Extension version is not expanded")
        entry = EXTENSION_MAIN.replace(".", "/") + ".class"
        require(entry in names and b"KYOKOU_ROLE_TRANSFER_READY routes=" in archive.read(entry),
                "Rebuild the extension Jar: compiled entry/READY route-count marker is missing")
        require(not any(name.startswith(("org/geysermc/", "io/netty/", "kotlin/")) for name in names),
                "Extension Jar unexpectedly bundles its provided SDK")
    return {"geyser": geyser, "extension": extension, "extension_descriptor": descriptor,
            "geyser_sha256": GEYSER_SHA256, "extension_sha256": digest(extension)}


def asset_bytes(relative, asset_entry, asset_archive, maximum):
    source = PUBLIC / relative
    if source.is_file():
        source = public_file(source, maximum)
        return source.read_bytes(), source
    if asset_archive is not None and asset_entry in asset_archive.namelist():
        info = asset_archive.getinfo(asset_entry)
        require(info.file_size <= maximum, f"Public client asset exceeds limit: {asset_entry}")
        return asset_archive.read(asset_entry), None
    raise SmokeFailure(f"Missing public asset {relative}; supply --client-assets-jar with an explicit public client Jar. No private caches are read.")


def resource_plan(args):
    """The whitelist is literal: no gateway config, cache scan, client_jar.hash or player data is read."""
    resources = []
    with ExitStack() as stack:
        asset_jar = None
        if args.client_assets_jar is not None:
            path = public_file(args.client_assets_jar, 256 * 1024 * 1024)
            require(path.suffix.lower() == ".jar", "Client assets must be an explicitly supplied public .jar")
            asset_jar = stack.enter_context(zipfile.ZipFile(path))
        for locale in ("en_us", "deprecated"):
            relative = Path("locales") / (locale + ".json")
            data, source = asset_bytes(relative, f"assets/minecraft/lang/{locale}.json", asset_jar, 4 * 1024 * 1024)
            require(isinstance(json.loads(data), dict), f"Invalid public locale object: {relative}")
            resources.append((relative, data, source))
        for shape in ("slim", "wide"):
            for skin in SKINS:
                relative = Path("cache/default_player_skins") / shape / (skin + ".png")
                entry = f"assets/minecraft/textures/entity/player/{shape}/{skin}.png"
                data, source = asset_bytes(relative, entry, asset_jar, 1024 * 1024)
                require(len(data) >= 24 and data[:8] == b"\x89PNG\r\n\x1a\n", f"Invalid public default-skin PNG: {relative}")
                width, height = struct.unpack_from(">II", data, 16)
                require(0 < width <= 256 and 0 < height <= 256, f"Unexpected default-skin size: {relative}")
                resources.append((relative, data, source))
    return resources


def copy_resources(run, resources, args):
    copied = []
    for relative, data, source in resources:
        target = run / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        if source is not None:
            shutil.copyfile(source, target)
        else:
            target.write_bytes(data)
        expected = hashlib.sha256(data).hexdigest()
        require(digest(target) == expected, "Public resource changed during isolated copy")
        copied.append({"path": relative.as_posix(), "sha256": expected,
                       "source": "allowlisted-public-cache" if source else "explicit-public-client-jar"})
    if args.client_assets_jar is not None:
        # New isolated metadata is computed from the explicit public Jar, never copied from a gateway.
        (run / "cache/client_jar.hash").write_text(digest(args.client_assets_jar, "sha1"), encoding="utf-8")
    return copied


def reserve_ports():
    """The Java placeholder stays bound; only fresh Geyser reservations are released before launch."""
    reservations = []
    try:
        java = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        reservations.append(java)
        java.bind(("127.0.0.1", 0))
        java.listen(1)
        for _ in range(32):
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            udp.bind(("127.0.0.1", 0))
            try:
                tcp.bind(("127.0.0.1", udp.getsockname()[1]))
            except OSError:
                udp.close()
                tcp.close()
                continue
            reservations.extend((udp, tcp))
            break
        else:
            raise SmokeFailure("No fresh loopback TCP/UDP port pair is available")
        media = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        reservations.append(media)
        media.bind(("127.0.0.1", 0))
        return reservations, {"address": "127.0.0.1", "java_placeholder_tcp": java.getsockname()[1],
                              "raknet_udp_and_builtin_tcp": udp.getsockname()[1], "nethernet_udp": media.getsockname()[1]}
    except BaseException:
        for reservation in reservations:
            reservation.close()
        raise


def configuration(ports):
    return f"""# Isolated schema-12 loader smoke. No players, credentials or production endpoints.
config-version: 12
default-locale: en_us
debug-mode: false
log-player-ip-addresses: false
saved-user-logins: []
notify-on-new-bedrock-update: false
enable-metrics: false
bedrock:
  address: 127.0.0.1
  port: {ports['raknet_udp_and_builtin_tcp']}
  webrtc-port: {ports['nethernet_udp']}
  transport: both
  clone-remote-port: false
  signaling:
    mode: builtin
    advertise-addresses:
      - "127.0.0.1:{ports['nethernet_udp']}"
    stun-discovery: false
java:
  address: 127.0.0.1
  port: {ports['java_placeholder_tcp']}
  auth-type: offline
  resolve-srv: false
  forward-hostname: false
motd:
  primary-motd: Isolated loader smoke
  secondary-motd: No players
  passthrough-motd: false
  passthrough-player-counts: false
  integrated-ping-passthrough: false
advanced:
  resource-pack-urls: []
  cache-images: 0
  java:
    use-haproxy-protocol: false
  bedrock:
    # Preserve official Xbox/Bedrock verification defaults; no client login is attempted.
    validate-bedrock-login: true
    haproxy-protocol-whitelisted-ips: []
    signaling-trusted-proxies: []
    webrtc-proxy-protocol-relays: []
"""


def trace_state(text):
    clean = ANSI.sub("", text)
    require(BAD_LOG.search(clean) is None, "Official startup/resource/extension log contains an ERROR or exception")
    return {"extension_enabled": re.search(r"(?m)Enabled extension " + re.escape(EXTENSION_NAME) + r"[ \t]*$", clean) is not None,
            "one_extension_loaded": re.search(r"(?m)Loaded 1 extension\(s\)[ \t]*$", clean) is not None,
            "routes_ready": re.search(r"(?m)" + re.escape(READY) + r"[ \t]*$", clean) is not None,
            "native_startup_done": re.search(r"Done \([0-9.,]+s\)!", clean) is not None,
            "native_listener_started": "Started Geyser on 127.0.0.1:" in clean,
            "native_shutdown_done": "Geyser shutdown successfully." in clean}


def assert_trace(text, ports):
    state = trace_state(text)
    require(all(state.values()), "Missing real loader/startup/route/shutdown evidence: " + ",".join(key for key, value in state.items() if not value))
    clean = ANSI.sub("", text)
    require(len(re.findall(r"(?m)" + re.escape(READY) + r"[ \t]*$", clean)) == 1 and
            len(re.findall(r"(?m)Enabled extension " + re.escape(EXTENSION_NAME) + r"[ \t]*$", clean)) == 1,
            "Duplicate or ambiguous extension initialization evidence")
    endpoint = f"Started Geyser on 127.0.0.1:{ports['raknet_udp_and_builtin_tcp']}"
    require(re.search(re.escape(endpoint) + r"(?![0-9])", clean) is not None,
            "Native listener did not start on the freshly reserved loopback endpoint")
    return state


def child_environment(run):
    env = os.environ.copy()
    for key in list(env):
        if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"}:
            env.pop(key)
    for key, subdirectory in {"HOME": "home", "USERPROFILE": "home", "APPDATA": "appdata",
                              "LOCALAPPDATA": "localappdata", "XDG_CACHE_HOME": "cache", "TMP": "tmp", "TEMP": "tmp"}.items():
        target = run / subdirectory
        target.mkdir(exist_ok=True)
        env[key] = str(target)
    return env


def drain_output(process, log_path, events, overflow):
    total = 0
    try:
        with log_path.open("w", encoding="utf-8", newline="\n") as log:
            for line in process.stdout:
                total += len(line.encode("utf-8"))
                if total > MAX_LOG_BYTES:
                    overflow.set()
                    continue
                log.write(line)
                log.flush()
                try:
                    events.put_nowait(line)
                except queue.Full:
                    overflow.set()
    finally:
        try:
            events.put_nowait(None)
        except queue.Full:
            overflow.set()


def wait_startup(process, events, overflow, timeout):
    deadline = time.monotonic() + timeout
    ready_since = None
    text = ""
    while True:
        require(not overflow.is_set(), "Official output exceeded the bounded smoke log/queue limit")
        now = time.monotonic()
        if ready_since is not None and now - ready_since >= 3:
            return
        require(now < deadline, "Native Geyser startup/extension readiness timed out")
        require(process.poll() is None, "Geyser exited before stable loader/startup readiness")
        wait = min(deadline - now, 3 - (now - ready_since) if ready_since is not None else deadline - now)
        try:
            line = events.get(timeout=max(0.001, wait))
        except queue.Empty:
            continue
        require(line is not None, "Geyser output ended before stable readiness")
        text += line
        state = trace_state(text)
        if all(value for key, value in state.items() if key != "native_shutdown_done") and ready_since is None:
            ready_since = time.monotonic()


def stop_owned(process, timeout):
    """No PID discovery, process groups, port-killing or production-server shutdowns."""
    if process.poll() is not None:
        return False
    try:
        process.stdin.write("geyser stop\n")
        process.stdin.flush()
    except (BrokenPipeError, OSError):
        pass
    try:
        process.wait(timeout=timeout)
        return True
    except subprocess.TimeoutExpired:
        process.kill()  # Popen owns exactly this child PID; never taskkill /IM or a port owner.
        process.wait(timeout=10)
        return False


def run_smoke(args):
    BASE.mkdir(parents=True, exist_ok=True)
    run = BASE / uuid.uuid4().hex
    run.mkdir()
    evidence = {"kind": "native-standalone-loader-smoke", "status": "failed", "jvm_started": False,
                "fully_offline": False, "transport": "both", "limitations": LIMITATIONS,
                "run_directory": str(run.relative_to(ROOT)), "players_attempted": 0}
    process = None
    reader = None
    reservations = []
    failure = None
    try:
        jars = inspect_jars(args)
        resources = resource_plan(args)
        java = args.jdk / "bin" / ("java.exe" if os.name == "nt" else "java")
        require(java.is_file(), "Missing JDK java executable; no JVM was started")
        evidence.update({key: value for key, value in jars.items() if key not in {"geyser", "extension"}})
        evidence["public_resources"] = copy_resources(run, resources, args)
        shutil.copyfile(jars["geyser"], run / "Geyser-Standalone.jar")
        extensions = run / "extensions"
        extensions.mkdir()
        shutil.copyfile(jars["extension"], extensions / "KyokouAuthTransfer.jar")
        require(digest(run / "Geyser-Standalone.jar") == jars["geyser_sha256"] and
                digest(extensions / "KyokouAuthTransfer.jar") == jars["extension_sha256"],
                "Public artifact changed during isolated copy; refusing to execute an unverified copy")
        # Fixed GeyserExtensionLoader uses extensions/<descriptor.id>, not the old display-name path.
        data = extensions / EXTENSION_ID
        data.mkdir()
        reservations, ports = reserve_ports()
        evidence["ports"] = ports
        (data / "transfer-routes.properties").write_text(
            "enabled=true\nroute.smoke.java-host=127.0.0.1\n"
            f"route.smoke.java-port={ports['java_placeholder_tcp']}\n"
            "route.smoke.bedrock-host=127.0.0.1\n"
            f"route.smoke.bedrock-port={ports['raknet_udp_and_builtin_tcp']}\n", encoding="utf-8")
        (run / "config.yml").write_text(configuration(ports), encoding="utf-8")
        env = child_environment(run)
        command = [str(java.resolve()), "-Xms32M", "-Xmx256M", "-XX:ActiveProcessorCount=2",
                   "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                   f"-Duser.home={run / 'home'}", f"-Djava.io.tmpdir={run / 'tmp'}", "-Dterminal.jline=false",
                   "-Dterminal.ansi=false", "-Dio.netty.eventLoopThreads=2", "-DGeyser.BedrockNetworkThreads=1",
                   f"-Dsun.net.client.defaultConnectTimeout={args.resource_timeout_ms}",
                   f"-Dsun.net.client.defaultReadTimeout={args.resource_timeout_ms}",
                   "-jar", str(run / "Geyser-Standalone.jar"), "--nogui", "--config", str(run / "config.yml")]
        evidence["command"] = command
        # Keep only our Java placeholder bound. Races on the newly released Geyser ports fail closed.
        for reservation in reservations[1:]:
            reservation.close()
        process = subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        evidence.update({"jvm_started": True, "owned_pid": process.pid})
        events = queue.Queue(maxsize=2048)
        overflow = threading.Event()
        reader = threading.Thread(target=drain_output, args=(process, run / "console.log", events, overflow), daemon=True)
        reader.start()
        wait_startup(process, events, overflow, args.timeout)
    except (Exception, KeyboardInterrupt) as caught:
        failure = str(caught) or type(caught).__name__
    finally:
        if process is not None:
            try:
                evidence["graceful_stop_command_completed"] = stop_owned(process, args.shutdown_timeout)
                evidence["exit_code"] = process.returncode
            except Exception as caught:
                failure = failure or "Could not reap the owned Geyser child: " + type(caught).__name__
            if reader is not None:
                reader.join(timeout=5)
                if reader.is_alive():
                    failure = failure or "Owned stdout reader did not terminate"
            if process.stdout is not None:
                process.stdout.close()
            if process.stdin is not None:
                process.stdin.close()
        for reservation in reservations:
            reservation.close()
        if failure is None:
            try:
                require(evidence.get("graceful_stop_command_completed") and evidence.get("exit_code") == 0,
                        "Native Geyser did not exit normally after geyser stop")
                evidence["observations"] = assert_trace((run / "console.log").read_text(encoding="utf-8"), evidence["ports"])
                evidence["status"] = "passed"
            except Exception as caught:
                failure = str(caught)
        if failure is not None:
            evidence["failure"] = failure
        (run / "evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": evidence["status"], "evidence": str((run / "evidence.json").relative_to(ROOT)),
                      "jvm_started": evidence["jvm_started"], "failure": failure}, ensure_ascii=False))
    return 0 if evidence["status"] == "passed" else 1


def self_test():
    """Synthetic oracle negatives only; never written as native runtime evidence."""
    ports = {"java_placeholder_tcp": 30121, "raknet_udp_and_builtin_tcp": 30123, "nethernet_udp": 30124}
    config = configuration(ports)
    assert "validate-bedrock-login: true" in config and "validate-bedrock-login: false" not in config
    lines = [f"Enabled extension {EXTENSION_NAME}", "Loaded 1 extension(s)", READY,
             "Started Geyser on 127.0.0.1:30123", "Done (1.234s)!", "Geyser shutdown successfully."]
    assert_trace("\n".join(lines), ports)
    rejected = 0
    cases = ["\n".join(lines[:index] + lines[index + 1:]) for index in range(len(lines))]
    cases += ["\n".join(lines + [READY]), "\n".join(lines + ["[ERROR] Resource failed"]),
              "\n".join(lines + ["[ERROR] XBOX AUTHENTICATION IS DISABLED ON THIS GEYSER INSTANCE!"]),
              "\n".join(lines + ["Caused by: invalid extension metadata"]),
              "\n".join(lines).replace(":30123", ":30124"),
              "\n".join(lines).replace(":30123", ":301234"),
              "\n".join(lines).replace(READY, READY + "0"),
              "\n".join(lines).replace("Enabled extension " + EXTENSION_NAME, "Enabled extension " + EXTENSION_NAME + "Other")]
    for text in cases:
        try:
            assert_trace(text, ports)
        except SmokeFailure:
            rejected += 1
        else:
            raise AssertionError("Invalid synthetic trace was accepted")
    assert rejected == len(cases)
    print(json.dumps({"self_test": "passed", "synthetic_only": True, "jvm_started": False,
                      "negative_cases": rejected, "limitations": LIMITATIONS}))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("inspect", "self-test", "run"), help="inspect/self-test never start Java; run is for the reserved JVM window")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--geyser-jar", type=Path, default=ROOT / ".narrafork/floodgate-e2e/deps/Geyser-Standalone-2.12.1-b1303.jar")
    parser.add_argument("--extension-jar", type=Path, default=ROOT / "platform-geyser-transfer/build/libs/KyokouAuth-GeyserTransfer-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--client-assets-jar", type=Path, help="optional explicit public Minecraft client Jar; never searches launcher/private data")
    parser.add_argument("--timeout", type=int, default=90, choices=range(10, 91), metavar="10..90", help="whole native startup/readiness deadline in seconds")
    parser.add_argument("--resource-timeout-ms", type=int, default=10000, choices=range(1000, 30001), metavar="1000..30000", help="JDK URL fallback connect/read deadline; whole startup is bounded independently")
    parser.add_argument("--shutdown-timeout", type=int, default=20, choices=range(5, 31), metavar="5..30")
    args = parser.parse_args()
    if args.mode == "self-test":
        return self_test()
    if args.mode == "run":
        return run_smoke(args)
    try:
        jars = inspect_jars(args)
        resources = resource_plan(args)
        print(json.dumps({"inspection": "passed", "jvm_started": False, "public_resources": len(resources),
                          "geyser_sha256": jars["geyser_sha256"], "extension_sha256": jars["extension_sha256"],
                          "extension_descriptor": jars["extension_descriptor"], "limitations": LIMITATIONS}, ensure_ascii=False))
        return 0
    except Exception as caught:
        print(json.dumps({"inspection": "failed", "jvm_started": False, "failure": str(caught)}, ensure_ascii=False))
        return 1


if __name__ == "__main__":
    sys.exit(main())
