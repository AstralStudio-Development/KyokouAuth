"""Isolated official Floodgate 2.2.5 b141 crypto -> native proxy -> shared gate regression.

The client reuses Floodgate's official AES/BedrockData implementation and the complete upstream
MCProtocolLib protocol-777 client. No channel attributes are injected. Each proxy must run its
actual Floodgate HandshakeHandler and create its own channel proof. Only a fresh isolated
Floodgate-generated key under .narrafork/floodgate-e2e is read; no run-* key, launcher data,
Microsoft device code, real Xbox account or production database is used.

This verifies the trusted Geyser-side Java transport, NOT a complete Geyser/real Bedrock login.
Run this regression separately from other JVM-based suites. `prepare` downloads only;
`run` compiles the test-only Java probe, then runs one 128 MiB proxy and one 64 MiB client at a time.
All generated metadata, keys, classes, configs, logs and evidence remain under .narrafork.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/floodgate-e2e"
XUID = 2535412345678901
EXPECTED_UUID = uuid.UUID(int=XUID)
EXPECTED_NAME = ".CryptoBedrock"
FLOODGATE_SHA256 = {
    "velocity": "649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d",
    "bungee": "ba79b19048e1255201ed886af5b437038d428e6e673d9756a8704235473c1544",
}
BUNGEE_SHA256 = "1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd"
SCENARIOS = {
    "valid": "bedrock",
    "wrongkey": "bedrock",
    "forgedUUIDOnly": "bedrock",
    "linked": "bedrock",
    "nativeProfileMismatch": "bedrock-profile-uuid",
    "nativeNameMismatch": "bedrock-profile-name",
    "providerDisabled": "bedrock-disabled",
    "providerStale": "bedrock-provider-stale",
}
MAIN_CLASS = "moe.ryuu.kyokouauth.tools.floodgate.FloodgateE2eCrypto"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(url, target, expected, proxy):
    if target.is_file():
        if digest(target) != expected:
            raise RuntimeError(f"Pinned official checksum mismatch: {target}")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    handlers = {} if proxy == "none" else {"http": proxy, "https": proxy}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler(handlers))
    request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with opener.open(request, timeout=90) as response:
        data = response.read(128 * 1024 * 1024 + 1)
    if len(data) > 128 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
        raise RuntimeError(f"Pinned official download checksum/size mismatch: {target.name}")
    with target.open("xb") as output:
        output.write(data)
    return target


def prepare(platforms, proxy):
    jars = {}
    for platform in platforms:
        jars[platform] = download(
            f"https://download.geysermc.org/v2/projects/floodgate/versions/2.2.5/builds/141/downloads/{platform}",
            BASE / "deps" / f"floodgate-{platform}-2.2.5-b141.jar", FLOODGATE_SHA256[platform], proxy)
    if "bungee" in platforms:
        target = BASE / "deps/BungeeCord-2111.jar"
        cached = ROOT / ".narrafork/bungee-e2e/deps/BungeeCord-2111.jar"
        if not target.exists() and cached.is_file() and digest(cached) == BUNGEE_SHA256:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(cached, target)
        download("https://ci.md-5.net/job/BungeeCord/2111/artifact/bootstrap/target/BungeeCord.jar",
                 target, BUNGEE_SHA256, proxy)
    return jars


def fixtures():
    # Reuse the existing bounded, non-playing recording backend. Do not introduce another codec.
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("floodgate_bungee_fixtures", ROOT / "tools/bungee-e2e.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def executable(jdk, name):
    path = jdk / "bin" / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise RuntimeError(f"Missing JDK executable: {path}")
    return str(path.resolve())


def client_classpath(path):
    if not path.is_file():
        raise RuntimeError("Build :limbo-protocol-777:generateLimboCodec first, or provide --client-classpath-file")
    entries = path.read_text(encoding="utf-8").strip().split(os.pathsep)
    if not entries or any(not Path(entry).is_file() for entry in entries):
        raise RuntimeError("The complete upstream MCProtocolLib tool classpath contains missing files")
    if not any(Path(entry).name == "protocol-26.3-4.jar" for entry in entries):
        raise RuntimeError("The native crypto fixture is pinned to the existing protocol-26.3-4 client")
    return os.pathsep.join(entries)


def compile_probe(args, platform, floodgate, upstream):
    classes = BASE / "probe-classes" / platform
    classes.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join((upstream, str(floodgate.resolve())))
    command = [executable(args.jdk, "javac"), "-J-Xmx128M", "-J-XX:ActiveProcessorCount=2",
               "-encoding", "UTF-8", "-cp", classpath, "-d", str(classes.resolve()),
               str(ROOT / "tools/FloodgateE2eCrypto.java")]
    subprocess.run(command, cwd=BASE, check=True, timeout=90)
    return os.pathsep.join((str(classes.resolve()), classpath))


def backend_codec(args, classpath):
    """Pre-encode only B valid's two backend packets with the same complete upstream SDK."""
    directory = BASE / "backend-codec" / uuid.uuid4().hex[:12]
    directory.mkdir(parents=True)
    (directory / "tmp").mkdir()
    command = [executable(args.jdk, "java"), "-Xms16M", "-Xmx64M", "-XX:ActiveProcessorCount=2",
               f"-Djava.io.tmpdir={directory / 'tmp'}", f"-Dkyokou.floodgate.isolatedRoot={BASE.resolve()}",
               "-cp", classpath, MAIN_CLASS, "--encode-backend", str(directory.resolve())]
    result = subprocess.run(command, cwd=BASE, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=30)
    (directory / "codec.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0:
        raise RuntimeError(f"Native backend codec failed; inspect {(directory / 'codec.log').relative_to(ROOT)}")
    metadata = json.loads((directory / "codec.json").read_text(encoding="utf-8"))
    if (metadata.get("protocol") != 777 or metadata.get("sdk") != "MCProtocolLib-26.3-4" or
            metadata.get("uuid") != str(EXPECTED_UUID) or metadata.get("name") != EXPECTED_NAME):
        raise AssertionError("The backend codec did not encode the fixed native identity/protocol")
    return {"success": (directory / "login-success.bin").read_bytes(),
            "disconnect": (directory / "configuration-disconnect.bin").read_bytes(),
            "acknowledgement": metadata["loginAcknowledgedId"]}


def acknowledging_recorder(module, packets):
    """Retain the existing handshake recorder; replace its single kick with SDK native packets."""
    class NativeLoginRecorder(module.Recorder):
        def handle(self, connection):
            recorder = self

            class NativeLoginSocket:
                stream = None

                def __getattr__(self, name):
                    return getattr(connection, name)

                def makefile(self, *args, **kwargs):
                    self.stream = connection.makefile(*args, **kwargs)
                    return self.stream

                def sendall(self, _negative_fixture_kick):
                    # ServerConnector.cutThrough sends the first frontend LoginSuccess only now.
                    connection.sendall(packets["success"])
                    acknowledgement = module.read_frame(self.stream)
                    if (module.read_varint(acknowledgement) != packets["acknowledgement"] or
                            acknowledgement.read(1)):
                        raise ValueError("Expected the real Bungee LoginAcknowledged before configuration")
                    recorder.events[-1].update(backendLoginAcknowledged=True,
                                               backendSuccessCodec="MCProtocolLib-26.3-4")
                    connection.sendall(packets["disconnect"])
                    connection.shutdown(socket.SHUT_WR)
                    connection.settimeout(3)
                    try:
                        while self.stream.read(4096):
                            pass
                    except socket.timeout:
                        pass

            super().handle(NativeLoginSocket())

    return NativeLoginRecorder()


def test_jar(platform, artifact):
    if not artifact.is_file():
        raise RuntimeError(f"Build the platform shadowJar before the isolated crypto run: {artifact}")
    package = f"moe/ryuu/kyokouauth/{platform}"
    bootstrap = "VelocityE2ePlugin" if platform == "velocity" else "BungeeE2ePlugin"
    directory = ROOT / f"platform-{platform}/build/classes/kotlin/test" / package
    entries = sorted(directory.glob(("VelocityE2ePlugin" if platform == "velocity" else "BungeeE2e") + "*.class"))
    if not entries:
        raise RuntimeError(f"Build :platform-{platform}:compileTestKotlin first")
    target = BASE / "deps" / f"gate-test-{platform}.jar"
    metadata = "velocity-plugin.json" if platform == "velocity" else "bungee.yml"
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as output:
        if any(bootstrap in name for name in source.namelist()):
            raise RuntimeError("The production artifact must not contain the test bootstrap")
        for info in source.infolist():
            if info.filename != metadata:
                output.writestr(info.filename, source.read(info.filename))
        if platform == "velocity":
            descriptor = json.loads(source.read(metadata))
            descriptor.update(id="kyokou-floodgate-e2e", name="Kyokou Floodgate E2E", version="isolated",
                              main=f"moe.ryuu.kyokouauth.velocity.{bootstrap}")
            dependencies = [item for item in descriptor.get("dependencies", []) if item.get("id") != "floodgate"]
            descriptor["dependencies"] = dependencies + [{"id": "floodgate", "optional": True}]
            output.writestr(metadata, json.dumps(descriptor))
        else:
            output.writestr(metadata, "name: KyokouAuth\nversion: isolated\nmain: moe.ryuu.kyokouauth.bungee.BungeeE2ePlugin\nsoftDepends: [floodgate]\n")
        for path in entries:
            output.writestr(package + "/" + path.name, path.read_bytes())
    return target


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def configure(run, platform, port, backend_port):
    plugins = run / "plugins"
    floodgate = plugins / "floodgate"
    floodgate.mkdir(parents=True)
    (run / "tmp").mkdir()
    # Floodgate itself generates key.pem on its first initialization. Never copy/read run-* keys.
    (floodgate / "config.yml").write_text(f'''key-file-name: key.pem
username-prefix: "."
replace-spaces: true
default-locale: en_US
send-floodgate-data: false
disconnect:
  invalid-key: ISOLATED_FLOODGATE_INVALID_KEY
  invalid-arguments-length: ISOLATED_FLOODGATE_INVALID_DATA
player-link:
  enabled: false
  require-link: false
  enable-own-linking: false
  allowed: false
  link-code-timeout: 300
  type: sqlite
  enable-global-linking: false
metrics:
  enabled: false
  uuid: {uuid.uuid4()}
debug: false
config-version: 3
''', encoding="utf-8")
    if platform == "velocity":
        (run / "velocity.toml").write_text(f'''config-version = "2.9"
bind = "127.0.0.1:{port}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
backend = "127.0.0.1:{backend_port}"
try = ["backend"]
[forced-hosts]
"lobby.example.com" = ["backend"]
"factions.example.com" = ["backend"]
"minigames.example.com" = ["backend"]
[advanced]
login-ratelimit = 0
connection-timeout = 1000
read-timeout = 10000
''', encoding="utf-8")
        (run / "forwarding.secret").write_text("isolated-forwarding-fixture-not-a-production-credential", encoding="utf-8")
    else:
        (run / "config.yml").write_text(f'''online_mode: false
ip_forward: true
network_compression_threshold: -1
connection_throttle: -1
connection_throttle_limit: 100
timeout: 15000
server_connect_timeout: 1000
log_pings: false
prevent_proxy_connections: false
listeners:
- host: 127.0.0.1:{port}
  motd: Isolated official Floodgate crypto fixture
  max_players: 20
  priorities: [backend]
  force_default_server: true
  forced_hosts: {{}}
  query_enabled: false
servers:
  backend:
    address: 127.0.0.1:{backend_port}
    motd: Isolated recording backend
    restricted: false
''', encoding="utf-8")


def wait_started(process, console, platform, timeout):
    prefix = platform.upper()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
        if f"{prefix}_E2E_STARTED bedrock" in text:
            return
        if process.poll() is not None or f"{prefix}_E2E_START_FAILED" in text:
            raise RuntimeError(f"Isolated native startup failed; inspect {console.relative_to(ROOT)}")
        time.sleep(0.2)
    raise TimeoutError(f"Isolated native startup timed out; inspect {console.relative_to(ROOT)}")


@contextmanager
def runtime(args, run, platform, proxy, scenario):
    console = run / "console.log"
    # Authentication stays credential-free. Disabled global linking needs no external service.
    command = [executable(args.jdk, "java"), "-Xms64M", "-Xmx128M", "-XX:ActiveProcessorCount=2",
               f"-Djava.io.tmpdir={run / 'tmp'}", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
               "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
               f"-Dkyokou.e2e.scenario={scenario}", f"-Dkyokou.e2e.bedrockXuid={XUID}",
               "-jar", str(proxy.resolve())]
    with console.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output,
                                   stderr=subprocess.STDOUT, text=True)
        try:
            wait_started(process, console, platform, args.timeout)
            key = run / "plugins/floodgate/key.pem"
            if not key.is_file() or not key.resolve().is_relative_to(run.resolve()):
                raise RuntimeError("The isolated official Floodgate did not generate its own local key")
            yield console
        finally:
            if process.poll() is None:
                try:
                    process.communicate("end\n", timeout=25)
                except (BrokenPipeError, subprocess.TimeoutExpired):
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            (run / "process-exit.json").write_text(json.dumps({"exitCode": process.returncode}), encoding="utf-8")
            if process.returncode != 0 and sys.exc_info()[0] is None:
                raise RuntimeError(f"Isolated proxy shutdown was not normal: {process.returncode}")


def assert_result(platform, test_case, probe, text, events):
    prefix = platform.upper() + "_E2E_BEDROCK_"
    if prefix + "FORBIDDEN" in text:
        raise AssertionError("The Bedrock fixture entered a forbidden Java/write/device path or broke a native invariant")
    if not probe.get("remoteDisconnect"):
        raise AssertionError("A transport error is not a proven authentication refusal")
    if test_case == "valid":
        marker = prefix + f"AUTHENTICATED uuid={EXPECTED_UUID} name={EXPECTED_NAME} javaWrites=0"
        if marker not in text or not probe.get("loginFinished"):
            raise AssertionError("The actual native/shared gate did not authenticate the encrypted XUID identity")
        if probe.get("uuid") != str(EXPECTED_UUID) or probe.get("name") != EXPECTED_NAME:
            raise AssertionError("The native client profile differs from the proven XUID/name")
        if len(events) != 1 or events[0].get("name") != EXPECTED_NAME:
            raise AssertionError("Exactly one recording-backend login with the native final name is required")
        event = events[0]
        if uuid.UUID(event["login_uuid"]) != EXPECTED_UUID:
            raise AssertionError("The recording backend received an unstable or provisional UUID")
        if platform == "bungee":
            if uuid.UUID(event["forwarded_uuid"]) != EXPECTED_UUID:
                raise AssertionError("Bungee legacy forwarding differs from the proven native identity")
            if not event.get("backendLoginAcknowledged") or event.get("backendSuccessCodec") != "MCProtocolLib-26.3-4":
                raise AssertionError("Bungee did not acknowledge the real SDK-encoded backend LoginSuccess")
        if any(name.lower().startswith("kyokouauth") for name in event.get("property_names", [])):
            raise AssertionError("A private Limbo ticket leaked into the real backend handshake")
    else:
        if events or probe.get("loginFinished") or prefix + "AUTHENTICATED" in text:
            raise AssertionError("A rejected crypto/native fixture reached native success or the recording backend")
        if test_case == "wrongkey" and "ISOLATED_FLOODGATE_INVALID_KEY" not in probe.get("disconnectReason", ""):
            raise AssertionError("The wrong key was not rejected by the actual Floodgate handshake decoder")
        expected_kinds = {"linked": ("LINKED",), "providerDisabled": ("DISABLED",),
                          "providerStale": ("UNAVAILABLE", "STALE")}.get(test_case, ())
        if expected_kinds and not any(f"kind={kind}" in text for kind in expected_kinds):
            raise AssertionError(f"The expected native probe refusal was not observed: {expected_kinds}")


def run_case(args, platform, test_case, proxy, floodgate, gate_jar, classpath, module, packets=None):
    run = BASE / "runtime" / f"{platform}-{test_case}-{uuid.uuid4().hex[:12]}"
    run.mkdir(parents=True)
    backend = acknowledging_recorder(module, packets) if platform == "bungee" and test_case == "valid" else module.Recorder()
    port = free_port()
    evidence = {"platform": platform, "case": test_case, "floodgateVersion": "2.2.5-b141",
                "floodgateSha256": FLOODGATE_SHA256[platform], "proxySha256": digest(proxy),
                "officialNativeCryptoPathVerified": False, "realGeyserBedrockClientVerified": False,
                "limitation": "Controlled trusted Geyser-side Java transport; no real Bedrock/Xbox login"}
    try:
        configure(run, platform, port, backend.port)
        (run / "isolated-fixture.json").write_text(json.dumps({"newIsolatedFixture": True, "platform": platform}), encoding="utf-8")
        shutil.copyfile(gate_jar, run / "plugins/gate-test.jar")
        shutil.copyfile(floodgate, run / "plugins/floodgate.jar")
        with runtime(args, run, platform, proxy, SCENARIOS[test_case]) as console:
            command = [executable(args.jdk, "java"), "-Xms32M", "-Xmx64M", "-XX:ActiveProcessorCount=2",
                       f"-Djava.io.tmpdir={run / 'tmp'}", f"-Dkyokou.floodgate.isolatedRoot={BASE.resolve()}",
                       "-cp", classpath, MAIN_CLASS, str(run.resolve()), str(port), test_case]
            result = subprocess.run(command, cwd=run, capture_output=True, text=True, encoding="utf-8",
                                    errors="replace", timeout=40)
            (run / "client.log").write_text(result.stdout + result.stderr, encoding="utf-8")
            if result.returncode != 0:
                raise RuntimeError(f"Crypto client failed; inspect {(run / 'client.log').relative_to(ROOT)}")
            lines = [line.removeprefix("FLOODGATE_CRYPTO_RESULT ") for line in result.stdout.splitlines()
                     if line.startswith("FLOODGATE_CRYPTO_RESULT ")]
            if len(lines) != 1:
                raise AssertionError("The credential-free MCProtocolLib crypto probe produced no unique result")
            probe = json.loads(lines[0])
            text = console.read_text(encoding="utf-8", errors="replace")
            assert_result(platform, test_case, probe, text, backend.events)
            evidence.update(status="passed", client=probe,
                            officialNativeCryptoPathVerified=test_case != "forgedUUIDOnly")
        print(f"PASS {platform} Floodgate {test_case}: official native crypto/gate; backend logins={len(backend.events)}")
        return evidence
    except Exception as error:
        evidence.update(status="failed", failure=type(error).__name__)
        raise
    finally:
        backend.close()
        evidence["backendEvents"] = backend.events
        (run / "result.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "run"), help="prepare never starts Java; run requires a reserved JVM window")
    parser.add_argument("--platform", choices=("velocity", "bungee", "both"), default="both")
    parser.add_argument("--case", action="append", choices=tuple(SCENARIOS), help="Repeat to select bounded cases; defaults to all")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--velocity-proxy", type=Path, default=ROOT / "run-proxy/velocity.jar")
    parser.add_argument("--velocity-jar", type=Path, default=ROOT / "platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--bungee-jar", type=Path, default=ROOT / "platform-bungee/build/libs/KyokouAuth-Bungee-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--client-classpath-file", type=Path, default=ROOT / "limbo/protocols/777/build/generated/limbo-codec/tool-classpath.txt")
    parser.add_argument("--http-proxy", default="http://127.0.0.1:7890", help="Pinned download proxy, or 'none' for direct HTTPS")
    parser.add_argument("--timeout", type=int, default=90, help="Positive bounded proxy startup timeout")
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("--timeout must be between 1 and 300 seconds")
    platforms = ("velocity", "bungee") if args.platform == "both" else (args.platform,)
    floodgate = prepare(platforms, args.http_proxy)
    if args.command == "prepare":
        print("Prepared and SHA256-verified official Floodgate 2.2.5 b141; no Java process started")
        return
    upstream = client_classpath(args.client_classpath_file)
    module = fixtures()
    cases = args.case or tuple(SCENARIOS)
    results = []
    for platform in platforms:
        proxy = args.velocity_proxy if platform == "velocity" else BASE / "deps/BungeeCord-2111.jar"
        if not proxy.is_file():
            parser.error(f"Missing fixed native proxy distribution: {proxy}")
        artifact = args.velocity_jar if platform == "velocity" else args.bungee_jar
        gate_jar = test_jar(platform, artifact)
        classpath = compile_probe(args, platform, floodgate[platform], upstream)
        packets = backend_codec(args, classpath) if platform == "bungee" and "valid" in cases else None
        for test_case in cases:
            results.append(run_case(args, platform, test_case, proxy, floodgate[platform], gate_jar, classpath, module, packets))
    (BASE / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("Real Bedrock/Xbox client layer: NOT VERIFIED (no real account authorization requested)")


if __name__ == "__main__":
    main()
