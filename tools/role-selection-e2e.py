"""Protocol-777 role selection through real Velocity adapters, native cookies and inventory packets.

prepare verifies/reuses local pinned fixtures only; it starts no Java and never downloads.
run requires the primary narrator's reserved JVM window: one 128 MiB Velocity proxy,
one 64 MiB SDK recording backend and one 64 MiB SDK client, serial cases, bounded cleanup.
All generated keys, optional Floodgate SQLite data, configs, classes and logs remain in
.narrafork/role-selection-e2e. Existing run-* paths are read-only JAR inputs, never key sources.

Microsoft/Xbox, current linkage, J textures and canary data are synthetic test fixtures.
The backend proves native LoginHello + modern HMAC role/nonce/properties, not Paper saves.
Optional Floodgate cases use official AES/BedrockData and two actual new native channels;
they do not verify Geyser's Bedrock Transfer extension, a real Xbox account or skin rendering.
--floodgate-messages keeps existing cases unchanged unless opted in: FG U/J send a
valid unknown-form response in chooser and final new-socket PLAY, with official handler
logs and a post-handler wire barrier; Java sends the same response and must be rejected.
--skin-policy sr-explicit/sr-default adds SHA-pinned SR 15.12.6 FILE storage and its
actual NORMAL listener. Only public API-seeded isolated custom data is used, not user SR data.
"""
import argparse
import base64
from contextlib import contextmanager
import hashlib
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import struct
import sys
import threading
import time
import uuid
from urllib.parse import parse_qs, urlsplit
import zipfile
import zlib

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/role-selection-e2e"
MAIN = "moe.ryuu.kyokouauth.tools.roles.RoleSelectionClient"
BOOTSTRAP = "RoleSelectionE2ePlugin"
CASES = ("java-u", "java-j", "floodgate-u", "floodgate-j")
AVATAR_CASES = ("cold", "hit", "changed", "new-user", "fallback")
XUID = 2535412345678901
FLOODGATE_SHA256 = "649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d"
SKINS_SHA256 = "a85b4a370f988741c9a38f4d0498262edacbf3220314790ac1c127461996359c"
U = str(uuid.UUID(int=2535412345678901))
J = "12345678-1234-4234-9234-123456789abc"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def official_helpers():
    # Reuse the pinned upstream classpath/JDK checks without calling its downloader or runtime.
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("role_official_floodgate_fixtures", ROOT / "tools/floodgate-e2e.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def prepare(args):
    helper = official_helpers()
    upstream = helper.client_classpath(args.client_classpath_file)
    if not args.floodgate.is_file() or digest(args.floodgate) != FLOODGATE_SHA256:
        raise RuntimeError("Missing/checksum-mismatched cached official Floodgate 2.2.5 b141 Velocity JAR; prepare the existing floodgate-e2e fixture first")
    BASE.mkdir(parents=True, exist_ok=True)
    deps = BASE / "deps"
    deps.mkdir(exist_ok=True)
    cached = deps / "floodgate-velocity-2.2.5-b141.jar"
    if not cached.exists():
        shutil.copyfile(args.floodgate, cached)
    if digest(cached) != FLOODGATE_SHA256:
        raise RuntimeError("Isolated cached Floodgate checksum mismatch")
    if args.skin_policy != "none":
        if not args.skinsrestorer_jar.is_file() or digest(args.skinsrestorer_jar) != SKINS_SHA256:
            raise RuntimeError("Missing/checksum-mismatched public SkinsRestorer 15.12.6 JAR (no user data/config is read)")
        with zipfile.ZipFile(args.skinsrestorer_jar) as archive:
            descriptor = json.loads(archive.read("velocity-plugin.json"))
            if descriptor.get("id") != "skinsrestorer" or descriptor.get("version") != "15.12.6":
                raise RuntimeError("Pinned public SkinsRestorer descriptor mismatch")
        cached_skins = deps / "SkinsRestorer-15.12.6.jar"
        if not cached_skins.exists():
            shutil.copyfile(args.skinsrestorer_jar, cached_skins)
        if digest(cached_skins) != SKINS_SHA256:
            raise RuntimeError("Isolated cached SkinsRestorer checksum mismatch")
    registry = ROOT / "limbo/protocols/777/src/main/resources/limbo/role-selector-777.properties"
    properties = dict(line.split("=", 1) for line in registry.read_text(encoding="utf-8").splitlines() if "=" in line and not line.startswith("#"))
    if properties.get("protocol") != "777" or properties.get("source") != "official-server-bootstrap-built-in-registries":
        raise RuntimeError("Selector registry must be the pinned official protocol-777 extraction")
    configuration_sha256 = None
    if args.floodgate_messages:
        configuration = ROOT / "limbo/protocols/777/src/main/resources/limbo/configuration-777.bin"
        provenance = configuration.with_suffix(".properties")
        data = dict(line.split("=", 1) for line in provenance.read_text(encoding="utf-8").splitlines() if "=" in line)
        configuration_sha256 = data.get("sha256")
        if data.get("protocol") != "777" or data.get("source") != "official-offline-server-empty-known-packs" or digest(configuration) != configuration_sha256:
            raise RuntimeError("Official final-PLAY configuration snapshot provenance/checksum mismatch")
    metadata = {"protocol": 777, "sdk": "MCProtocolLib-26.3-4", "floodgateSha256": FLOODGATE_SHA256,
                "playerHeadId": int(properties["player-head-id"]), "registrySha256": digest(registry),
                "clientSourceSha256": digest(ROOT / "tools/RoleSelectionClient.java"), "networkDownloads": False,
                "skinPolicy": args.skin_policy, "skinsRestorerSha256": SKINS_SHA256 if args.skin_policy != "none" else None,
                "floodgateMessages": args.floodgate_messages, "configurationSha256": configuration_sha256,
                "avatars": args.avatars}
    (BASE / "prepared.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return helper, upstream, cached, metadata


def test_jar(artifact, mode, skin_policy, floodgate_messages=False):
    if not artifact.is_file():
        raise RuntimeError("Build :platform-velocity:shadowJar first")
    package = "moe/ryuu/kyokouauth/velocity"
    directory = ROOT / "platform-velocity/build/classes/kotlin/test" / package
    entries = sorted(directory.glob(BOOTSTRAP + "*.class"))
    if not entries:
        raise RuntimeError("Build :platform-velocity:compileTestKotlin first")
    target = BASE / "deps" / f"role-gate-{mode}-{skin_policy}.jar"
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=1) as output:
        if any("E2ePlugin" in name for name in source.namelist()):
            raise RuntimeError("Production artifact contains a test bootstrap")
        metadata = json.loads(source.read("velocity-plugin.json"))
        metadata.update(id="kyokou-role-selection-e2e", name="Isolated Role Selection E2E", version="isolated",
                        main=f"moe.ryuu.kyokouauth.velocity.{BOOTSTRAP}")
        dependencies = {item["id"]: item for item in metadata.get("dependencies", [])}
        dependencies["floodgate"] = {"id": "floodgate", "optional": mode != "floodgate" and skin_policy == "none" and not floodgate_messages}
        dependencies["skinsrestorer"] = {"id": "skinsrestorer", "optional": skin_policy == "none"}
        metadata["dependencies"] = list(dependencies.values())
        for info in source.infolist():
            if info.filename != "velocity-plugin.json":
                output.writestr(info.filename, source.read(info.filename))
        output.writestr("velocity-plugin.json", json.dumps(metadata))
        for path in entries:
            output.writestr(package + "/" + path.name, path.read_bytes())
    return target


def compile_client(args, helper, upstream, floodgate):
    classes = BASE / "client-classes"
    classes.mkdir(exist_ok=True)
    classpath = os.pathsep.join((upstream, str(floodgate.resolve())))
    command = [helper.executable(args.jdk, "javac"), "-J-Xmx128M", "-J-XX:ActiveProcessorCount=2", "-encoding", "UTF-8",
               "-cp", classpath, "-d", str(classes.resolve()), str(ROOT / "tools/RoleSelectionClient.java")]
    subprocess.run(command, cwd=BASE, check=True, timeout=90)
    return os.pathsep.join((str(classes.resolve()), classpath))


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def synthetic_texture(character):
    return base64.b64encode(json.dumps({"textures": {"SKIN": {
        "url": "https://textures.minecraft.net/texture/" + character * 64}}}, separators=(",", ":")).encode()).decode()


def solid_skin(red, green, blue):
    """Small genuine 64x64 RGBA public fixture; production service does the actual sprite rendering."""
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)
    pixels = b"".join(b"\0" + bytes((red, green, blue, 255)) * 64 for _ in range(64))
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 64, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b"")


@contextmanager
def avatar_status_fixture(run, fixture):
    """Controlled offered/fallback status only. Not an actual Geyser session, pack offer or render ACK."""
    if not fixture.get("avatars"):
        yield
        return
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            request = urlsplit(self.path)
            query = parse_qs(request.query, keep_blank_values=True)
            status, body = 404, {"schema": 1, "state": "not-found"}
            if request.path == "/v1/packs/status" and set(query) == {"xuid"} and len(query["xuid"]) == 1:
                try:
                    xuid = int(query["xuid"][0])
                    offered = json.loads((run / "avatar-presentation.json").read_text(encoding="utf-8"))
                    if xuid > 0 and xuid == offered["xuid"]:
                        body = {key: offered[key] for key in ("schema", "state", "nonce", "fingerprint", "fallback")}
                        status = 200
                    with lock:
                        with (run / "avatar-status-events.jsonl").open("a", encoding="utf-8") as output:
                            output.write(json.dumps({"xuid": xuid, "status": status, **body,
                                "proof": "controlled-loopback-fixture-not-geyser"}) + "\n")
                except (OSError, ValueError, KeyError):
                    status, body = 503, {"error": "isolated presentation unavailable"}
            payload = json.dumps(body, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", fixture["avatarStatusPort"]), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, name="role-avatar-offered-fixture", daemon=True)
    thread.start()
    try:
        yield
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def configure_skins(run):
    data = run / "plugins/skinsrestorer"
    data.mkdir(parents=True)
    (data / "config.yml").write_text("""database:
  type: FILE
advanced:
  noConnections: true
  disableOnJoinSkins: false
storage:
  disallowAutoUpdateSkin: true
  defaultSkins:
    enabled: false
login:
  alwaysApplyPremium: true
  offlineModeWarning:
    enabled: false
""", encoding="utf-8")
    (data / "noautoupdate.txt").write_text("Pinned isolated SR fixture; never auto-update.\n", encoding="utf-8")
    metrics = run / "plugins/bStats"
    metrics.mkdir(exist_ok=True)
    (metrics / "config.txt").write_text("enabled=false\n", encoding="utf-8")


def configure(run, mode, role, port, backend, metadata):
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
accepts-transfers = true
login-ratelimit = 0
connection-timeout = 2000
read-timeout = 15000
''', encoding="utf-8")
    (run / "forwarding.secret").write_text(secrets.token_hex(32), encoding="utf-8")
    skin_policy = metadata["skinPolicy"]
    avatars = metadata.get("avatars", False)
    avatar_case = metadata.get("avatarCase", "none")
    xuid = XUID + 31 if avatar_case == "new-user" else XUID
    fixture = {"protocol": 777, "mode": mode, "role": role, "playerHeadId": metadata["playerHeadId"],
               "premiumTextureValue": synthetic_texture("c" if avatar_case == "changed" else "a"),
               "roleU": str(uuid.UUID(int=xuid)), "roleJ": J, "xuid": xuid,
               "avatars": avatars, "avatarCase": avatar_case,
               "baselineTextureValue": synthetic_texture("e"), "baselineXuid": XUID,
               "geyserStatusProof": "controlled-loopback-fixture-not-geyser", "packRendered": False,
               "skinPolicy": skin_policy, "floodgateMessages": metadata["floodgateMessages"],
               "configurationSha256": metadata["configurationSha256"],
               "microsoftTextureValue": synthetic_texture("b") if skin_policy == "sr-explicit" else None,
               "microsoftSeedTextureValue": synthetic_texture("b"), "decoyTextureValue": synthetic_texture("c"),
               "microsoftProof": "synthetic-services" if mode == "java" else "official-floodgate-cipher-channel",
               "relation": "synthetic-current-fixture", "texture": "synthetic-not-official-profile",
               "paperSaveVerified": False, "realMicrosoftVerified": False, "realBedrockVerified": False,
               "skinsRestorerVerified": False, "skinRenderingVerified": False}
    if avatars:
        ports = {port, backend}
        for key in ("avatarBridgePort", "avatarStatusPort"):
            candidate = free_port()
            while candidate in ports:
                candidate = free_port()
            ports.add(candidate)
            fixture[key] = candidate
        (run / "avatar-red64.png").write_bytes(solid_skin(220, 40, 40))
        (run / "avatar-blue64.png").write_bytes(solid_skin(40, 70, 220))
        offered = {"schema": 1, "state": "offered", "xuid": xuid, "nonce": str(uuid.uuid4()), "fingerprint": None, "fallback": True}
        (run / "avatar-presentation.json").write_text(json.dumps(offered), encoding="utf-8")
    (run / "isolated-role-fixture.json").write_text(json.dumps(fixture, indent=2), encoding="utf-8")
    if metadata["floodgateMessages"] and mode == "floodgate":
        shutil.copyfile(ROOT / "limbo/protocols/777/src/main/resources/limbo/configuration-777.bin", run / "configuration-777.bin")
    if skin_policy != "none":
        configure_skins(run)
    if mode == "floodgate" or skin_policy != "none" or metadata["floodgateMessages"]:
        data = plugins / "floodgate"
        data.mkdir()
        # The actual official plugin creates its own new key and any SQLite data in this directory.
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


def wait_for(process, console, marker, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
        if "ROLE_E2E_ASSERTION_FAILED" in text:
            raise RuntimeError(f"Native fixture assertion failed; inspect {console.relative_to(ROOT)}")
        if marker in text:
            return
        if process.poll() is not None:
            raise RuntimeError(f"Fixture process exited {process.returncode}; inspect {console.relative_to(ROOT)}")
        time.sleep(0.2)
    raise TimeoutError(f"No {marker}; inspect {console.relative_to(ROOT)}")


def stop(process, payload):
    if process is None or process.poll() is not None:
        return
    try:
        process.stdin.write(payload)
        process.stdin.flush()
        process.wait(timeout=20)
    except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


@contextmanager
def process(command, run, console, shutdown):
    child = None
    try:
        with console.open("w", encoding="utf-8") as output:
            child = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
            yield child
    finally:
        stop(child, shutdown)


def java(args, helper, run, heap):
    return [helper.executable(args.jdk, "java"), "-Xms16M", f"-Xmx{heap}M", "-XX:ActiveProcessorCount=2",
            "-Djava.net.preferIPv4Stack=true", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
            "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
            f"-Djava.io.tmpdir={run / 'tmp'}", f"-Dkyokou.roleE2e.root={BASE.resolve()}"]


def verify_java_forgery(run, role, result, client_output):
    kick = "Only Floodgate players can send floodgate messages!"
    console = (run / "console.log").read_text(encoding="utf-8", errors="replace")
    if not result.get("floodgateMessages") or not result.get("forgedFloodgateRejected") or not result.get("chooserFormSent") or result.get("officialKick") != kick:
        raise AssertionError("Java negative control lacked the exact official Floodgate source rejection")
    if not result.get("playReady") or result.get("newSocket") or kick not in console:
        raise AssertionError("Java forged message did not fail closed in the original native PLAY socket")
    for marker in ("ROLE_E2E_NATIVE_HELD", "ROLE_E2E_LIMBO_READY", "ROLE_E2E_MENU", "ROLE_E2E_FG_JAVA_REJECT_BARRIER"):
        if marker not in console:
            raise AssertionError("Java forged message did not reach the native HELD chooser/official-handler boundary")
    if "ROLE_CLIENT_FG_MESSAGE_SENT phase=chooser channel=floodgate:form formId=-12345 payload=valid-json-0" not in client_output:
        raise AssertionError("Java negative control did not use the same valid official-channel response")
    for forbidden in ("ROLE_E2E_ASSERTION_FAILED", "ROLE_E2E_NATIVE_FINAL", "ROLE_E2E_AUTHORIZED_BACKEND", "ROLE_E2E_FG_MESSAGE_BARRIER",
                      "Couldn't find stored form with id -12345", "Invalid form response", "Error while processing form response!"):
        if forbidden in console:
            raise AssertionError(f"Java Floodgate forgery was not a pure official source rejection: {forbidden}")
    events_file = run / "backend-events.jsonl"
    if events_file.exists() and events_file.read_text(encoding="utf-8").strip():
        raise AssertionError("Forged Java Floodgate message reached the backend")
    result.update(nativeBackendHandshakes=0, microsoftProof="synthetic-services", relation="synthetic-current-fixture",
                  paperSaveVerified=False, skinsRestorerVerified=False, fixture=str(run.relative_to(ROOT)))
    return result


def verify_avatar_evidence(run, result, console, client_output):
    fixture = json.loads((run / "isolated-role-fixture.json").read_text(encoding="utf-8"))
    if not fixture.get("avatars"):
        return
    evidence = result.get("avatars", {})
    scenario = fixture["avatarCase"]
    expected_reloads = 0 if scenario == "hit" else 1
    if evidence.get("enabled") is not True or evidence.get("scenario") != scenario or evidence.get("visualTransfers") != expected_reloads:
        raise AssertionError("Avatar visual reload must be opt-in, scenario-bound and limited to exactly zero/one native Transfer")
    if evidence.get("visualRoleHandoffUsed") is not False or evidence.get("roleCookieMinted") is not False:
        raise AssertionError("Visual retry must not mint/use a RoleHandoff or role cookie")
    if evidence.get("rendered") is not False or evidence.get("applied") is not False or evidence.get("geyserStatusProof") != "controlled-loopback-fixture-not-geyser":
        raise AssertionError("Controlled offered/fallback status is not an applied/render ACK")
    fallback = scenario == "fallback"
    if evidence.get("fallback") is not fallback or evidence.get("offeredMatch") is not (not fallback) or evidence.get("unavailableNotice") is not fallback:
        raise AssertionError("Fallback must remain explicitly unavailable, never disguised as offered/applied success")
    if evidence.get("freshProof") is not True or evidence.get("snapshotHttpVerified") is not True or evidence.get("distinctSprites") is not True:
        raise AssertionError("Missing independent fresh-FG/real-avatar-HTTP/PNG proof")
    if evidence.get("itemCmd") != [0x4B5901, 0x4B5902] or evidence.get("nonItalic") is not True:
        raise AssertionError("Native two-role GUI did not preserve exact avatar CMD/nonitalic roots")
    xuid = fixture["xuid"]
    if evidence.get("xuid") != xuid or fixture["roleU"] != str(uuid.UUID(int=xuid)):
        raise AssertionError("Avatar presentation subject does not match the strongly proved native XUID/U")
    snapshot = json.loads((run / "avatar-snapshot-observed.json").read_text(encoding="utf-8"))
    if snapshot.get("schema") != 1 or snapshot.get("state") != "ready" or snapshot.get("microsoft_default") is not True or snapshot.get("premium_default") is not False:
        raise AssertionError("Real avatar bridge snapshot/default flags were not observed")
    pngs = [base64.b64decode(snapshot[key], validate=True) for key in ("microsoft_png", "premium_png")]
    if pngs[0] == pngs[1]:
        raise AssertionError("Prepared red/blue avatars are not independent PNGs")
    for png in pngs:
        if not 24 <= len(png) <= 8192 or png[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", png[16:24]) != (32, 32):
            raise AssertionError("Actual avatar service did not return bounded 32x32 PNG sprites")
    fingerprint = hashlib.sha256(b"kyokouauth-role-avatar-v1\0" + b"".join(struct.pack(">I", len(png)) + png for png in pngs)).hexdigest()
    if snapshot.get("fingerprint") != fingerprint or evidence.get("fingerprint") != fingerprint:
        raise AssertionError("Avatar fingerprint is not the exact independently hashed HTTP PNG content")
    phases = {"chooser": evidence.get("chooserWireSession"), "final": evidence.get("finalWireSession")}
    if expected_reloads:
        phases["visual"] = evidence.get("visualWireSession")
    elif evidence.get("visualWireSession") != "" or evidence.get("visualPort") != 0:
        raise AssertionError("Warm fingerprint hit unexpectedly used a visual socket")
    if len(set(phases.values())) != len(phases):
        raise AssertionError("Visual/chooser/final must be genuinely distinct wire sessions")
    ports = [evidence.get(phase + "Port") for phase in phases]
    if any(type(port) is not int or not 1 <= port <= 65535 for port in ports) or len(set(ports)) != len(ports):
        raise AssertionError("Visual/chooser/final require distinct actual local TCP ports")
    native_sessions = {}
    for phase, wire_session in phases.items():
        if not isinstance(wire_session, str) or not re.fullmatch(r"[0-9a-f-]{36}", wire_session):
            raise AssertionError("Missing exact LoginFinished wire session")
        port = evidence[phase + "Port"]
        marker = f"ROLE_CLIENT_AVATAR_SOCKET phase={phase} wireSession={wire_session} localPort={port} freshOfficialCipher=true"
        if client_output.count(marker) != 1:
            raise AssertionError("Visual retry reused/missed fresh official Floodgate proof on the actual client socket")
        # The SDK's LoginFinished session UUID and Velocity Player.sessionId are
        # distinct ID domains. Correlate them only through the real TCP tuple.
        state = "final" if phase == "final" else "held"
        matches = re.findall(r"ROLE_E2E_AVATAR_NATIVE_SOCKET session=([0-9a-f-]{36}) remotePort=" + str(port) + r" state=" + state + r"\b", console)
        if len(matches) != 1:
            raise AssertionError("Client TCP socket lacks a unique matching native proxy connection")
        session = matches[0]
        native_sessions[phase] = session
        if not re.search(r"ROLE_E2E_FG_PROOF session=" + re.escape(session) + r" phase=[1-9][0-9]* linked=true", console):
            raise AssertionError("Visual retry reused/missed fresh official Floodgate proof")
        native = "ROLE_E2E_NATIVE_FINAL" if phase == "final" else "ROLE_E2E_NATIVE_HELD"
        if f"{native} session={session}" not in console:
            raise AssertionError("Visual retry incorrectly consumed role authorization instead of remaining HELD")
    if len(set(native_sessions.values())) != len(native_sessions):
        raise AssertionError("Visual/chooser/final reused a native proxy connection")
    menu_sessions = re.findall(r"ROLE_E2E_AVATAR_MENU session=([0-9a-f-]{36}) uCmd=4937985 jCmd=4937986 profile=unchanged", console)
    if native_sessions["chooser"] not in menu_sessions or native_sessions.get("visual") in menu_sessions:
        raise AssertionError("Avatar menu appeared before the visual fresh-proof connection or used the wrong native session")
    prepared = re.findall(r"ROLE_E2E_AVATAR_PREPARED call=[0-9]+ xuid=" + str(xuid) + " fingerprint=" + fingerprint + r" source=real-service syntheticSkins=true", console)
    if len(prepared) < 1 + expected_reloads:
        raise AssertionError("Fresh avatar connection did not prepare/revalidate the real role presentation again")
    statuses = [json.loads(line) for line in (run / "avatar-status-events.jsonl").read_text(encoding="utf-8").splitlines() if line]
    own = [status for status in statuses if status.get("xuid") == xuid and status.get("proof") == "controlled-loopback-fixture-not-geyser"]
    if not any(status.get("status") == 200 and status.get("fingerprint") == fingerprint and status.get("fallback") is fallback for status in own):
        raise AssertionError("The controlled status HTTP bridge never offered the expected subject/content/fallback")
    if expected_reloads and not any(status.get("status") != 200 or status.get("fingerprint") != fingerprint or status.get("fallback") is True for status in own):
        raise AssertionError("Native visual Transfer had no real initial offered-status MISS")
    if scenario in ("changed", "new-user", "hit"):
        baseline = json.loads((run / "avatar-baseline.json").read_text(encoding="utf-8"))
        if (baseline.get("fingerprint") == fingerprint) != (scenario == "hit"):
            raise AssertionError("Changed/new-user avatar content reused a stale presentation fingerprint")
        if scenario == "new-user" and (baseline.get("xuid") == xuid or xuid == XUID):
            raise AssertionError("New-user scenario reused the original strong fixture XUID")


def verify_evidence(run, mode, role, client_output):
    prefix = "ROLE_CLIENT_RESULT "
    results = [json.loads(line[len(prefix):]) for line in client_output.splitlines() if line.startswith(prefix)]
    if len(results) != 1 or results[0].get("role") != role or results[0].get("mode") != mode:
        raise AssertionError("Missing unambiguous native SDK client result")
    result = results[0]
    floodgate_messages = json.loads((run / "isolated-role-fixture.json").read_text(encoding="utf-8")).get("floodgateMessages", False)
    if floodgate_messages and mode == "java":
        return verify_java_forgery(run, role, result, client_output)
    if not result.get("newSocket") or not result.get("playReady") or result.get("heads") != [12, 14] or result.get("menuSlots") != 63:
        raise AssertionError("Native inventory or new-socket transfer was not observed")
    if mode == "java" and not result.get("cookieReplayRejected"):
        raise AssertionError("Actual native cookie replay was not rejected before Player login")
    if mode == "floodgate" and not result.get("hintReplayRequiresChooser"):
        raise AssertionError("Consumed hint did not require a fresh chooser")
    if floodgate_messages and (not result.get("floodgateMessages") or not result.get("chooserFormAcknowledged") or not result.get("finalFormAcknowledged") or not result.get("finalPlay")):
        raise AssertionError("Chooser/final PLAY did not wait for the official Floodgate handler wire barrier")
    console = (run / "console.log").read_text(encoding="utf-8", errors="replace")
    if "ROLE_E2E_ASSERTION_FAILED" in console:
        raise AssertionError("The production native adapter/bootstrap reported an assertion failure")
    if floodgate_messages:
        if "Only Floodgate players can send floodgate messages!" in client_output + console:
            raise AssertionError("Only Floodgate players can send floodgate messages!")
        if console.count("Couldn't find stored form with id -12345 for player JRole") != 2:
            raise AssertionError("Official FormChannel did not process exactly two chooser/final PLAY valid unknown-form responses")
        for phase, held in (("chooser", "true"), ("final", "false")):
            barriers = re.findall(r"ROLE_E2E_FG_MESSAGE_BARRIER session=([0-9a-f-]{36}) phase=" + phase + r" source=PLAYER held=" + held + r" bytes=3", console)
            if len(barriers) != 1:
                raise AssertionError(f"No unique native LAST message barrier followed the official NORMAL handler in {phase} PLAY")
            expected_session_marker = "ROLE_E2E_NATIVE_HELD" if phase == "chooser" else "ROLE_E2E_NATIVE_FINAL"
            if expected_session_marker + " session=" + barriers[0] not in console:
                raise AssertionError("Official message barrier used the wrong exact native session")
            if f"ROLE_CLIENT_FG_MESSAGE_SENT phase={phase} channel=floodgate:form formId=-12345 payload=valid-json-0" not in client_output:
                raise AssertionError("The official SDK did not send the valid message from both PLAY sockets")
        for error in ("Invalid form response", "Error while processing form response!", "Exception while handling plugin message"):
            if error in console:
                raise AssertionError(f"Official payload parse/handler error is not a pass: {error}")
    for marker in ("ROLE_E2E_NATIVE_HELD", "ROLE_E2E_LIMBO_READY", "ROLE_E2E_MENU", "ROLE_E2E_NATIVE_FINAL", "ROLE_E2E_AUTHORIZED_BACKEND"):
        if marker not in console:
            raise AssertionError(f"Native event proof is absent: {marker}")
    if console.count("ROLE_E2E_NATIVE_FINAL") != 1 or console.count("ROLE_E2E_AUTHORIZED_BACKEND") != 1:
        raise AssertionError("The old chooser or a replay reached final backend admission")
    skin_policy = result.get("skinPolicy", "none")
    if skin_policy != "none":
        for marker in ("ROLE_E2E_SR_SEEDED version=15.12.6 storage=FILE", "ROLE_E2E_SR_SERVICE_READ", "ROLE_E2E_SR_FINAL_POLICY"):
            if marker not in console:
                raise AssertionError(f"Real SR storage/native integration proof absent: {marker}")
        if role == "premium" and "ROLE_E2E_SR_NORMAL_J_DECOY" not in console:
            raise AssertionError("Real SR NORMAL did not attempt the stored J decoy before Gate LAST policy")
        if role == "microsoft" and skin_policy == "sr-explicit" and "ROLE_E2E_SR_NORMAL_U_EXPLICIT" not in console:
            raise AssertionError("Real SR NORMAL explicit U skin was not observed")
    file = run / "backend-events.jsonl"
    events = [json.loads(line) for line in file.read_text(encoding="utf-8").splitlines()] if file.exists() else []
    if any(event.get("event") == "error" for event in events):
        raise AssertionError("Official SDK recording backend encountered a protocol/forwarding assertion error")
    if sum(event.get("event") == "handshake" for event in events) != 1:
        raise AssertionError("Chooser/replay must never produce any second backend handshake")
    logins = [event for event in events if event.get("event") == "login"]
    forwarded = [event for event in events if event.get("event") == "authorized"]
    fixture = json.loads((run / "isolated-role-fixture.json").read_text(encoding="utf-8"))
    expected_id, expected_name, canary = (fixture.get("roleU", U), "URole", "independent-u-data") if role == "microsoft" else (J, "JRole", "independent-j-data")
    if len(logins) != 1 or logins[0].get("uuid") != expected_id or logins[0].get("name") != expected_name:
        raise AssertionError("Actual backend LoginHello did not carry the selected final UUID/name")
    if len(forwarded) != 1 or forwarded[0].get("uuid") != expected_id or forwarded[0].get("name") != expected_name or forwarded[0].get("canary") != canary or not forwarded[0].get("hmac"):
        raise AssertionError("Modern HMAC role identity did not select the independent UUID-keyed canary")
    if floodgate_messages:
        for phase in ("play", "play-message-complete"):
            observations = [event for event in events if event.get("event") == phase]
            if len(observations) != 1 or observations[0].get("uuid") != expected_id or observations[0].get("name") != expected_name:
                raise AssertionError("Selected final UUID/name did not complete official messaging in actual backend PLAY")
    final_sessions = re.findall(r"ROLE_E2E_NATIVE_FINAL session=([0-9a-f-]{36})", console)
    chooser_sessions = set(re.findall(r"ROLE_E2E_NATIVE_HELD session=([0-9a-f-]{36})", console))
    if len(final_sessions) != 1 or final_sessions[0] in chooser_sessions or forwarded[0].get("nonce") != final_sessions[0]:
        raise AssertionError("Backend nonce is not the new native final connection's exact session ID")
    verify_avatar_evidence(run, result, console, client_output)
    result.update(backend=forwarded[0], nativeBackendHandshakes=1,
                  microsoftProof="synthetic-services" if mode == "java" else "official-floodgate-cipher-channel",
                  relation="synthetic-current-fixture", paperSaveVerified=False, skinsRestorerVerified=skin_policy != "none",
                  fixture=str(run.relative_to(ROOT)))
    return result


def run_case(args, helper, metadata, floodgate, classpath, case, avatar_case="none"):
    mode = "floodgate" if case.startswith("floodgate") else "java"
    role = "microsoft" if case.endswith("-u") else "premium"
    suffix = f"-avatars-{avatar_case}" if args.avatars else ""
    run = BASE / "runtime" / f"{case}-{args.skin_policy}{suffix}-{uuid.uuid4().hex[:12]}"
    port, backend = free_port(), free_port()
    while backend == port:
        backend = free_port()
    fixture = configure(run, mode, role, port, backend, dict(metadata, avatarCase=avatar_case))
    shutil.copyfile(test_jar(args.jar, mode, args.skin_policy, args.floodgate_messages), run / "plugins/role-gate-test.jar")
    if mode == "floodgate" or args.skin_policy != "none" or args.floodgate_messages:
        shutil.copyfile(floodgate, run / "plugins/floodgate.jar")
    if args.skin_policy != "none":
        shutil.copyfile(BASE / "deps/SkinsRestorer-15.12.6.jar", run / "plugins/skinsrestorer.jar")
    backend_command = java(args, helper, run, 64) + ["-cp", classpath, MAIN, "backend", str(run.resolve()), str(backend)]
    proxy_command = java(args, helper, run, 128) + [
        f"-Dkyokou.roleE2e.run={run.resolve()}", f"-Dkyokou.roleE2e.mode={mode}", f"-Dkyokou.roleE2e.role={role}",
        f"-Dkyokou.roleE2e.port={port}", f"-Dkyokou.roleE2e.premiumTexture={fixture['premiumTextureValue']}",
        f"-Dkyokou.roleE2e.floodgateMessages={str(args.floodgate_messages).lower()}",
        f"-Dkyokou.roleE2e.avatars={str(args.avatars).lower()}", f"-Dkyokou.roleE2e.avatarCase={avatar_case}",
        f"-Dkyokou.roleE2e.xuid={fixture['xuid']}", f"-Dkyokou.roleE2e.avatarBridgePort={fixture.get('avatarBridgePort', 0)}",
        f"-Dkyokou.roleE2e.avatarStatusPort={fixture.get('avatarStatusPort', 0)}",
        f"-Dkyokou.roleE2e.avatarBaselineTexture={fixture['baselineTextureValue']}",
        f"-Dkyokou.roleE2e.skinPolicy={args.skin_policy}", f"-Dkyokou.roleE2e.microsoftTexture={fixture['microsoftSeedTextureValue']}",
        f"-Dkyokou.roleE2e.decoyTexture={fixture['decoyTextureValue']}",
        "-Dvelocity.packet-decode-logging=true",
        f"-Dkyokou.roleE2e.skinsJar={(run / 'plugins/skinsrestorer.jar').resolve()}", "-jar", str(args.proxy.resolve())]
    client_command = java(args, helper, run, 64) + ["-cp", classpath, MAIN, "client", str(run.resolve()), str(port)]
    with avatar_status_fixture(run, fixture), process(backend_command, run, run / "backend.log", b"\n") as backend_process:
        wait_for(backend_process, run / "backend.log", "ROLE_BACKEND_READY", args.timeout)
        with process(proxy_command, run, run / "console.log", b"end\n") as proxy_process:
            wait_for(proxy_process, run / "console.log", "ROLE_E2E_STARTED", args.timeout)
            completed = subprocess.run(client_command, cwd=run, capture_output=True, text=True, encoding="utf-8",
                                       errors="replace", timeout=100)
            output = completed.stdout + completed.stderr
            (run / "client.log").write_text(output, encoding="utf-8")
            if completed.returncode != 0:
                kick = "Only Floodgate players can send floodgate messages!"
                console = (run / "console.log").read_text(encoding="utf-8", errors="replace")
                detail = f"; official kick: {kick}" if args.floodgate_messages and kick in output + console else ""
                raise RuntimeError(f"Native role client failed{detail}; inspect {(run / 'client.log').relative_to(ROOT)}")
            result = verify_evidence(run, mode, role, output)
            result.update(productionJarSha256=digest(args.jar), velocityJarSha256=digest(args.proxy))
            (run / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
            if args.floodgate_messages and mode == "java":
                print(f"PASS Java Floodgate forgery {case}: same valid PLAY form response rejected by exact official source kick; zero backend handshakes")
            else:
                proof = "; official Floodgate handler in chooser+final PLAY" if args.floodgate_messages else ""
                print(f"PASS role selection {case}: native GUI/cookie-or-proof/new socket/final UUID canary; no chooser backend handshake{proof}")
            return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=("prepare", "run"))
    parser.add_argument("--case", action="append", choices=CASES, help="Repeat; defaults to java-u and java-j (minimal native cookie acceptance)")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--proxy", type=Path, default=ROOT / "run-proxy/velocity.jar", help="Read-only public Velocity 4.3.1 JAR, never its config/key/data")
    parser.add_argument("--jar", type=Path, default=ROOT / "platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--floodgate", type=Path, default=ROOT / ".narrafork/floodgate-e2e/deps/floodgate-velocity-2.2.5-b141.jar")
    parser.add_argument("--client-classpath-file", type=Path, default=ROOT / "limbo/protocols/777/build/generated/limbo-codec/tool-classpath.txt")
    parser.add_argument("--skin-policy", choices=("none", "sr-explicit", "sr-default"), default="none",
                        help="Optional real SR 15.12.6 FILE/public API/native NORMAL cases; no real skin rendering")
    parser.add_argument("--skinsrestorer-jar", type=Path, default=ROOT / "run-live/plugins/SkinsRestorer.jar",
                        help="Read only this SHA-pinned public JAR; never its user storage/config")
    parser.add_argument("--avatars", action="store_true", help="Opt-in real avatar service/HTTP + controlled offered-status fixture; implies --floodgate-messages")
    parser.add_argument("--avatar-case", action="append", choices=AVATAR_CASES, help="Repeat; defaults to cold. Each scenario has an independent isolated proxy/key/cache")
    parser.add_argument("--floodgate-messages", action="store_true",
                        help="Probe official floodgate:form during chooser+final PLAY and reject Java forgery; flag alone runs FG U/J + Java negative control")
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    if args.avatars:
        args.floodgate_messages = True
        if any(not case.startswith("floodgate-") for case in (args.case or CASES[2:])):
            parser.error("--avatars targets strongly verified Floodgate U/J; Java source forgery remains --floodgate-messages without --avatars")
    elif args.avatar_case:
        parser.error("--avatar-case requires --avatars")
    if not 1 <= args.timeout <= 300:
        parser.error("--timeout must be within 1..300 seconds")
    if args.skin_policy != "none" and any(case.startswith("floodgate-") for case in (args.case or CASES[:2])):
        parser.error("The SR native NORMAL acceptance cases currently target Java U/J; run Floodgate cases separately with --skin-policy none")
    if args.floodgate_messages and args.skin_policy != "none":
        parser.error("Message source acceptance runs separately with --skin-policy none")
    helper, upstream, floodgate, metadata = prepare(args)
    if args.command == "prepare":
        print("Prepared local checksum-pinned role fixtures; no downloads, Java processes, production key or database access")
        return
    if not args.proxy.is_file():
        parser.error("Missing read-only public Velocity JAR")
    classpath = compile_client(args, helper, upstream, floodgate)
    cases = args.case or (CASES[2:] if args.avatars else (("floodgate-u", "floodgate-j", "java-u") if args.floodgate_messages else CASES[:2]))
    scenarios = args.avatar_case or (("cold",) if args.avatars else ("none",))
    results = [run_case(args, helper, metadata, floodgate, classpath, case, scenario) for scenario in scenarios for case in cases]
    (BASE / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print("NOT VERIFIED: real Microsoft/Xbox, Geyser Bedrock Transfer, real Paper saves, official J profile lookup or client rendering")
    if args.skin_policy == "none":
        print("SR public storage/reflection/native NORMAL not executed; use --skin-policy sr-explicit or sr-default")


if __name__ == "__main__":
    main()
