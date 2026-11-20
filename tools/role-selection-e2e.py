"""Protocol-777 native Floodgate role selection, ordinary LOGIN only, no Transfer/cookies.

prepare never starts Java or downloads. run is reserved for the primary narrator's serial JVM
window: one 128MiB proxy + one 64MiB recording backend + one 64MiB SDK client. Its javac finishes
before servers start. Every case owns a fresh loopback fixture/key/config under .narrafork.
Microsoft/linkage/texture/canary inputs are synthetic; official Floodgate cipher, handler, native
platform events and complete SDK codecs are real. This is not Xbox/phone/render/Paper acceptance.
Java advanced cross-role selection is unsupported this round: use existing velocity-e2e.py and
bungee-e2e.py for their unchanged Java basic handshakes, not these Floodgate results.
"""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/role-selection-e2e"
MAIN = "moe.ryuu.kyokouauth.tools.roles.RoleSelectionClient"
BOOTSTRAP = "RoleSelectionE2ePlugin"
CASES = ("floodgate-j", "floodgate-u", "floodgate-single")
XUID = 2535412345678901
U = str(uuid.UUID(int=XUID))
J = "12345678-1234-4234-9234-123456789abc"
FLOODGATE_SHA256 = {"velocity": "649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d",
                    "bungee": "ba79b19048e1255201ed886af5b437038d428e6e673d9756a8704235473c1544"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(value, message):
    if not value:
        raise AssertionError(message)


def official_helpers():
    spec = importlib.util.spec_from_file_location("role_official_floodgate_fixtures", ROOT / "tools/floodgate-e2e.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(args):
    helper = official_helpers()
    upstream = helper.client_classpath(args.client_classpath_file)
    floodgate_sha = FLOODGATE_SHA256[args.platform]
    require(args.floodgate.is_file() and digest(args.floodgate) == floodgate_sha,
            "Missing/checksum-mismatched cached official platform-specific Floodgate 2.2.5 b141 JAR")
    BASE.mkdir(parents=True, exist_ok=True)
    deps = BASE / "deps"
    deps.mkdir(exist_ok=True)
    cached = deps / f"floodgate-{args.platform}-2.2.5-b141.jar"
    if not cached.exists():
        shutil.copyfile(args.floodgate, cached)
    require(digest(cached) == floodgate_sha, "Isolated Floodgate checksum differs")
    registry = ROOT / "limbo/protocols/777/src/main/resources/limbo/role-selector-777.properties"
    props = dict(line.split("=", 1) for line in registry.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
    require(props.get("protocol") == "777" and props.get("source") == "official-server-bootstrap-built-in-registries", "Selector registry provenance differs")
    configuration = ROOT / "limbo/protocols/777/src/main/resources/limbo/configuration-777.bin"
    provenance = dict(line.split("=", 1) for line in configuration.with_suffix(".properties").read_text(encoding="utf-8").splitlines() if "=" in line)
    require(provenance.get("protocol") == "777" and provenance.get("source") == "official-offline-server-empty-known-packs"
            and digest(configuration) == provenance.get("sha256"), "Final PLAY official configuration provenance differs")
    metadata = {"protocol": 777, "platform": args.platform, "sdk": "MCProtocolLib-26.3-4", "floodgateSha256": floodgate_sha,
                "nameTagId": int(props["name-tag-id"]), "diamondId": int(props["diamond-id"]),
                "registrySha256": digest(registry), "clientSourceSha256": digest(ROOT / "tools/RoleSelectionClient.java"),
                "configurationSha256": provenance["sha256"], "networkDownloads": False, "floodgateMessages": True}
    (BASE / "prepared.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return helper, upstream, cached, metadata


def test_jar(artifact, platform):
    require(artifact.is_file(), f"Build :platform-{platform}:shadowJar first")
    package = f"moe/ryuu/kyokouauth/{platform}"
    bootstrap = BOOTSTRAP if platform == "velocity" else "BungeeRoleSelectionE2ePlugin"
    entries = sorted((ROOT / f"platform-{platform}/build/classes/kotlin/test" / package).glob(bootstrap + "*.class"))
    require(entries, f"Build :platform-{platform}:compileTestKotlin first")
    target = BASE / "deps" / f"role-gate-{platform}-no-transfer.jar"
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as output:
        require(not any("E2ePlugin" in name for name in source.namelist()), "Production artifact contains a test bootstrap")
        descriptor_name = "velocity-plugin.json" if platform == "velocity" else "bungee.yml"
        for info in source.infolist():
            if info.filename != descriptor_name: output.writestr(info.filename, source.read(info.filename))
        if platform == "velocity":
            descriptor = json.loads(source.read(descriptor_name))
            descriptor.update(id="kyokou-role-selection-e2e", name="Isolated Role Selection E2E", version="isolated",
                              main=f"moe.ryuu.kyokouauth.velocity.{bootstrap}")
            descriptor["dependencies"] = [{"id": "floodgate", "optional": False}]
            output.writestr(descriptor_name, json.dumps(descriptor))
        else:
            output.writestr(descriptor_name, "name: KyokouRoleSelectionE2e\nversion: isolated\nmain: moe.ryuu.kyokouauth.bungee.BungeeRoleSelectionE2ePlugin\ndepends: [floodgate]\n")
        for path in entries: output.writestr(package + "/" + path.name, path.read_bytes())
    return target


def compile_client(args, helper, upstream, floodgate):
    classes = BASE / "client-classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join((upstream, str(floodgate.resolve())))
    subprocess.run([helper.executable(args.jdk, "javac"), "-J-Xmx128M", "-J-XX:ActiveProcessorCount=2", "-proc:none", "-encoding", "UTF-8",
                    "-cp", classpath, "-d", str(classes.resolve()), str(ROOT / "tools/RoleSelectionClient.java")], cwd=BASE, check=True, timeout=90)
    return os.pathsep.join((str(classes.resolve()), classpath))


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def synthetic_texture(character):
    return base64.b64encode(json.dumps({"textures": {"SKIN": {"url": "https://textures.minecraft.net/texture/" + character * 64}}}, separators=(",", ":")).encode()).decode()


def configure(run, role, scenario, port, backend, metadata):
    plugins = run / "plugins"
    plugins.mkdir(parents=True)
    (run / "tmp").mkdir()
    (run / "velocity.toml").write_text(f'''config-version = "2.9"
bind = "127.0.0.1:{port}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
backend = "127.0.0.1:{backend}"
try = ["backend"]
[forced-hosts]
"lobby.example.com" = ["backend"]
"factions.example.com" = ["backend"]
"minigames.example.com" = ["backend"]
[advanced]
accepts-transfers = false
login-ratelimit = 0
connection-timeout = 2000
read-timeout = 15000
''', encoding="utf-8")
    if metadata["platform"] == "bungee":
        (run / "velocity.toml").unlink()
        (run / "config.yml").write_text(f'''online_mode: false
ip_forward: true
network_compression_threshold: -1
connection_throttle: -1
connection_throttle_limit: 100
timeout: 15000
server_connect_timeout: 2000
log_pings: false
prevent_proxy_connections: false
listeners:
- host: 127.0.0.1:{port}
  motd: Isolated role selection oracle
  max_players: 20
  priorities: [backend]
  force_default_server: true
  forced_hosts: {{}}
  query_enabled: false
servers:
  backend:
    address: 127.0.0.1:{backend}
    motd: Isolated SDK recording backend
    restricted: false
''', encoding="utf-8")
    else:
        (run / "forwarding.secret").write_text(secrets.token_hex(32), encoding="utf-8")
    fixture = {**metadata, "mode": "floodgate", "role": role, "scenario": scenario, "roleU": U, "roleJ": J, "xuid": XUID,
               "premiumTextureValue": synthetic_texture("a"), "microsoftTextureValue": None, "skinPolicy": "none",
               "microsoftProof": "official-floodgate-cipher-channel", "relation": "synthetic-current-fixture",
               "texture": "synthetic-not-official-profile", "paperSaveVerified": False,
               "realMicrosoftVerified": False, "realBedrockVerified": False, "skinRenderingVerified": False}
    (run / "isolated-role-fixture.json").write_text(json.dumps(fixture, indent=2), encoding="utf-8")
    shutil.copyfile(ROOT / "limbo/protocols/777/src/main/resources/limbo/configuration-777.bin", run / "configuration-777.bin")
    data = plugins / "floodgate"
    data.mkdir()
    # Official plugin creates this fixture's own key/SQLite data; never copy user keys/data.
    (data / "config.yml").write_text(f'''key-file-name: key.pem
username-prefix: "."
replace-spaces: true
default-locale: en_US
send-floodgate-data: false
disconnect:
  invalid-key: ROLE_E2E_INVALID_KEY
  invalid-arguments-length: ROLE_E2E_INVALID_DATA
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
    return fixture


def wait_for(child, console, marker, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
        require("ROLE_E2E_ASSERTION_FAILED" not in text, f"Native fixture failed: {console}")
        if marker in text: return
        require(child.poll() is None, f"Owned process exited before {marker}: {console}")
        time.sleep(0.2)
    raise TimeoutError(f"No {marker}: {console}")


def stop(child, payload):
    if child is None or child.poll() is not None: return
    try:
        child.stdin.write(payload); child.stdin.flush(); child.wait(timeout=20)
    except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
        child.terminate()
        try: child.wait(timeout=5)
        except subprocess.TimeoutExpired:
            child.kill(); child.wait(timeout=5)


@contextmanager
def process(command, run, console, shutdown):
    child = None
    try:
        with console.open("w", encoding="utf-8") as output:
            env = os.environ.copy()
            for key in list(env):
                if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"}: env.pop(key)
            for key in ("HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME", "TMP", "TEMP"):
                location = run / "home" / key.lower(); location.mkdir(parents=True, exist_ok=True); env[key] = str(location.resolve())
            child = subprocess.Popen(command, cwd=run, env=env, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
            yield child
    finally:
        stop(child, shutdown)


def java(args, helper, run, heap):
    return [helper.executable(args.jdk, "java"), "-Xms16M", f"-Xmx{heap}M", "-XX:ActiveProcessorCount=2",
            "-Djava.net.preferIPv4Stack=true", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
            "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9", f"-Djava.io.tmpdir={run / 'tmp'}",
            f"-Duser.home={run / 'home'}", f"-Dkyokou.roleE2e.root={BASE.resolve()}"]


def verify_evidence(run, mode, role, client_output):
    fixture = json.loads((run / "isolated-role-fixture.json").read_text(encoding="utf-8"))
    require(fixture.get("mode") == "floodgate" and mode == "floodgate", "Java basic paths are separate; advanced role results are unsupported")
    lines = [json.loads(line.removeprefix("ROLE_CLIENT_RESULT ")) for line in client_output.splitlines() if line.startswith("ROLE_CLIENT_RESULT ")]
    require(len(lines) == 1 and lines[0].get("mode") == mode and lines[0].get("role") == role, "Missing unambiguous native SDK client result")
    result = lines[0]
    require(result.get("transferPackets") == 0 and result.get("cookiePackets") == 0, "Native Transfer or cookie path was observed")
    require(result.get("platform") == fixture.get("platform", "velocity"), "Wrong native platform result")
    scenario = fixture.get("scenario", "linked")
    single = scenario == "single"
    reconnects = 0 if single or role == "premium" else 1
    sockets = result.get("sockets", [])
    require(result.get("ordinaryReconnects") == reconnects and len(sockets) == reconnects + 1, "Wrong ordinary connection count")
    require(result.get("selectionDisconnect") is bool(reconnects), "Different identity must receive role-selected ordinary disconnect")
    console = (run / "console.log").read_text(encoding="utf-8", errors="replace")
    for bad in ("ROLE_E2E_ASSERTION_FAILED", "Only Floodgate players can send floodgate messages!", "Invalid form response", "Error while processing form response!", "Exception while handling plugin message"):
        require(bad not in console + client_output, "Official payload/source/native failure: " + bad)
    if single:
        require("ROLE_E2E_MENU" not in console and "ROLE_E2E_NATIVE_HELD" not in console and result.get("menuSlots") == 0, "singleRole must authorize prelogin with zero menu/chooser")
    else:
        require(result.get("menuSlots") == 27 and result.get("wireSlots") == 63 and result.get("iconSlots") == [12, 14]
                and result.get("icons") == ["NAME_TAG", "DIAMOND"] and (run / "role-selector.bin").is_file(), "Real vanilla 27-slot GUI export missing")
    mapped = []
    fingerprints, ports, wires = set(), set(), set()
    def map_socket(value):
        require(value.get("intent") == "LOGIN", "Each socket must use ordinary LOGIN, never TRANSFER")
        require(value.get("wireSession") not in wires and value.get("localPort") not in ports, "New socket must have distinct wire session and TCP port")
        wires.add(value.get("wireSession")); ports.add(value.get("localPort"))
        fingerprint = value.get("proofFingerprint", "")
        require(value.get("freshOfficialCipher") is True and re.fullmatch(r"[0-9a-f]{64}", fingerprint) and fingerprint not in fingerprints, "Missing independently fresh official cipher on actual socket")
        fingerprints.add(fingerprint)
        marker = "ROLE_CLIENT_SOCKET " + json.dumps(value, separators=(",", ":"))
        require(client_output.count(marker) == 1, "Client socket evidence is absent/ambiguous")
        native = re.findall(r"ROLE_E2E_NATIVE_SOCKET session=([0-9a-f-]{36}) remotePort=" + str(value.get("localPort")) + r" state=(?:held|final)\b", console)
        require(len(native) == 1, "Socket lacks a unique native TCP correlation")
        session = native[0]
        require(re.search(r"ROLE_E2E_FG_PROOF session=" + re.escape(session) + r" phase=[1-9][0-9]* linked=" + ("false" if single else "true") + r"\b", console), "Socket lacks its exact native official Floodgate proof")
        return session
    for value in sockets: mapped.append(map_socket(value))
    require(len(set(mapped)) == len(mapped), "New LOGIN reused old native session")
    final = mapped[-1]
    require(console.count("ROLE_E2E_NATIVE_FINAL ") == 1 and f"ROLE_E2E_NATIVE_FINAL session={final}" in console
            and console.count("ROLE_E2E_AUTHORIZED_BACKEND ") == 1 and f"ROLE_E2E_AUTHORIZED_BACKEND session={final}" in console, "Final native admission is not exact once on the expected socket")
    if not single:
        initial = mapped[0]
        require(f"ROLE_E2E_NATIVE_HELD session={initial}" in console and f"ROLE_E2E_MENU session={initial} slots=27 u=NAME_TAG j=DIAMOND" in console, "Initial chooser is not held/native vanilla GUI")
        replay = result.get("replaySocket")
        require(result.get("hintReplayRequiresChooser") is True and isinstance(replay, dict), "Consumed hint replay must require held chooser")
        replay_session = map_socket(replay)
        require(f"ROLE_E2E_NATIVE_HELD session={replay_session}" in console and f"ROLE_E2E_MENU session={replay_session} slots=27 u=NAME_TAG j=DIAMOND" in console, "Consumed hint replay lacks a fresh held chooser")
        require(replay_session != final, "Hint replay borrowed authorized connection")
    phases = [("final", final, "false")] if single else [("chooser", mapped[0], "true"), ("final", final, "false")]
    require(result.get("finalPlay") is True and result.get("finalFormAcknowledged") is True
            and (single or result.get("chooserFormAcknowledged") is True), "Official handler wire ACK/final PLAY missing")
    require(console.count("Couldn't find stored form with id -12345 for player ") == len(phases), "Official FormChannel did not process the exact valid responses")
    for phase, session, held in phases:
        pattern = r"ROLE_E2E_FG_MESSAGE_BARRIER session=([0-9a-f-]{36}) phase=" + phase + " source=PLAYER held=" + held + " bytes=3"
        require(re.findall(pattern, console) == [session], "Missing exact official handler barrier/session")
        require(f"ROLE_CLIENT_FG_MESSAGE_SENT phase={phase} channel=floodgate:form formId=-12345 payload=valid-json-0" in client_output, "SDK official form response missing")
    events_path = run / "backend-events.jsonl"
    events = [json.loads(line) for line in events_path.read_text(encoding="utf-8").splitlines() if line] if events_path.exists() else []
    require(not any(value.get("event") == "error" for value in events), "Official backend protocol/forwarding error")
    require(sum(value.get("event") == "handshake" for value in events) == 1, "Must have exactly one backend handshake; chooser/replay unauthorized")
    expected_uuid, name, canary = (fixture.get("roleU", U), "URole", "independent-u-data") if role == "microsoft" else (J, "JRole", "independent-j-data")
    forwarded = [value for value in events if value.get("event") == "authorized"]
    require(len(forwarded) == 1 and forwarded[0].get("uuid") == expected_uuid and forwarded[0].get("name") == name
            and forwarded[0].get("canary") == canary, "Wrong final UUID-keyed canary")
    if fixture.get("platform", "velocity") == "bungee":
        require(forwarded[0].get("legacyForwarded") is True, "Bungee legacy forwarded identity evidence missing; Velocity HMAC is not Bungee authority")
        require(forwarded[0].get("privateTicketAbsent") is True and forwarded[0].get("connectionNonceAbsent") is True,
                "Bungee private ticket/connection nonce leaked to real backend")
        require(forwarded[0].get("testSession") == final, "Test-only unmanaged backend correlation differs from exact final native session")
        # Authority authenticates only the private Limbo engine. It is never forwarded to a real backend.
        if not single:
            for session in (mapped[0], replay_session):
                require(f"ROLE_E2E_PRIVATE_LIMBO_AUTHORITY session={session} consumed=true target=private-engine" in console,
                        "Missing actual private Limbo authority admission on exact held socket")
    else:
        require(forwarded[0].get("hmac") is True, "Missing real modern forwarding HMAC")
        require(forwarded[0].get("nonce") == final, "Backend nonce differs from exact final native session")
    for event in ("login", "play", "play-message-complete"):
        actual = [value for value in events if value.get("event") == event]
        require(len(actual) == 1 and actual[0].get("uuid") == expected_uuid and actual[0].get("name") == name, "Actual backend identity/PLAY message completion missing")
    return dict(result, backend=forwarded[0], nativeBackendHandshakes=1, paperSaveVerified=False,
                realBedrockVerified=False, realMicrosoftVerified=False, renderingVerified=False)


def run_case(args, helper, metadata, floodgate, classpath, case):
    role = "premium" if case == "floodgate-j" else "microsoft"
    scenario = "single" if case == "floodgate-single" else "linked"
    run = BASE / "runtime" / f"{case}-{uuid.uuid4().hex[:12]}"
    port, backend = free_port(), free_port()
    while backend == port: backend = free_port()
    fixture = configure(run, role, scenario, port, backend, metadata)
    shutil.copyfile(test_jar(args.jar, args.platform), run / "plugins/role-gate-test.jar")
    shutil.copyfile(floodgate, run / "plugins/floodgate.jar")
    backend_command = java(args, helper, run, 64) + ["-cp", classpath, MAIN, "backend", str(run.resolve()), str(backend)]
    proxy_command = java(args, helper, run, 128) + [f"-Dkyokou.roleE2e.run={run.resolve()}", "-Dkyokou.roleE2e.mode=floodgate",
        f"-Dkyokou.roleE2e.role={role}", f"-Dkyokou.roleE2e.scenario={scenario}", f"-Dkyokou.roleE2e.premiumTexture={fixture['premiumTextureValue']}",
        "-Dkyokou.roleE2e.floodgateMessages=true", "-Dvelocity.packet-decode-logging=true", "-jar", str(args.proxy.resolve())]
    client_command = java(args, helper, run, 64) + ["-cp", classpath, MAIN, "client", str(run.resolve()), str(port)]
    with process(backend_command, run, run / "backend.log", b"\n") as backend_process:
        wait_for(backend_process, run / "backend.log", "ROLE_BACKEND_READY", args.timeout)
        with process(proxy_command, run, run / "console.log", b"end\n") as proxy_process:
            wait_for(proxy_process, run / "console.log", "ROLE_E2E_STARTED", args.timeout)
            completed = subprocess.run(client_command, cwd=run, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=100)
            output = completed.stdout + completed.stderr
            (run / "client.log").write_text(output, encoding="utf-8")
            require(completed.returncode == 0, f"Native SDK client failed: {run / 'client.log'}")
            result = verify_evidence(run, "floodgate", role, output)
            result.update(productionJarSha256=digest(args.jar), proxyJarSha256=digest(args.proxy))
            (run / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
            print(f"PASS {args.platform}/{case}: ordinary reconnects={result['ordinaryReconnects']}; exact backend UUID/canary + official FG messages")
            print(f"EVIDENCE {run.relative_to(ROOT)}")
            return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("prepare", "run"))
    parser.add_argument("--platform", choices=("velocity", "bungee"), default="velocity")
    parser.add_argument("--case", action="append", choices=CASES, help="Default: minimal native same-identity floodgate-j")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--proxy", type=Path, help="Read only this public Jar, never server config/key/data")
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--floodgate", type=Path)
    parser.add_argument("--client-classpath-file", type=Path, default=ROOT / "limbo/protocols/777/build/generated/limbo-codec/tool-classpath.txt")
    parser.add_argument("--timeout", type=int, choices=range(10, 301), default=90)
    args = parser.parse_args()
    label = "Velocity" if args.platform == "velocity" else "Bungee"
    args.jar = args.jar or ROOT / f"platform-{args.platform}/build/libs/KyokouAuth-{label}-0.1.0-SNAPSHOT.jar"
    args.proxy = args.proxy or (ROOT / "run-proxy/velocity.jar" if args.platform == "velocity" else ROOT / ".narrafork/bungee-e2e/deps/BungeeCord-2111.jar")
    args.floodgate = args.floodgate or ROOT / f".narrafork/floodgate-e2e/deps/floodgate-{args.platform}-2.2.5-b141.jar"
    if args.platform == "bungee":
        require(args.proxy.is_file() and digest(args.proxy) in {
            "ede03ff02417f9257e6a53be7daf2718ba9fb917f3ff8e4f9f9823a50e08942a",
            "1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd",
        }, "Pinned official Bungee 2110/2111 public Jar mismatch")
    helper, upstream, floodgate, metadata = prepare(args)
    if args.command == "prepare":
        print("Prepared pinned local no-Transfer fixtures; zero JVM/downloads/user-config/key/data reads")
        return
    require(args.proxy.is_file(), "Missing explicit read-only public proxy Jar")
    classpath = compile_client(args, helper, upstream, floodgate)
    results = [run_case(args, helper, metadata, floodgate, classpath, case) for case in (args.case or ("floodgate-j",))]
    (BASE / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("NOT VERIFIED: Xbox/phone/render/Paper, Java advanced roles, other native proxy platforms")


if __name__ == "__main__":
    main()
