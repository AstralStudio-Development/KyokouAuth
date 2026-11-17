"""Pinned official SkinsRestorer 15.12.6 + Floodgate + real Velocity gate isolation regression.

inspect/self-test never start a JVM. run is for the primary narrator's reserved JVM window:
one 128 MiB proxy plus one 64 MiB client, with compiler JVMs finished before proxy startup.
Only the public plugin Jars are read from run-*; no user config, skins database, API key, token
or production Floodgate key is read. All generated data stays under .narrafork.

Java uses UUID(0,xuid), with Floodgate installed, and an explicitly assigned synthetic custom
skin in SR's FILE storage. A different skin at OfflinePlayer:VelOracle makes a late UUID rewrite
observable. The native NORMAL listener must replace textures while preserving marker/nonce.
Bedrock uses the real Floodgate crypto/channel proof and an unassigned, nonempty synthetic
profile texture at the public post-Floodgate seam; SR must not replace it with a Java skin.
No real skin rendering, skin upload, Xbox/Bedrock client or online skin-changing is claimed.
SR 15.12.6 uses FloodgateApi.isFloodgateId for its unassigned-skin fallback: a Java UUID(0,xuid)
without an explicit SR skin assignment is therefore outside automatic default-skin coverage.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/skinsrestorer-e2e"
SKINS_SHA256 = "a85b4a370f988741c9a38f4d0498262edacbf3220314790ac1c127461996359c"
FLOODGATE_SHA256 = "649835022de37ff09255fc626d4bfe7bc2a3b926d07b0dfaf1f51e12bf37001d"
# Fixed native fixture identity, shared with FloodgateE2eCrypto.java (not read from runtime logs).
STABLE = str(uuid.UUID(int=2535412345678901))
PROBE_MAIN = "moe.ryuu.kyokouauth.tools.skinsrestorer.SkinsRestorerVelocityProbe"
NATIVE_UPDATE = "SR_E2E_NATIVE_UPDATE nonce=preserved marker=preserved uuid=preserved name=preserved textures=replaced input=unchanged"
LIMITATION = ("Synthetic profile/storage transport only, not real client skin rendering/upload. "
              "SR 15.12.6's unassigned UUID(0,xuid) fallback uses Floodgate isFloodgateId; "
              "Java automatic/default skin lookup without an explicit SR assignment is not covered.")


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "tools" / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def official_jar(path, expected, plugin_id, version=None):
    if not path.is_file() or digest(path) != expected:
        raise RuntimeError(f"Missing or checksum-mismatched pinned official Jar: {path}")
    with zipfile.ZipFile(path) as source:
        metadata = json.loads(source.read("velocity-plugin.json"))
        if metadata.get("id") != plugin_id or version is not None and metadata.get("version") != version:
            raise AssertionError("Unexpected official Velocity plugin descriptor")
        return metadata


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
    (data / "noautoupdate.txt").write_text("Isolated pinned fixture; never update or contact external skin APIs.\n", encoding="utf-8")
    metrics = run / "plugins/bStats"
    metrics.mkdir(exist_ok=True)
    (metrics / "config.txt").write_text("enabled=false\n", encoding="utf-8")


def compile_probe(args, floodgate):
    classes = BASE / "probe-classes" / uuid.uuid4().hex[:12]
    classes.mkdir(parents=True)
    command = [floodgate.executable(args.jdk, "javac"), "-J-Xmx128M", "-J-XX:ActiveProcessorCount=2",
               "-encoding", "UTF-8", "--release", "17", "-proc:none", "-cp",
               os.pathsep.join(str(path.resolve()) for path in (args.proxy, args.skinsrestorer_jar, args.floodgate_jar)),
               "-d", str(classes.resolve()), str(ROOT / "tools/SkinsRestorerVelocityProbe.java")]
    subprocess.run(command, cwd=BASE, check=True, timeout=90)
    target = classes.parent / f"skinsrestorer-probe-{classes.name}.jar"
    metadata = {"id": "skinsrestorer-e2e-probe", "name": "SkinsRestorer isolated native probe", "version": "1",
                "main": PROBE_MAIN, "authors": [],
                "dependencies": [{"id": "skinsrestorer", "optional": False}, {"id": "floodgate", "optional": False}]}
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as output:
        output.writestr("velocity-plugin.json", json.dumps(metadata))
        for path in sorted(classes.rglob("*.class")):
            name = path.relative_to(classes).as_posix()
            if not name.startswith(PROBE_MAIN.replace(".", "/")):
                raise AssertionError("The probe Jar must not bundle third-party SDK classes")
            output.writestr(name, path.read_bytes())
    return target


def require_unique(text, marker):
    if text.count(marker) != 1:
        raise AssertionError(f"Expected exactly one native observation: {marker}")
    return text.index(marker)


def assert_trace(case, text):
    """Observable contract; missing/duplicated/stale-phase markers are failures, never timeouts-as-pass."""
    for marker in ("SR_E2E_FAILED", "VELOCITY_E2E_START_FAILED", "VELOCITY_E2E_BEDROCK_FORBIDDEN"):
        if marker in text:
            raise AssertionError(f"Native contract failed: {marker}")
    bedrock = case == "bedrock"
    name, path = (".CryptoBedrock", "BEDROCK") if bedrock else ("VelOracle", "JAVA")
    texture, nonce = ("bedrock-preserved", "absent") if bedrock else ("stable-authority", "present")
    ordered = [
        NATIVE_UPDATE,
        f"SR_E2E_SEEDED version=15.12.6 storage=FILE stable={STABLE}",
        f"SR_E2E_FLOODGATE_CLASSIFICATION uuidShape=true current={str(bedrock).lower()} path={path}",
        f"SR_E2E_BEFORE_NORMAL uuid={STABLE} name={name} marker=1 nonce=",
        f"SR_E2E_AFTER_NORMAL uuid={STABLE} name={name} marker=1 texture={texture} nonce=preserved path={path}",
        f"SR_E2E_FINAL_PROFILE uuid={STABLE} name={name} marker=1 texture={texture} nonce={nonce} path={path}",
        f"SR_E2E_LOGIN_PROFILE uuid={STABLE} name={name} marker=1 texture={texture} nonce={nonce} path={path}",
        f"SR_E2E_NATIVE_POST_LOGIN uuid={STABLE} name={name} path={path}",
    ]
    indices = [require_unique(text, marker) for marker in ordered]
    if indices != sorted(indices):
        raise AssertionError("Native pre-NORMAL/async SR/final profile/login phase order changed")
    if bedrock:
        require_unique(text, f"VELOCITY_E2E_BEDROCK_AUTHENTICATED uuid={STABLE} name={name} javaWrites=0")
        require_unique(text, f"VELOCITY_E2E_SKINS_PROFILE phase=bedrock-authenticated uuid={STABLE} name={name} marker=1 textures=1 nonce=0")
        for phase in (1, 2, 3, 4):
            require_unique(text, f"VELOCITY_E2E_BEDROCK_PROBE phase={phase} kind=VERIFIED")
        if "VELOCITY_E2E_SESSION" in text or "VELOCITY_E2E_TITLE" in text or "VELOCITY_E2E_READY" in text:
            raise AssertionError("A proven Bedrock player entered the Java verification/nonce path")
    else:
        held = require_unique(text, "VELOCITY_E2E_COMMANDS_HELD roots=hidden execute=blocked")
        admitted = require_unique(text, "VELOCITY_E2E_COMMANDS_AUTHENTICATED roots=restored execute=allowed")
        if not indices[-1] < held < admitted:
            raise AssertionError("The same frontend did not pass held then authenticated command admission")
        for phase in ("held", "authenticated"):
            require_unique(text, f"VELOCITY_E2E_SKINS_PROFILE phase={phase} uuid={STABLE} name={name} marker=1 textures=1 nonce=1")
        require_unique(text, f"VELOCITY_E2E_SESSION {STABLE}")
        require_unique(text, "VELOCITY_E2E_TITLE VelOracle")
        if "VELOCITY_E2E_BEDROCK_AUTHENTICATED" in text:
            raise AssertionError("A Java XUID UUID shape was mistaken for native Bedrock proof")


def self_test():
    """Unit-test only the fail-closed trace oracle. These replay strings are NEVER runtime evidence."""
    rejected = 0
    for case in ("java", "bedrock"):
        bedrock = case == "bedrock"
        name, path = (".CryptoBedrock", "BEDROCK") if bedrock else ("VelOracle", "JAVA")
        texture, nonce = ("bedrock-preserved", "absent") if bedrock else ("stable-authority", "present")
        lines = [NATIVE_UPDATE, f"SR_E2E_SEEDED version=15.12.6 storage=FILE stable={STABLE} decoy=unused",
                 f"SR_E2E_FLOODGATE_CLASSIFICATION uuidShape=true current={str(bedrock).lower()} path={path}",
                 f"SR_E2E_BEFORE_NORMAL uuid={STABLE} name={name} marker=1 nonce=0 path={path}",
                 f"SR_E2E_AFTER_NORMAL uuid={STABLE} name={name} marker=1 texture={texture} nonce=preserved path={path}",
                 f"SR_E2E_FINAL_PROFILE uuid={STABLE} name={name} marker=1 texture={texture} nonce={nonce} path={path}",
                 f"SR_E2E_LOGIN_PROFILE uuid={STABLE} name={name} marker=1 texture={texture} nonce={nonce} path={path}",
                 f"SR_E2E_NATIVE_POST_LOGIN uuid={STABLE} name={name} path={path}"]
        if bedrock:
            lines += [f"VELOCITY_E2E_BEDROCK_PROBE phase={phase} kind=VERIFIED" for phase in (1, 2, 3, 4)]
            lines += [f"VELOCITY_E2E_SKINS_PROFILE phase=bedrock-authenticated uuid={STABLE} name={name} marker=1 textures=1 nonce=0",
                      f"VELOCITY_E2E_BEDROCK_AUTHENTICATED uuid={STABLE} name={name} javaWrites=0"]
        else:
            lines += ["VELOCITY_E2E_TITLE VelOracle", f"VELOCITY_E2E_SKINS_PROFILE phase=held uuid={STABLE} name={name} marker=1 textures=1 nonce=1",
                      "VELOCITY_E2E_COMMANDS_HELD roots=hidden execute=blocked", f"VELOCITY_E2E_SESSION {STABLE}",
                      f"VELOCITY_E2E_SKINS_PROFILE phase=authenticated uuid={STABLE} name={name} marker=1 textures=1 nonce=1",
                      "VELOCITY_E2E_COMMANDS_AUTHENTICATED roots=restored execute=allowed"]
        baseline = "\n".join(lines)
        assert_trace(case, baseline)
        mutants = [baseline.replace(line, "", 1) for line in lines]
        mutants += [baseline + "\n" + lines[3], baseline + "\nSR_E2E_FAILED",
                    baseline.replace(STABLE, "11111111-1111-1111-1111-111111111111", 1),
                    baseline.replace(f"texture={texture}", "texture=wrong-authority", 1),
                    baseline.replace(f"nonce={nonce}", "nonce=corrupt", 1),
                    baseline.replace("uuidShape=true", "uuidShape=false", 1),
                    baseline.replace(lines[3], "MOVE").replace(lines[4], lines[3]).replace("MOVE", lines[4])]
        for mutant in mutants:
            try:
                assert_trace(case, mutant)
            except AssertionError:
                rejected += 1
            else:
                raise AssertionError(f"The {case} oracle accepted a missing/corrupted/duplicated native observation")
    print(f"PASS assertion self-test: {rejected} negative mutations rejected; no native runtime executed")


@contextmanager
def runtime(args, run, case, velocity, floodgate):
    console = run / "console.log"
    scenario = "bedrock" if case == "bedrock" else "commands"
    command = [floodgate.executable(args.jdk, "java"), "-Xms64M", "-Xmx128M", "-XX:ActiveProcessorCount=2",
               f"-Djava.io.tmpdir={run / 'tmp'}", "-Dhttp.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
               "-Dhttps.proxyHost=127.0.0.1", "-Dhttps.proxyPort=9", "-Dkyokou.e2e.skinsrestorer=true",
               f"-Dkyokou.e2e.skinsrestorerJar={(run / 'plugins/skinsrestorer.jar').resolve()}",
               f"-Dkyokou.e2e.skinsrestorerSha256={SKINS_SHA256}", f"-Dkyokou.e2e.scenario={scenario}",
               "-jar", str(args.proxy.resolve())]
    with console.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            velocity.wait_for(process, console, "Done (", args.timeout)
            velocity.wait_for(process, console, f"VELOCITY_E2E_STARTED {scenario}", args.timeout)
            velocity.wait_for(process, console, "SR_E2E_SEEDED version=15.12.6", args.timeout)
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
                raise RuntimeError(f"Native proxy did not shut down normally: {process.returncode}")


def run_case(args, case, probe_jar, gate_jar, crypto_classpath, velocity, floodgate):
    run = BASE / "runtime" / f"{case}-{uuid.uuid4().hex[:12]}"
    run.mkdir(parents=True)
    backend = floodgate.fixtures().Recorder("kick" if case == "bedrock" else "stall")
    evidence = {"status": "failed", "case": case, "skinsrestorerVersion": "15.12.6", "skinsrestorerSha256": SKINS_SHA256,
                "floodgateSha256": FLOODGATE_SHA256, "proxySha256": digest(args.proxy), "artifactSha256": digest(args.jar),
                "sourceSha256": {name: digest(ROOT / name) for name in ("tools/SkinsRestorerVelocityProbe.java",
                    "tools/skinsrestorer-e2e.py", "tools/velocity-e2e.py",
                    "platform-velocity/src/test/kotlin/moe/ryuu/kyokouauth/velocity/VelocityE2ePlugin.kt")},
                "officialNativeTextureContractVerified": False, "realSkinRenderingVerified": False,
                "realBedrockXboxClientVerified": False, "realSkinUploadVerified": False, "limitation": LIMITATION}
    try:
        port = velocity.free_port()
        floodgate.configure(run, "velocity", port, backend.port)
        configure_skins(run)
        (run / "isolated-fixture.json").write_text(json.dumps({"newIsolatedFixture": True, "platform": "velocity"}), encoding="utf-8")
        for source, name in ((args.skinsrestorer_jar, "skinsrestorer.jar"), (args.floodgate_jar, "floodgate.jar"),
                             (gate_jar, "gate-test.jar"), (probe_jar, "skinsrestorer-probe.jar")):
            shutil.copyfile(source, run / "plugins" / name)
        with runtime(args, run, case, velocity, floodgate) as console:
            if case == "bedrock":
                command = [floodgate.executable(args.jdk, "java"), "-Xms32M", "-Xmx64M", "-XX:ActiveProcessorCount=2",
                           f"-Djava.io.tmpdir={run / 'tmp'}", f"-Dkyokou.floodgate.isolatedRoot={BASE.resolve()}",
                           "-cp", crypto_classpath, floodgate.MAIN_CLASS, str(run.resolve()), str(port), "valid"]
                result = subprocess.run(command, cwd=run, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=45)
                (run / "client.log").write_text(result.stdout + result.stderr, encoding="utf-8")
                if result.returncode != 0:
                    raise RuntimeError(f"Official crypto client failed; inspect {(run / 'client.log').relative_to(ROOT)}")
                lines = [line.removeprefix("FLOODGATE_CRYPTO_RESULT ") for line in result.stdout.splitlines()
                         if line.startswith("FLOODGATE_CRYPTO_RESULT ")]
                if len(lines) != 1:
                    raise AssertionError("Expected a unique native Floodgate crypto client result")
                probe = json.loads(lines[0])
                text = console.read_text(encoding="utf-8", errors="replace")
                floodgate.assert_result("velocity", "valid", probe, text, backend.events)
                evidence["client"] = probe
            else:
                command = [sys.executable, str(ROOT / "tools/limbo-e2e.py"), "--external-proxy", "--version", "26.3",
                           "--port", str(port), "--jdk", str(args.jdk), "--name", "VelOracle", "--timeout", str(args.timeout),
                           "--proxy-log", str(console), "--expect-rejection", "COMMAND_GUARD_COMPLETE"]
                result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace",
                                        timeout=args.timeout + 45)
                (run / "client.log").write_text(result.stdout + result.stderr, encoding="utf-8")
                if result.returncode != 0:
                    raise RuntimeError(f"Native Java/Limbo client failed; inspect {(run / 'client.log').relative_to(ROOT)}")
                text = console.read_text(encoding="utf-8", errors="replace")
            assert_trace(case, text)
            evidence.update(status="passed", officialNativeTextureContractVerified=True)
        print(f"PASS SkinsRestorer 15.12.6 {case}: native NORMAL/final/login texture contract; {run.relative_to(ROOT)}")
        return evidence
    except Exception as failure:
        evidence.update(status="failed", failure=type(failure).__name__)
        raise
    finally:
        backend.close()
        evidence["backendEvents"] = backend.events
        (run / "result.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("inspect", "self-test", "run"), help="Only run starts/compiler JVMs; reserve a primary-narrator window")
    parser.add_argument("--case", choices=("java", "bedrock", "both"), default="both")
    parser.add_argument("--skinsrestorer-jar", type=Path, default=ROOT / "run-live/plugins/SkinsRestorer.jar")
    parser.add_argument("--floodgate-jar", type=Path, default=ROOT / ".narrafork/floodgate-e2e/deps/floodgate-velocity-2.2.5-b141.jar")
    parser.add_argument("--jar", type=Path, default=ROOT / "platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar")
    parser.add_argument("--proxy", type=Path, default=ROOT / "run-proxy/velocity.jar")
    parser.add_argument("--jdk", type=Path, default=Path("C:/Program Files/Zulu/zulu-25"))
    parser.add_argument("--client-classpath-file", type=Path, default=ROOT / "limbo/protocols/777/build/generated/limbo-codec/tool-classpath.txt")
    parser.add_argument("--timeout", type=int, default=90)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 300:
        parser.error("--timeout must be between 1 and 300 seconds")
    if args.command == "self-test":
        self_test()
        return
    metadata = official_jar(args.skinsrestorer_jar, SKINS_SHA256, "skinsrestorer", "15.12.6")
    official_jar(args.floodgate_jar, FLOODGATE_SHA256, "floodgate")
    if args.command == "inspect":
        print(json.dumps({"skinsrestorer": metadata, "sha256": SKINS_SHA256,
                          "floodgateSha256": FLOODGATE_SHA256, "startsJava": False, "limitation": LIMITATION}, indent=2))
        return
    for path in (args.jar, args.proxy):
        if not path.is_file():
            parser.error(f"Missing required build/distribution: {path}")
    BASE.mkdir(parents=True, exist_ok=True)
    velocity, floodgate = load_tool("velocity-e2e"), load_tool("floodgate-e2e")
    velocity.BASE.mkdir(parents=True, exist_ok=True)
    gate_jar = velocity.test_jar(args.jar, required_plugins=("skinsrestorer-e2e-probe", "floodgate"))
    probe_jar = compile_probe(args, floodgate)
    crypto_classpath = None
    if args.case != "java":
        upstream = floodgate.client_classpath(args.client_classpath_file)
        crypto_classpath = floodgate.compile_probe(args, "velocity", args.floodgate_jar, upstream)
    cases = ("java", "bedrock") if args.case == "both" else (args.case,)
    results = [run_case(args, case, probe_jar, gate_jar, crypto_classpath, velocity, floodgate) for case in cases]
    (BASE / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(LIMITATION)


if __name__ == "__main__":
    main()
