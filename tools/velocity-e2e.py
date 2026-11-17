"""Exercise the real Velocity gate with synthetic Services and credential-free vanilla clients.

All generated files stay in .narrafork/velocity-e2e. Test bootstrap classes are taken from
compileTestKotlin, never from the production artifact. Proxy/client JVM windows must be serial.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/velocity-e2e"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


def test_jar(artifact, required_plugins=()):
    classes = ROOT / "platform-velocity/build/classes/kotlin/test/moe/ryuu/kyokouauth/velocity"
    entries = sorted(classes.glob("VelocityE2ePlugin*.class"))
    if not entries:
        raise RuntimeError("Run :platform-velocity:compileTestKotlin first")
    signature = digest(artifact) + ":" + hashlib.sha256(b"".join(path.read_bytes() for path in entries)).hexdigest()
    signature += ":metadata-v2:" + ",".join(sorted(required_plugins))
    target = BASE / "gate-test.jar"
    if target.is_file():
        with zipfile.ZipFile(target) as previous:
            if previous.comment.decode() == signature:
                return target
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as output:
        if any("VelocityE2ePlugin" in name for name in source.namelist()):
            raise RuntimeError("Production artifact contains test bootstrap")
        # Retain the production descriptor's optional integrations, especially Floodgate's loader
        # edge. Only the test bootstrap identity and explicitly requested test companions change.
        metadata = json.loads(source.read("velocity-plugin.json"))
        metadata.update(id="kyokou-velocity-e2e", name="Kyokou Velocity E2E", version="1",
                        main="moe.ryuu.kyokouauth.velocity.VelocityE2ePlugin")
        dependencies = {item["id"]: item for item in metadata.get("dependencies", [])}
        dependencies.update({name: {"id": name, "optional": False} for name in required_plugins})
        metadata["dependencies"] = list(dependencies.values())
        for info in source.infolist():
            if info.filename != "velocity-plugin.json":
                output.writestr(info.filename, source.read(info.filename))
        output.writestr("velocity-plugin.json", json.dumps(metadata))
        for path in entries:
            output.writestr("moe/ryuu/kyokouauth/velocity/" + path.name, path.read_bytes())
        output.comment = signature.encode()
    return target


def wait_for(process, console, marker, timeout=80):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = console.read_text(encoding="utf-8", errors="replace") if console.exists() else ""
        if marker in text:
            return
        if process.poll() is not None:
            raise RuntimeError(f"Proxy exited {process.returncode}; inspect {console.relative_to(ROOT)}")
        time.sleep(0.2)
    raise TimeoutError(f"No {marker}; inspect {console.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT / "platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--proxy", type=Path, default=ROOT / "run-proxy/velocity.jar")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--scenario", choices=["held", "server", "commands", "tcp-refused", "login-kick", "join-timeout", "production", "unavailable"], default="held")
    parser.add_argument("--version", default="26.3")
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    if args.scenario in {"tcp-refused", "login-kick", "join-timeout"} and args.version != "26.3":
        parser.error("Failure fixture is bound to protocol 777")
    for path in (args.jar, args.proxy, args.jdk / "bin/java.exe"):
        if not path.is_file():
            parser.error(f"Missing required file: {path}")
    BASE.mkdir(parents=True, exist_ok=True)
    # Reuse the bounded negative-backend fixture, not another production protocol implementation.
    spec = importlib.util.spec_from_file_location("bungee_failure_fixture", ROOT / "tools/bungee-e2e.py")
    fixtures = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixtures)
    backend = fixtures.Recorder("stall" if args.scenario == "commands" else "kick")
    engine = fixtures.Recorder("stall" if args.scenario == "join-timeout" else "kick") if args.scenario in {"login-kick", "join-timeout"} else None
    run = BASE / "runtime" / f"{args.scenario}-{args.version}"
    plugins = run / "plugins"
    plugins.mkdir(parents=True, exist_ok=True)
    (run / "tmp").mkdir(exist_ok=True)
    port = free_port()
    (run / "velocity.toml").write_text(f'''config-version = "2.9"
bind = "127.0.0.1:{port}"
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
[servers]
backend = "127.0.0.1:{backend.port}"
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
    (run / "forwarding.secret").write_text("isolated-test-forwarding-secret-not-a-credential", encoding="utf-8")
    production = args.scenario in {"production", "unavailable"}
    shutil.copyfile(args.jar if production else test_jar(args.jar), plugins / "gate-test.jar")
    if args.scenario == "unavailable":
        data = plugins / "kyokouauth"
        data.mkdir(exist_ok=True)
        (data / "config.yml").write_text("database:\n  type: intentionally-invalid\n", encoding="utf-8")
    command = [str(args.jdk / "bin/java.exe"), "-Xms64M", "-Xmx128M", "-XX:ActiveProcessorCount=2",
               f"-Djava.io.tmpdir={run / 'tmp'}", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=7890",
               f"-Dkyokou.e2e.scenario={args.scenario}"]
    if args.scenario == "tcp-refused":
        command.append(f"-Dkyokou.e2e.enginePort={free_port()}")
    elif engine is not None:
        command.append(f"-Dkyokou.e2e.enginePort={engine.port}")
    command += ["-jar", str(args.proxy.resolve())]
    console = run / "console.log"
    process = None
    try:
        with console.open("w", encoding="utf-8") as output:
            process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT)
            wait_for(process, console, "Done (")
            if args.scenario == "production":
                wait_for(process, console, "Login limbo supports protocols 767..777")
            else:
                if not production:
                    wait_for(process, console, "VELOCITY_E2E_STARTED")
                client = [sys.executable, str(ROOT / "tools/limbo-e2e.py"), "--external-proxy", "--version", args.version,
                          "--port", str(port), "--jdk", str(args.jdk), "--name", "VelOracle", "--timeout", str(args.timeout)]
                if args.scenario in {"tcp-refused", "login-kick", "join-timeout", "unavailable", "commands"}:
                    expected = "COMMAND_GUARD_COMPLETE" if args.scenario == "commands" else (
                        "Authentication is unavailable" if args.scenario == "unavailable" else "Login failed")
                    client += ["--proxy-log", str(console), "--expect-rejection", expected]
                else:
                    client += ["--observer", "--expect-verification", "--hold-seconds", "12", "--proxy-log", str(console),
                               "--ready-marker", "VELOCITY_E2E_READY {name}"]
                subprocess.run(client, cwd=ROOT, check=True)
                text = console.read_text(encoding="utf-8", errors="replace")
                if args.scenario in {"held", "server"}:
                    if "VELOCITY_E2E_PRIVATE_ENDPOINTS protocols=11 publicRegistry=false" not in text:
                        raise AssertionError("Private endpoints leaked into native server discovery")
                    if text.count("VELOCITY_E2E_TITLE VelOracle") != 1:
                        raise AssertionError("The actual shared gate must send exactly one title")
                    if args.scenario == "server":
                        if "VELOCITY_E2E_COMMANDS_HELD roots=hidden execute=blocked" not in text:
                            raise AssertionError("Configured commands were not hidden and denied at the native dispatcher")
                        if "VELOCITY_E2E_TARGET_OBSERVED backend" in text:
                            raise AssertionError("Blocked /server executed before the backend-connect guard")
                if args.scenario == "commands":
                    for marker in ("VELOCITY_E2E_COMMANDS_HELD roots=hidden execute=blocked",
                                   "VELOCITY_E2E_COMMANDS_AUTHENTICATED roots=restored execute=allowed"):
                        if marker not in text:
                            raise AssertionError("The same native frontend did not pass both command admission phases")
                elif backend.events:
                    raise AssertionError("Unauthenticated frontend reached the real backend")
                if engine is not None and len(engine.events) != 1:
                    raise AssertionError("Negative Engine fixture did not receive one login attempt")
            print(f"PASS Velocity {args.scenario} {args.version}: actual native/shared gate; real-backend handshakes={len(backend.events)}")
    finally:
        if process is not None and process.poll() is None:
            try:
                process.stdin.write(b"end\n"); process.stdin.flush(); process.wait(timeout=25)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill(); process.wait(timeout=5)
        (run / "backend-events.json").write_text(json.dumps(backend.events, indent=2), encoding="utf-8")
        backend.close()
        if engine is not None:
            engine.close()


if __name__ == "__main__":
    main()
