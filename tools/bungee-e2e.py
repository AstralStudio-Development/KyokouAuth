"""Fixed official Bungee 2111 isolation. All generated files remain in .narrafork/bungee-e2e.

prepare: download/verify the exact official distribution and API, extract its test dependencies.
startup: load the production JAR, or prove fail-closed startup with ip_forward disabled.
network: run a test-only injected-Services bootstrap (never ships in the production JAR).
         Synthetic probes prove forwarding/failure isolation, not vanilla rendering. --client uses
         the installed credential-free original 26.3 client for READY and 12-second heartbeat.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import socket
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/bungee-e2e"
BUNGEE_SHA256 = "1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd"
API = "26.1-R0.1-20261105.104341-62"
API_SHA256 = "a49ccc85acaf078c2f9cc41eac5f0f5fb614b60ae6c5995232f717c8721ffb84"
STABLE = uuid.UUID(int=2535412345678901)
REPRESENTATIVE_PROTOCOLS = {"1.21.1": 767, "1.21.4": 769, "1.21.5": 770, "26.3": 777}


def download(url, target):
    if target.is_file():
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": "http://127.0.0.1:7890", "https": "http://127.0.0.1:7890"}))
    with opener.open(url, timeout=90) as response:
        data = response.read()
    target.write_bytes(data)


def prepare():
    deps = BASE / "deps"
    deps.mkdir(parents=True, exist_ok=True)
    jar = deps / "BungeeCord-2111.jar"
    download("https://ci.md-5.net/job/BungeeCord/2111/artifact/bootstrap/target/BungeeCord.jar", jar)
    if hashlib.sha256(jar.read_bytes()).hexdigest() != BUNGEE_SHA256:
        raise RuntimeError("Official Bungee 2111 checksum mismatch")
    api = deps / f"bungeecord-api-{API}.jar"
    download(f"https://central.sonatype.com/repository/maven-snapshots/net/md-5/bungeecord-api/26.1-R0.1-SNAPSHOT/bungeecord-api-{API}.jar", api)
    if hashlib.sha256(api.read_bytes()).hexdigest() != API_SHA256:
        raise RuntimeError("Pinned Bungee API checksum mismatch")
    libraries = deps / "libraries"
    libraries.mkdir(exist_ok=True)
    hashes = {jar.name: BUNGEE_SHA256, api.name: hashlib.sha256(api.read_bytes()).hexdigest()}
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.startswith("META-INF/libraries/") and name.endswith(".jar"):
                data = archive.read(name)
                path = libraries / Path(name).name
                path.write_bytes(data)
                hashes[path.name] = hashlib.sha256(data).hexdigest()
        with zipfile.ZipFile(io.BytesIO(archive.read("META-INF/libraries/bungeecord-proxy-26.1-R0.1-SNAPSHOT.jar"))) as implementation:
            manifest = implementation.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "").replace("\n ", "")
            if "55e1b0b:2111" not in manifest:
                raise RuntimeError("Official revision/build identity differs")
    (deps / "sha256.json").write_text(json.dumps(hashes, indent=2), encoding="utf-8")
    return jar


def varint(value):
    result = bytearray()
    while True:
        item = value & 127
        value >>= 7
        result.append(item | (128 if value else 0))
        if not value:
            return bytes(result)


def read_varint(stream):
    result = 0
    for offset in range(0, 35, 7):
        data = stream.read(1)
        if not data:
            raise EOFError("connection closed")
        result |= (data[0] & 127) << offset
        if not data[0] & 128:
            return result
    raise ValueError("oversized varint")


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


def read_string(stream):
    length = read_varint(stream)
    if length > 32767:
        raise ValueError("oversized string")
    data = stream.read(length)
    if len(data) != length:
        raise EOFError()
    return data.decode("utf-8")


def frame(payload):
    return varint(len(payload)) + payload


def read_frame(stream):
    size = read_varint(stream)
    if size > 1 << 21:
        raise ValueError("oversized packet")
    data = stream.read(size)
    if len(data) != size:
        raise EOFError()
    return io.BytesIO(data)


class Recorder:
    """A deliberately non-playing backend: records actual legacy forwarding then rejects login."""
    def __init__(self, mode="kick"):
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.port = self.listener.getsockname()[1]
        self.mode = mode
        self.events = []
        self.stopped = threading.Event()
        self.connections = []
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        while not self.stopped.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            self.connections.append(connection)
            threading.Thread(target=self.handle, args=(connection,), daemon=True).start()

    def handle(self, connection):
        connection.settimeout(20)
        try:
            with connection.makefile("rb") as stream:
                packet = read_frame(stream)
                if read_varint(packet) != 0:
                    raise ValueError("expected handshake")
                protocol = read_varint(packet)
                host = read_string(packet)
                port = packet.read(2)
                intent = read_varint(packet)
                login = read_frame(stream)
                if read_varint(login) != 0:
                    raise ValueError("expected login start")
                name = read_string(login)
                login_uuid = str(uuid.UUID(bytes=login.read(16)))
                fields = host.split("\x00")
                properties = json.loads(fields[3]) if len(fields) >= 4 else []
                self.events.append({"protocol": protocol, "name": name, "forwarded_uuid": fields[2] if len(fields) >= 3 else None,
                                    "login_uuid": login_uuid, "property_names": [prop["name"] for prop in properties]})
                if self.mode == "kick":
                    connection.sendall(frame(varint(0) + string(json.dumps({"text": "ISOLATED_BACKEND_STOP"}))))
                else:
                    self.stopped.wait(12)
        except (EOFError, OSError, ValueError) as error:
            self.events.append({"error": type(error).__name__})
        finally:
            connection.close()

    def close(self):
        self.stopped.set()
        self.listener.close()
        for connection in self.connections:
            connection.close()
        self.thread.join(timeout=1)


class PaperBackend:
    """Separate plugin-free Paper process; reads only public dependencies from run-live."""
    def __init__(self, args):
        source = ROOT / "run-live"
        self.folder = BASE / "paper" / args.scenario
        self.folder.mkdir(parents=True, exist_ok=True)
        (self.folder / "tmp").mkdir(exist_ok=True)
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            self.port = listener.getsockname()[1]
        shutil.copy2(source / "paper.jar", self.folder / "paper.jar")
        for name in ("libraries", "versions"):
            shutil.copytree(source / name, self.folder / name, dirs_exist_ok=True)
        self.queried = False
        # No plugins or identity mutations: the real backend UUID is read through its native console.
        (self.folder / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        (self.folder / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={self.port}\nonline-mode=false\nenforce-secure-profile=false\nwhite-list=false\nenforce-whitelist=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n", encoding="utf-8")
        (self.folder / "spigot.yml").write_text("settings:\n  bungeecord: true\n", encoding="utf-8")
        config = self.folder / "config"
        config.mkdir(exist_ok=True)
        (config / "paper-global.yml").write_text("proxies:\n  bungee-cord:\n    online-mode: false\n  velocity:\n    enabled: false\n", encoding="utf-8")
        self.log = (self.folder / "console.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen([str(args.jdk / "bin/java.exe"), "-Xms192M", "-Xmx512M", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=" + str(self.folder / "tmp"),
            "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
            "-jar", "paper.jar", "--nogui"], cwd=self.folder, stdin=subprocess.PIPE, stdout=self.log, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + 120
        while True:
            text = (self.folder / "console.log").read_text(encoding="utf-8", errors="replace")
            if 'Done (' in text:
                break
            if self.process.poll() is not None or time.monotonic() > deadline:
                self.close()
                raise RuntimeError("Isolated Paper failed; inspect " + str(self.folder.relative_to(ROOT)))
            time.sleep(0.2)

    @property
    def events(self):
        text = (self.folder / "console.log").read_text(encoding="utf-8", errors="replace")
        text = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text)
        if not self.queried and "OracleLocal joined the game" in text:
            self.process.stdin.write("data get entity OracleLocal UUID\n")
            self.process.stdin.flush()
            self.queried = True
        for line in text.splitlines():
            if "has the following entity data" not in line:
                continue
            match = re.search(r"\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]", line)
            if match:
                value = sum((int(part) & 0xffffffff) << (96 - index * 32) for index, part in enumerate(match.groups()))
                observed = str(uuid.UUID(int=value))
                return [{"name": "OracleLocal", "forwarded_uuid": observed, "login_uuid": observed,
                         "property_names": None, "uuid_observation": "native Paper console data get entity UUID"}]
        return []

    def close(self):
        if self.process.poll() is None:
            try:
                self.process.communicate("stop\n", timeout=20)
            except subprocess.TimeoutExpired:
                self.process.terminate(); self.process.wait(timeout=5)
        self.log.close()


def inspect_artifact(artifact):
    with zipfile.ZipFile(artifact) as archive:
        names = archive.namelist()
        if "bungee.yml" not in names or "${version}" in archive.read("bungee.yml").decode():
            raise RuntimeError("Missing/unexpanded Bungee plugin descriptor")
        if any(name.startswith("net/md_5/") or "/BungeeE2ePlugin" in name for name in names):
            raise RuntimeError("Native proxy or test-only bootstrap leaked into the production artifact")
        service = "META-INF/services/moe.ryuu.kyokouauth.limbo.LimboEngineFactory"
        if names.count(service) != 1:
            raise RuntimeError("Engine ServiceLoader descriptor is missing/duplicated")
        providers = [line.strip() for line in archive.read(service).decode().splitlines() if line.strip() and not line.startswith("#")]
        if len(set(providers)) != 11:
            raise RuntimeError("Production artifact does not contain exactly eleven distinct Engine providers")
        data = archive.read("moe/ryuu/kyokouauth/bungee/KyokouAuthBungee.class")
        if struct.unpack(">H", data[6:8])[0] != 61:
            raise RuntimeError("Bungee adapter bytecode target is not Java 17")
    digest = hashlib.sha256()
    with artifact.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    evidence = {"artifact": str(artifact.relative_to(ROOT)), "bytes": artifact.stat().st_size,
                "sha256": digest.hexdigest(), "engine_providers": providers, "adapter_class_major": 61,
                "zip_entries": len(names), "native_proxy_bundled": False, "test_bootstrap_bundled": False}
    (BASE / "deps/production-artifact.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    return evidence


def setup_runtime(args, backend_port):
    jar = prepare()
    run = BASE / "runtime" / (args.scenario + ("-" + args.version if args.observer else ""))
    plugins = run / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    (run / "matrix-result.json").unlink(missing_ok=True)
    version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
    artifact = ROOT / f"platform-bungee/build/libs/KyokouAuth-Bungee-{version}.jar"
    if not artifact.is_file():
        raise RuntimeError("Build :platform-bungee:shadowJar first")
    evidence = inspect_artifact(artifact)
    (run / "source-artifact.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
    target = plugins / "KyokouAuth.jar"
    if args.command == "network":
        classes = ROOT / "platform-bungee/build/classes/kotlin/test/moe/ryuu/kyokouauth/bungee"
        fixture_classes = list(classes.glob("BungeeE2ePlugin*.class"))
        if not fixture_classes:
            raise RuntimeError("Build :platform-bungee:testClasses first")
        with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as output:
            for entry in source.infolist():
                if entry.filename != "bungee.yml":
                    output.writestr(entry, source.read(entry))
            for path in fixture_classes:
                output.write(path, "moe/ryuu/kyokouauth/bungee/" + path.name)
            output.writestr("bungee.yml", "name: KyokouAuth\nversion: e2e\nmain: moe.ryuu.kyokouauth.bungee.BungeeE2ePlugin\n")
    else:
        shutil.copy2(artifact, target)
        folder = plugins / "KyokouAuth"
        folder.mkdir(exist_ok=True)
        driver = folder / "libraries/com/h2database/h2/1.4.200/h2-1.4.200.jar"
        download("https://repo.maven.apache.org/maven2/com/h2database/h2/1.4.200/h2-1.4.200.jar", driver)
        (folder / "config.yml").write_text("database:\n  type: h2\n  database: ./plugins/KyokouAuth/isolated-db\n  auto-download-driver: false\n", encoding="utf-8")
    (run / "config.yml").write_text(f'''online_mode: false
ip_forward: {str(args.scenario != "unavailable").lower()}
network_compression_threshold: -1
connection_throttle: -1
connection_throttle_limit: 100
timeout: 15000
server_connect_timeout: 1000
log_pings: false
prevent_proxy_connections: false
listeners:
- host: 127.0.0.1:{args.port}
  motd: Isolated Bungee 2111 authentication test
  max_players: 20
  priorities:
  - backend
  force_default_server: true
  forced_hosts: {{}}
  query_enabled: false
servers:
  backend:
    address: 127.0.0.1:{backend_port}
    motd: Isolated recording backend
    restricted: false
''', encoding="utf-8")
    return jar, run


@contextmanager
def runtime(args, backend_port, engine_port=None):
    jar, run = setup_runtime(args, backend_port)
    (run / "tmp").mkdir(exist_ok=True)
    command = [str(args.jdk / "bin/java.exe"), "-Xms96M", "-Xmx512M", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=" + str(run / "tmp"),
               "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=7890", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
               "-Dkyokou.e2e.scenario=" + args.scenario]
    if engine_port is not None:
        command += ["-Dkyokou.e2e.enginePort=" + str(engine_port)]
    command += ["-jar", str(jar)]
    logpath = run / "console.log"
    with logpath.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 90
            while True:
                text = logpath.read_text(encoding="utf-8", errors="replace")
                marker = "all logins remain refused" if args.scenario == "unavailable" else (
                    "BUNGEE_E2E_STARTED" if args.command == "network" else "KyokouAuth-Bungee accepting connections")
                if marker in text and "Listening on" in text:
                    break
                if process.poll() is not None or "BUNGEE_E2E_START_FAILED" in text:
                    raise RuntimeError("Bungee startup failed: " + str(logpath.relative_to(ROOT)))
                if time.monotonic() > deadline:
                    raise RuntimeError("Bungee startup timeout: " + str(logpath.relative_to(ROOT)))
                time.sleep(0.2)
            yield run
        finally:
            if process.poll() is None:
                try:
                    process.communicate("end\n", timeout=20)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)
            (run / "process-exit.json").write_text(json.dumps({"bungee_exit_code": process.returncode}), encoding="utf-8")
            if process.returncode != 0:
                raise RuntimeError("Isolated Bungee did not shut down normally: " + str(process.returncode))


def probe(port, name="OracleLocal", timeout=10):
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as connection:
        connection.settimeout(timeout)
        claimed = uuid.uuid3(uuid.NAMESPACE_DNS, "deliberately-not-trusted")
        connection.sendall(frame(varint(0) + varint(777) + string("localhost") + struct.pack(">H", port) + varint(2)))
        connection.sendall(frame(varint(0) + string(name) + claimed.bytes))
        with connection.makefile("rb") as stream:
            try:
                while True:
                    packet = read_frame(stream)
                    packet_id = read_varint(packet)
                    if packet_id == 0:
                        return "rejected"
                    if packet_id == 2:
                        connection.sendall(frame(varint(3)))
            except socket.timeout as error:
                raise RuntimeError("Frontend did not reject/close before the bounded probe deadline") from error
            except (EOFError, OSError):
                return "closed"


def vanilla(args, run, backend, reconnect=False):
    # Only version/library metadata and assets are read from the installation. No launcher profile,
    # account, access token, world or options from the real installation is read.
    spec = importlib.util.spec_from_file_location("limbo_client_rules", ROOT / "tools/limbo-e2e.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    metadata = json.loads((args.minecraft / "versions/26.3/26.3.json").read_text(encoding="utf-8"))
    folder = BASE / "client" / args.scenario / ("reconnect" if reconnect else "first")
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "tmp").mkdir(exist_ok=True)
    (folder / "options.txt").write_text("onboardAccessibility:false\nskipMultiplayerWarning:true\n", encoding="utf-8")
    features = {"is_quick_play_multiplayer": True, "has_custom_resolution": True}
    jars = []
    for library in metadata["libraries"]:
        if helper.allowed(library.get("rules", []), features):
            item = library.get("downloads", {}).get("artifact")
            if item:
                jars.append(str(args.minecraft / "libraries" / item["path"]))
    jars.append(str(args.minecraft / "versions/26.3/26.3.jar"))
    for part in ("java", "jna", "lwjgl", "netty"):
        (folder / "natives" / part).mkdir(parents=True, exist_ok=True)
    name = "OracleLocal"
    player_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)
    variables = {"auth_player_name": name, "version_name": "26.3", "game_directory": str(folder),
                 "assets_root": str(args.minecraft / "assets"), "assets_index_name": metadata["assetIndex"]["id"],
                 "auth_uuid": player_uuid.hex, "auth_access_token": "0", "clientid": "", "auth_xuid": "0", "version_type": "release",
                 "resolution_width": "854", "resolution_height": "480", "quickPlayMultiplayer": f"127.0.0.1:{args.port}",
                 "natives_directory": str(folder / "natives"), "launcher_name": "KyokouAuth-Bungee-Isolation", "launcher_version": "1",
                 "classpath": os.pathsep.join(jars)}
    def expand(items):
        result = []
        for item in items:
            values = [item] if isinstance(item, str) else item["value"] if helper.allowed(item["rules"], features) else []
            for value in ([values] if isinstance(values, str) else values):
                result.append(re.sub(r"\$\{([^}]+)\}", lambda m: variables[m[1]], value))
        return result
    command = [str(args.jdk / "bin/java.exe"), "-Xms192M", "-Xmx768M", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=" + str(folder / "tmp"),
               "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
               *expand(metadata["arguments"]["jvm"]), metadata["mainClass"], *expand(metadata["arguments"]["game"])]
    logpath = run / "console.log"
    offset = logpath.stat().st_size
    ready_at = None
    with (folder / "console.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + args.timeout
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Original 26.3 client exited; inspect isolated client log")
                with logpath.open("r", encoding="utf-8", errors="replace") as source:
                    source.seek(offset)
                    text = source.read()
                if args.scenario in ("stable", "first-bind", "trusted") or reconnect:
                    if args.scenario == "first-bind" and not reconnect:
                        if "BUNGEE_E2E_SESSION" in text and "BUNGEE_E2E_LEFT OracleLocal" in text:
                            return
                    elif backend.events:
                        return
                elif "BUNGEE_E2E_READY OracleLocal" in text:
                    if ready_at is None:
                        ready_at = time.monotonic()
                    if "BUNGEE_E2E_LEFT OracleLocal" in text:
                        if args.scenario == "server" and "BUNGEE_E2E_UNAUTHORIZED_SERVER_ATTEMPT" in text:
                            return
                        raise RuntimeError("Original client left after READY")
                    if args.scenario != "server" and time.monotonic() - ready_at >= 12:
                        if text.count("BUNGEE_E2E_TITLE OracleLocal") != 1:
                            raise RuntimeError("Title did not remain single-shot")
                        client_log = folder / "logs/latest.log"
                        client_text = client_log.read_text(encoding="utf-8", errors="replace")
                        if "microsoft.com/link" not in client_text:
                            raise RuntimeError("Original client did not log clickable verification message")
                        return
                time.sleep(0.3)
            raise RuntimeError("Original client scenario timed out; inspect " + str(folder.relative_to(ROOT)))
        finally:
            process.terminate()
            try:
                process.wait(timeout=8)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["prepare", "startup", "network"])
    parser.add_argument("--scenario", default="production", choices=["production", "unavailable", "held", "trusted", "first-bind", "stable", "server", "tcp-refused", "login-kick", "join-timeout"])
    parser.add_argument("--port", type=int, default=25679)
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--minecraft", type=Path, default=Path(os.environ.get("APPDATA", ".")) / ".minecraft")
    parser.add_argument("--client", action="store_true")
    parser.add_argument("--version", default="26.3", choices=["1.21.1", "1.21.4", "1.21.5", "26.3"])
    parser.add_argument("--observer", action="store_true", help="Use the shared read-only original-client screen/level/player observer (held matrix)")
    parser.add_argument("--paper", action="store_true", help="Use a separate plugin-free Paper backend and read UUID through its native console")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if args.command == "prepare":
        prepare(); print("PASS fixed Bungee 2111 / 55e1b0b and pinned API prepared"); return
    if args.command == "network" and args.scenario in ("production", "unavailable"):
        parser.error("network requires an injected-Services scenario")
    if args.observer and (not args.client or args.scenario != "held"):
        parser.error("--observer requires --client --scenario held")
    if args.version != "26.3" and not args.observer:
        parser.error("the representative old-client matrix requires --observer")
    if args.paper and (not args.client or args.scenario not in ("stable", "first-bind", "trusted")):
        parser.error("--paper requires --client and a stable/first-bind/trusted identity scenario")
    backend = PaperBackend(args) if args.paper else Recorder()
    engine = Recorder("stall" if args.scenario == "join-timeout" else "kick") if args.scenario in ("login-kick", "join-timeout") else None
    try:
        override = 9 if args.scenario == "tcp-refused" else engine.port if engine else None
        with runtime(args, backend.port, override) as run:
            if args.command == "startup":
                if args.scenario == "unavailable":
                    if probe(args.port) != "rejected":
                        raise RuntimeError("Failed startup did not reject LOGIN")
                result = "production startup/shutdown" if args.scenario != "unavailable" else "ip_forward failure remains closed"
            elif args.client:
                if args.observer:
                    subprocess.run([sys.executable, str(ROOT / "tools/limbo-e2e.py"), "--version", args.version,
                        "--external-proxy", "--proxy-log", str(run / "console.log"), "--port", str(args.port), "--observer",
                        "--ready-marker", "BUNGEE_E2E_READY {name}", "--expect-verification", "--hold-seconds", "12",
                        "--name", "OracleLocal", "--jdk", str(args.jdk), "--minecraft", str(args.minecraft), "--timeout", str(args.timeout)], check=True)
                    text = (run / "console.log").read_text(encoding="utf-8", errors="replace")
                    if text.count("BUNGEE_E2E_TITLE OracleLocal") != 1:
                        raise RuntimeError("Observer matrix did not retain a single title")
                    client_log = ROOT / ".narrafork/multiversion/e2e-clients" / (args.version + "-" + str(args.port)) / "logs/latest.log"
                    if "https://microsoft.com/link?otc=E2E-CODE" not in client_log.read_text(encoding="utf-8", errors="replace"):
                        raise RuntimeError("Original client did not receive the complete test verification URL")
                    if not re.search(r"Limbo ready for OracleLocal .*protocol " + str(REPRESENTATIVE_PROTOCOLS[args.version]), text):
                        raise RuntimeError("Native READY did not match the requested original-client protocol")
                else:
                    vanilla(args, run, backend)
                    if args.scenario == "first-bind":
                        time.sleep(0.5)
                        vanilla(args, run, backend, reconnect=True)
                result = "original " + args.version + " client " + args.scenario
            else:
                if args.scenario not in ("trusted", "tcp-refused", "login-kick", "join-timeout"):
                    parser.error("this scenario requires --client; synthetic packets cannot prove Limbo READY")
                probe(args.port, timeout=14)
                result = "synthetic native event/forwarding " + args.scenario
            if args.scenario in ("trusted", "stable", "first-bind"):
                deadline = time.monotonic() + 8
                while not backend.events and time.monotonic() < deadline:
                    time.sleep(0.1)
                if not backend.events or "error" in backend.events[0]:
                    raise RuntimeError("No real backend legacy-forwarding handshake was captured")
                for event in backend.events:
                    if uuid.UUID(event["forwarded_uuid"]) != STABLE or uuid.UUID(event["login_uuid"]) != STABLE:
                        raise RuntimeError("Stable backend identity differs from core-approved identity")
                    if "kyokouauth:private-ticket" in (event["property_names"] or []):
                        raise RuntimeError("Private ticket leaked to a real backend")
            elif backend.events:
                raise RuntimeError("Unauthenticated frontend reached the real backend")
            (run / "backend-events.json").write_text(json.dumps(backend.events, indent=2), encoding="utf-8")
            if args.observer:
                source = json.loads((run / "source-artifact.json").read_text(encoding="utf-8"))
                (run / "matrix-result.json").write_text(json.dumps({"version": args.version,
                    "protocol": REPRESENTATIVE_PROTOCOLS[args.version], "source_artifact_sha256": source["sha256"],
                    "trusted_gate_ready": True, "readonly_world_player_present_screen_null": True,
                    "connected_after_ready_seconds": 12, "complete_test_url_received": True,
                    "title_count": 1, "backend_handshakes": 0}, indent=2), encoding="utf-8")
            detail = ("real Paper UUID confirmed via native console; properties not inspected" if args.paper else
                      "forwarded UUID and private-ticket removal confirmed" if args.scenario in ("trusted", "stable", "first-bind") else
                      "recording backend received zero frontend handshakes")
            print("PASS " + result + "; " + detail)
    finally:
        backend.close()
        if engine:
            engine.close()


if __name__ == "__main__":
    main()
