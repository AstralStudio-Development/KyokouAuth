"""Launch a selected official vanilla client with a workspace-local, credential-free test profile.

The fixture uses only an isolated loopback proxy. Success requires trusted onJoin plus the original
client's read-only world/player/screen observation; early protocols do not have player-loaded. No user
launcher profiles, credentials, worlds or identity databases are read or modified.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import uuid
import zipfile
from contextlib import contextmanager, nullcontext


def prepare_proxy(root, jdk, port):
    base = root / ".narrafork/limbo-rewrite/e2e"
    folder = base / "proxy"
    classes = base / "plugin-classes"
    (folder / "plugins").mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    artifact = root / "platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar"
    velocity = root / "run-proxy/velocity.jar"
    subprocess.run([str(jdk / "bin/javac.exe"), "-encoding", "UTF-8", "-cp", os.pathsep.join((str(velocity), str(artifact))),
                    "-d", str(classes), str(root / "tools/MultiLimboTestProxy.java")], check=True)
    metadata = json.dumps({"id": "kyokou-limbo-e2e", "name": "Kyokou Limbo E2E", "version": "1",
                           "main": "MultiLimboTestProxy", "authors": ["local test"]})
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(folder / "plugins/limbo-test.jar", "w", zipfile.ZIP_DEFLATED) as target:
        for entry in source.infolist():
            if entry.filename != "velocity-plugin.json":
                target.writestr(entry, source.read(entry))
        for file in classes.rglob("*.class"):
            target.write(file, file.relative_to(classes).as_posix())
        target.writestr("velocity-plugin.json", metadata)
    (folder / "forwarding.secret").write_text(uuid.uuid4().hex, encoding="utf-8")
    (folder / "velocity.toml").write_text('''config-version = "2.9"
bind = "127.0.0.1:25579"
motd = "Isolated Limbo test"
show-max-players = 20
online-mode = false
force-key-authentication = false
player-info-forwarding-mode = "modern"
forwarding-secret-file = "forwarding.secret"
forced-hosts = {}
[servers]
 unused = "127.0.0.1:9"
 try = ["unused"]
[advanced]
 login-ratelimit = 0
 read-timeout = 30000
 failover-on-unexpected-server-disconnect = false
[query]
 enabled = false
 port = 25579
'''.replace("25579", str(port)), encoding="utf-8")
    stats = folder / "plugins/bStats/config.txt"
    stats.parent.mkdir(exist_ok=True)
    stats.write_text("enabled=false\nlogFailedRequests=false\nlogSentData=false\nlogResponseStatusText=false\n", encoding="utf-8")


@contextmanager
def test_proxy(root, jdk, port):
    prepare_proxy(root, jdk, port)
    folder = root / ".narrafork/limbo-rewrite/e2e/proxy"
    with (folder / "console.log").open("w", encoding="utf-8") as output:
        (folder / "tmp").mkdir(exist_ok=True)
        process = subprocess.Popen([str(jdk / "bin/java.exe"), "-Dvelocity.packet-decode-logging=true", "-Djava.io.tmpdir=" + str(folder / "tmp"),
            "-Xms128M", "-Xmx1G", "-jar", str(root / "run-proxy/velocity.jar")],
            cwd=folder, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Isolated proxy exited; inspect workspace e2e/proxy/console.log")
                output.flush()
                log = (folder / "console.log").read_text(encoding="utf-8", errors="replace")
                if "Done (" in log and "LIMBO_TEST_READY" in log:
                    break
                if time.monotonic() > deadline:
                    raise RuntimeError("Isolated proxy did not finish startup")
                time.sleep(0.2)
            yield
        finally:
            if process.poll() is None:
                try:
                    process.communicate("end\n", timeout=12)
                except subprocess.TimeoutExpired:
                    process.terminate()
                    process.wait(timeout=5)


def prepare_observer(root, jdk):
    folder = root / ".narrafork/multiversion/client-observer"
    classes = folder / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(jdk / "bin/javac.exe"), "--release", "17", "-encoding", "UTF-8", "-d", str(classes),
                    str(root / "tools/LimboClientObserver.java")], check=True)
    artifact = folder / "observer.jar"
    with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as target:
        target.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: moe.ryuu.kyokouauth.tools.observer.LimboClientObserver\n\n")
        # Do not bundle stale default-package output: old signed vanilla classes also use that package.
        namespace = classes / "moe/ryuu/kyokouauth/tools/observer"
        for file in namespace.rglob("*.class"):
            target.write(file, file.relative_to(classes).as_posix())
    return artifact


def allowed(rules, features):
    result = not rules
    for rule in rules:
        condition = rule.get("os", {})
        if condition.get("name", "windows") != "windows":
            continue
        if condition.get("arch", "amd64") not in ("amd64", "x86_64"):
            continue
        if any(features.get(k, False) != v for k, v in rule.get("features", {}).items()):
            continue
        result = rule["action"] == "allow"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", type=Path, default=Path(os.environ["APPDATA"]) / ".minecraft")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--port", type=int, default=25579)
    parser.add_argument("--version", default="26.3", help="Official release version, e.g. 1.21.1, 1.21.4, 1.21.5 or 26.3")
    parser.add_argument("--proxy-log", type=Path, help="Explicit log path for an isolated Velocity or Bungee test proxy")
    parser.add_argument("--ready-marker", default="ORIGINAL_CLIENT_READY {name}", help="Trusted Limbo onJoin log marker; {name} is expanded")
    parser.add_argument("--observer", action=argparse.BooleanOptionalAction, default=True, help="Require read-only original-client world/player/screen evidence")
    parser.add_argument("--timeout", type=int, default=70)
    parser.add_argument("--hold-seconds", type=int, default=None, help="Nonnegative hold after trusted READY, observer and any required link; defaults to 12, or 0 for the legacy link probe")
    parser.add_argument("--name", default="OracleVanilla")
    parser.add_argument("--external-proxy", action="store_true", help="Use the already-running local test proxy without altering it")
    parser.add_argument("--expect-rejection", help="Require this exact server rejection in the vanilla client log")
    parser.add_argument("--expect-verification", action="store_true", help="Also require a fresh Microsoft verification link in the actual client log, without printing its code")
    args = parser.parse_args()
    if args.hold_seconds is not None and args.hold_seconds < 0:
        parser.error("--hold-seconds must be nonnegative")
    if not re.fullmatch(r"[A-Za-z0-9_]{3,16}", args.name):
        parser.error("invalid test player name")
    if args.expect_rejection and not args.external_proxy:
        parser.error("--expect-rejection requires --external-proxy")
    root = Path(__file__).resolve().parent.parent
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.version):
        parser.error("invalid official client version")
    run = root / ".narrafork/multiversion/e2e-clients" / (args.version + "-" + str(args.port))
    run.mkdir(parents=True, exist_ok=True)
    options = run / "options.txt"
    settings = options.read_text(encoding="utf-8") if options.exists() else "onboardAccessibility:false\nskipMultiplayerWarning:true\n"
    # Keep the isolated client rendered while the operator uses their own game or editor. Losing
    # focus must not open its pause menu and invalidate the unchanged screen=null/12-second check.
    settings = "\n".join(line for line in settings.splitlines() if not line.startswith("pauseOnLostFocus:"))
    options.write_text(settings + "\npauseOnLostFocus:false\n", encoding="utf-8")
    subprocess.run([sys.executable, str(root / "tools/prepare-limbo-client.py"), "--version", args.version,
                    "--minecraft", str(args.minecraft)], check=True)
    launch = json.loads((root / ".narrafork/multiversion/clients" / args.version / "launch.json").read_text(encoding="utf-8"))
    metadata = json.loads(Path(launch["metadata"]).read_text(encoding="utf-8"))
    features = {"is_quick_play_multiplayer": True, "has_custom_resolution": True}
    jars = launch["libraries"] + [launch["client"]]
    observer = prepare_observer(root, args.jdk) if args.observer else None
    name = args.name
    player_uuid = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)
    variables = {
        "auth_player_name": name, "version_name": args.version, "game_directory": str(run),
        "assets_root": launch["assets"], "assets_index_name": metadata["assetIndex"]["id"],
        "auth_uuid": player_uuid.hex, "auth_access_token": "0", "clientid": "", "auth_xuid": "0",
        "user_type": "msa", "user_properties": "{}",
        "version_type": "release", "resolution_width": "854", "resolution_height": "480",
        "quickPlayMultiplayer": "127.0.0.1:" + str(args.port), "natives_directory": str(run / "natives"),
        "launcher_name": "KyokouAuth-Local-E2E", "launcher_version": "1", "classpath": os.pathsep.join(jars),
    }
    for part in ("java", "jna", "lwjgl", "netty"):
        (run / "natives" / part).mkdir(parents=True, exist_ok=True)
    def expand(items):
        result = []
        for item in items:
            values = [item] if isinstance(item, str) else item["value"] if allowed(item["rules"], features) else []
            if isinstance(values, str):
                values = [values]
            for value in values:
                result.append(re.sub(r"\$\{([^}]+)\}", lambda m: variables[m[1]], value))
        return result
    observer_args = ["-javaagent:" + str(observer) + "=" + launch["observer"]] if observer else []
    command = [str(args.jdk / "bin/java.exe"), "-Xms128M", "-Xmx768M", "-XX:ActiveProcessorCount=2", "-Duser.language=en", "-Duser.country=US",
               "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9", "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9",
               "-Djava.io.tmpdir=" + str(run), *observer_args, *expand(metadata["arguments"]["jvm"]), metadata["mainClass"],
               *expand(metadata["arguments"]["game"])]
    proxy_log = args.proxy_log.resolve() if args.proxy_log else root / ("run-proxy/logs/latest.log" if args.external_proxy else ".narrafork/limbo-rewrite/e2e/proxy/logs/latest.log")
    start = time.monotonic()
    success_at = None
    context = nullcontext() if args.external_proxy else test_proxy(root, args.jdk, args.port)
    with context, (run / "console.log").open("w", encoding="utf-8") as log:
        offset = proxy_log.stat().st_size
        launch_time = time.time()
        process = subprocess.Popen(command, cwd=run, stdout=log, stderr=subprocess.STDOUT)
        try:
            while time.monotonic() - start < args.timeout:
                if process.poll() is not None:
                    raise RuntimeError(f"Original client exited with {process.returncode}; see workspace e2e/client/console.log")
                with proxy_log.open("r", encoding="utf-8", errors="replace") as file:
                    file.seek(offset)
                    recent = file.read()
                if args.expect_rejection:
                    client_log = run / "logs/latest.log"
                    client_text = client_log.read_text(encoding="utf-8", errors="replace") if client_log.exists() and client_log.stat().st_mtime >= launch_time else ""
                    # Vanilla does not log every LOGIN-phase rejection; Velocity records the exact
                    # reason on the initial connection. This proves server rejection, not GUI text rendering.
                    server_rejected = any("has disconnected:" in line and args.expect_rejection in line for line in recent.splitlines())
                    if args.expect_rejection in client_text or server_rejected:
                        if "is a Mojang-owned name; asking the proxy for a session check" in recent:
                            raise RuntimeError("Rejected name still triggered an upstream session check")
                        print("PASS: original client's connection received the required rejection; no upstream session check was triggered")
                        return 0
                ready = (args.ready_marker.format(name=name) in recent or any(
                    "Limbo ready for " + name + " (" in line for line in recent.splitlines()))
                if args.observer:
                    log.flush()
                    client_console = (run / "console.log").read_text(encoding="utf-8", errors="replace")
                    observations = [line for line in client_console.splitlines() if "CLIENT_OBSERVER " in line]
                    ready = ready and bool(observations) and "CLIENT_OBSERVER level=true player=true screen=null readonly=true" in observations[-1]
                    if "CLIENT_OBSERVER_ERROR " in client_console:
                        raise RuntimeError("Read-only client observer failed; no render evidence may be claimed")
                if recent.count("TEST_TITLE_ONCE " + name) > 1:
                    raise RuntimeError("Title was emitted repeatedly for one verification session")
                if ready:
                    if "TEST_CLIENT_LEFT " + name in recent or re.search(
                            r"\[connected player\] " + re.escape(name) + r" .*has disconnected", recent):
                        raise RuntimeError("Original client disconnected after ready")
                    if args.expect_verification:
                        client_log = run / "logs/latest.log"
                        text = client_log.read_text(encoding="utf-8", errors="replace") if client_log.exists() and client_log.stat().st_mtime >= launch_time else ""
                        if not any("[CHAT]" in line and "microsoft.com/link" in line for line in text.splitlines()):
                            success_at = None
                            time.sleep(0.5)
                            continue
                    if success_at is None:
                        success_at = time.monotonic()
                    # Preserve the old quick-link probe unless a controlled matrix explicitly requests a hold.
                    required_wait = args.hold_seconds if args.hold_seconds is not None else (0 if args.expect_verification else 12)
                    if time.monotonic() - success_at >= required_wait:
                        if args.expect_verification:
                            print(f"PASS: original vanilla {args.version} is ready, verification link observed (code redacted), held for {required_wait} seconds")
                        else:
                            print(f"PASS: original vanilla {args.version} joined loopback:{args.port}, read-only world/player/screen evidence={args.observer}, remained connected for {required_wait} seconds")
                        return 0
                else:
                    success_at = None
                time.sleep(0.5)
            raise RuntimeError("Original client did not report readiness before timeout; inspect workspace logs")
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
