"""Check first bind, rename, and old-name reuse with real Velocity/Bungee and pinned LuckPerms.

Uses test-only synthetic admission Services; never opens a launcher account, production
configuration, or existing LP database. Five loopback stages share one fresh SQLite LP
store. Native 1.21.1 login success and stored permissions are not real-account or GUI proof.
"""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import socket
import sqlite3
import struct
import subprocess
import tempfile
import threading
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / '.narrafork/luckperms-proxy-e2e'
LP_SHA256 = {
    'velocity': '9234e4573dc53dce3e2acbfe820538ce57b4ae633a52f3ac7f08545dee486b31',
    'bungee': '812d3260b54d992353aa7a6c1851680d770e77271b41d9d4b670108f9e1876a3',
}
BUNGEE_SHA256 = '1d9dbc31b57b22a37553f160ac1b61762717e9e4a6a1341f3dd27a1f6f2671fd'
LIB_SHA256 = {
    'adventure-4.21.1.jar': '910265674814c5d4d1464b244f8dea8b2da4a6df6ceb9e0bbc1d27aa808fe981',
    'asm-9.8.jar': '876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051',
    'asm-commons-9.8.jar': '3301a1c1cb4c59fcc5292648dac1d7c5aed4c0f067dfbe88873b8cdfe77404f4',
    'bytebuddy-1.15.11.jar': 'fa08998aae1e7bdae83bde0712c50e8444d71c0e0c196bb2247ade8d4ad0eb90',
    'caffeine-3.2.0.jar': 'ec411dfdf0c03f25218648ce89861630b71680e5858a9a7278ebac8e55cab3d7',
    'configurate-core-3.7.3.jar': 'd3a47758356207ce16b529074ee755f1348fc45d5e4320b27da6fc2fb3efa363',
    'configurate-yaml-3.7.3.jar': '6b4e2f4642e18a022a886fe0755bcaedcd58881409ee4d6afe404db12f4e543b',
    'event-3.0.0.jar': 'ca3bdd4dd03292d97788511014b1c2dea630c2deff0db09ded973c4384a521a8',
    'h2-driver-2.1.214.jar': 'd623cdc0f61d218cf549a8d09f1c391ff91096116b22e2475475fce4fbe72bd0',
    'jar-relocator-1.7.jar': '6f7d1184e17a90788797e3b9b2e34b87ff9e02bd623851052d78709071c3bb82',
    'okhttp-3.14.9.jar': '2570fab55515cbf881d7a4ceef49fc515490bc027057e666776a2832465aeca0',
    'okio-1.17.6.jar': '8e88b055523cc80613dfb844d598740e10ada62f4bd98304cdd140c5854cc3b6',
    'snakeyaml-1.33.jar': '11ff459788f0a2d781f56a4a86d7e69202cebacd0273d5269c4ae9f02f3fd8f0',
    'sqlite-driver-3.49.1.0.jar': '5c8609d2ca341deb8c6f71778974b5ba4995c7d32d7c7c89d9392a3e72c39291',
}
NAME = 'LpFirstProbe'
RENAMED_NAME = 'LpRenamedProbe'
XUID_A = 2535412345678901
XUID_B = 2535412345678902
STABLE = str(uuid.UUID(int=XUID_A))
STABLE_B = str(uuid.UUID(int=XUID_B))
SENTINEL = 'kyokou.e2e.synthetic_sentinel'


def varint(value):
    result = bytearray()
    while True:
        part = value & 127
        value >>= 7
        result.append(part | (128 if value else 0))
        if not value:
            return bytes(result)


def text(value):
    data = value.encode('utf-8')
    return varint(len(data)) + data


def read_varint(stream):
    value = 0
    for shift in range(0, 35, 7):
        part = stream.read(1)
        if not part:
            raise EOFError('Proxy closed before login success')
        value |= (part[0] & 127) << shift
        if not part[0] & 128:
            return value
    raise ValueError('Oversized VarInt')


def login(port, name):
    claimed = uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3)
    with socket.create_connection(('127.0.0.1', port), timeout=10) as connection:
        def send(payload):
            connection.sendall(varint(len(payload)) + payload)
        send(varint(0) + varint(767) + text('localhost') + struct.pack('>H', port) + varint(2))
        send(varint(0) + text(name) + claimed.bytes)
        with connection.makefile('rb') as incoming:
            size = read_varint(incoming)
            packet_id = read_varint(incoming)
            if packet_id != 2:
                raise AssertionError(f'Expected login success, got packet {packet_id}; length={size}')
            body = incoming.read(size - 1)
            if len(body) != size - 1:
                raise EOFError('Incomplete login success')
            profile = io.BytesIO(body)
            native_uuid = str(uuid.UUID(bytes=profile.read(16)))
            name_size = read_varint(profile)
            native_name = profile.read(name_size)
            if len(native_name) != name_size:
                raise EOFError('Incomplete native profile name')
            return native_uuid, native_name.decode('utf-8')


class LoginBackend:
    """Bungee needs backend login success before frontend success; echo only observed identity.

    This bounded 767 fixture never enters PLAY, selects an expected UUID, or writes LP state.
    """
    def __init__(self, port):
        self.listener = socket.socket()
        self.listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.listener.bind(('127.0.0.1', port))
        self.listener.listen()
        self.listener.settimeout(0.2)
        self.stopped = threading.Event()
        self.events = []
        self.connections = []
        self.workers = []
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    @staticmethod
    def packet(incoming):
        size = read_varint(incoming)
        if size > 1 << 21:
            raise ValueError('Oversized backend packet')
        data = incoming.read(size)
        if len(data) != size:
            raise EOFError('Incomplete backend packet')
        return io.BytesIO(data)

    @staticmethod
    def string(incoming):
        size = read_varint(incoming)
        if size > 32767:
            raise ValueError('Oversized backend string')
        value = incoming.read(size)
        if len(value) != size:
            raise EOFError('Incomplete backend string')
        return value.decode('utf-8')

    def run(self):
        while not self.stopped.is_set():
            try:
                connection, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            self.connections.append(connection)
            worker = threading.Thread(target=self.handle, args=(connection,), daemon=True)
            self.workers.append(worker)
            worker.start()

    def handle(self, connection):
        connection.settimeout(10)
        try:
            with connection.makefile('rb') as incoming:
                handshake = self.packet(incoming)
                if read_varint(handshake) != 0 or read_varint(handshake) != 767:
                    raise ValueError('Backend fixture supports only native 767 handshakes')
                forwarded = self.string(handshake).split('\x00')
                handshake.read(2)
                if read_varint(handshake) != 2 or len(forwarded) < 3:
                    raise ValueError('Expected legacy-forwarded login handshake')
                hello = self.packet(incoming)
                if read_varint(hello) != 0:
                    raise ValueError('Expected backend HELLO')
                name = self.string(hello)
                actual_uuid = uuid.UUID(bytes=hello.read(16))
                forwarded_uuid = uuid.UUID(forwarded[2])
                if actual_uuid != forwarded_uuid:
                    raise ValueError('Backend HELLO UUID differs from actual proxy forwarding')
                properties = json.loads(forwarded[3]) if len(forwarded) >= 4 else []
                event = {'name': name, 'loginUuid': str(actual_uuid), 'forwardedUuid': str(forwarded_uuid),
                         'propertyNames': [entry['name'] for entry in properties]}
                self.events.append(event)
                # UUID, name, empty property array, strict-error-handling (protocol 767).
                payload = varint(2) + actual_uuid.bytes + text(name) + varint(0) + b'\x01'
                connection.sendall(varint(len(payload)) + payload)
                while not self.stopped.is_set() and incoming.read(1):
                    pass
        except (EOFError, OSError, ValueError) as failure:
            if not self.stopped.is_set():
                self.events.append({'error': f'{type(failure).__name__}: {failure}'})
        finally:
            connection.close()

    def close(self):
        self.stopped.set()
        self.listener.close()
        for connection in self.connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connection.close()
        self.thread.join(timeout=1)
        for worker in self.workers:
            worker.join(timeout=1)


@contextmanager
def backend_login_fixture(platform, port):
    backend = LoginBackend(port) if platform == 'bungee' else None
    try:
        yield backend
    finally:
        if backend is not None:
            backend.close()


def free_port(excluded=()):
    for _ in range(128):
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        if port not in excluded:
            return port
    raise RuntimeError('Could not allocate a fresh loopback port without reusing a previous stage')


def wait_for_startup(process, console, start_marker, listening_marker, timeout=100):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        content = console.read_text(encoding='utf-8', errors='replace') if console.exists() else ''
        lowered = content.lower()
        if any(marker in lowered for marker in ('could not bind', 'bindexception', 'address already in use')):
            raise RuntimeError(f'Proxy listener bind failed; inspect {console.relative_to(ROOT)}')
        if start_marker in content and listening_marker in content:
            return
        if process.poll() is not None:
            raise RuntimeError(f'Proxy exited {process.returncode}; inspect {console.relative_to(ROOT)}')
        time.sleep(0.1)
    raise TimeoutError(f'Proxy did not reach startup/listener readiness; inspect {console.relative_to(ROOT)}')


def stop(process):
    if process.poll() is not None:
        return
    try:
        process.communicate('end\n', timeout=25)
    except subprocess.TimeoutExpired:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def database_rows(database):
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=1) as connection:
        players = connection.execute('SELECT uuid, username FROM luckperms_players ORDER BY uuid').fetchall()
        sentinels = connection.execute(
            'SELECT uuid, value FROM luckperms_user_permissions WHERE permission=? ORDER BY uuid, value',
            (SENTINEL,),
        ).fetchall()
    return players, sentinels


def grant_sentinel(process, database, timeout=20, command_prefix='lpv'):
    if process.stdin is None or process.poll() is not None:
        raise RuntimeError('Owned fixture console is unavailable')
    process.stdin.write(f'{command_prefix} user {STABLE} permission set {SENTINEL} true\n')
    process.stdin.flush()
    deadline = time.monotonic() + timeout
    observed = []
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError('Owned fixture exited before LP saved the sentinel')
        try:
            _, observed = database_rows(database)
            if observed == [(STABLE, 1)]:
                return observed
        except sqlite3.OperationalError:
            pass
        time.sleep(0.1)
    raise TimeoutError(f'LP console command did not persist the A-only sentinel: {observed}')


def bungee_test_jar(artifact, target):
    classes = ROOT / 'platform-bungee/build/classes/kotlin/test/moe/ryuu/kyokouauth/bungee'
    entries = sorted(classes.glob('BungeeE2e*.class'))
    bootstrap = classes / 'BungeeE2ePlugin.class'
    if not bootstrap.is_file() or not entries:
        raise RuntimeError('Build :platform-bungee:testClasses first')
    if b'kyokou.e2e.xuid' not in bootstrap.read_bytes():
        raise RuntimeError('Rebuild BungeeE2ePlugin with synthetic XUID selection before this five-stage test')
    with zipfile.ZipFile(artifact) as source:
        if 'bungee.yml' not in source.namelist() or 'moe/ryuu/kyokouauth/bungee/KyokouAuthBungee.class' not in source.namelist():
            raise RuntimeError('Bungee regression requires the production Bungee shadowJar, not a Velocity/test JAR')
        if any('/BungeeE2e' in name for name in source.namelist()):
            raise RuntimeError('Production Bungee artifact contains a test bootstrap')
        with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as output:
            for entry in source.infolist():
                if entry.filename != 'bungee.yml':
                    output.writestr(entry, source.read(entry))
            for path in entries:
                output.write(path, 'moe/ryuu/kyokouauth/bungee/' + path.name)
            output.writestr('bungee.yml', 'name: KyokouAuth\nversion: e2e\n'
                            'main: moe.ryuu.kyokouauth.bungee.BungeeE2ePlugin\ndepends: [LuckPerms]\n')
    return target


def configure_proxy(run, platform, port, backend):
    if platform == 'bungee':
        (run / 'config.yml').write_text(f'''online_mode: false
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
  motd: Isolated LuckPerms Bungee identity test
  max_players: 20
  priorities: [backend]
  force_default_server: true
  forced_hosts: {{}}
  query_enabled: false
servers:
  backend:
    address: 127.0.0.1:{backend}
    motd: Login-only recording backend
    restricted: false
''', encoding='utf-8')
    else:
        (run / 'velocity.toml').write_text(f'''config-version = "2.9"
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
login-ratelimit = 0
compression-threshold = -1
''', encoding='utf-8')
        (run / 'forwarding.secret').write_text('isolated-synthetic-forwarding-secret', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=('velocity', 'bungee'), default='velocity')
    parser.add_argument('--jar', type=Path, help='Production shadowJar for the selected platform')
    parser.add_argument('--jdk', type=Path, default=Path('C:/Program Files/Zulu/zulu-25'))
    args = parser.parse_args()
    platform_name = 'Bungee' if args.platform == 'bungee' else 'Velocity'
    if args.jar is None:
        args.jar = ROOT / f'platform-{args.platform}/build/libs/KyokouAuth-{platform_name}-0.1.1.jar'
    dependency = BASE / f'deps/LuckPerms-{platform_name}-5.5.91.jar'
    if hashlib.sha256(dependency.read_bytes()).hexdigest() != LP_SHA256[args.platform]:
        raise RuntimeError(f'Official LuckPerms {platform_name} 5.5.91 checksum mismatch')
    proxy = ROOT / ('.narrafork/bungee-e2e/deps/BungeeCord-2111.jar' if args.platform == 'bungee' else 'run-proxy/velocity.jar')
    if args.platform == 'bungee' and hashlib.sha256(proxy.read_bytes()).hexdigest() != BUNGEE_SHA256:
        raise RuntimeError('Official BungeeCord 2111 checksum mismatch')
    command_prefix = 'lpb' if args.platform == 'bungee' else 'lpv'
    start_marker = 'BUNGEE_E2E_STARTED' if args.platform == 'bungee' else 'VELOCITY_E2E_STARTED'
    listening_marker = 'Listening on' if args.platform == 'bungee' else 'Done ('
    libraries = BASE / 'deps/luckperms-libs'
    for filename, expected in LIB_SHA256.items():
        if hashlib.sha256((libraries / filename).read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Pinned LuckPerms runtime dependency checksum mismatch: {filename}')
    spec = importlib.util.spec_from_file_location('velocity_fixture', ROOT / 'tools/velocity-e2e.py')
    fixture = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fixture)
    (BASE / 'runtime').mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix=f'{args.platform}-native-', dir=BASE / 'runtime'))
    plugins = run / 'plugins'
    lp = plugins / ('LuckPerms' if args.platform == 'bungee' else 'luckperms')
    lp.mkdir(parents=True)
    if args.platform == 'bungee':
        artifact = bungee_test_jar(args.jar, plugins / 'gate-test.jar')
    else:
        fixture.BASE.mkdir(parents=True, exist_ok=True)
        artifact = fixture.test_jar(args.jar, required_plugins=('luckperms',))
        shutil.copyfile(artifact, plugins / 'gate-test.jar')
    shutil.copyfile(dependency, plugins / 'LuckPerms.jar')
    (lp / 'libs').mkdir()
    for filename in LIB_SHA256:
        shutil.copyfile(libraries / filename, lp / 'libs' / filename)
    (lp / 'config.yml').write_text('storage-method: sqlite\nwatch-files: false\n', encoding='utf-8')
    database = lp / 'luckperms-sqlite.db'
    provisional = str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + NAME).encode()).digest(), version=3))
    stages = (
        ('a-first-bind-old', 'first-bind', NAME, XUID_A, provisional),
        ('a-stable-old', 'trusted', NAME, XUID_A, STABLE),
        ('a-stable-new', 'trusted', RENAMED_NAME, XUID_A, STABLE),
        ('b-first-bind-old', 'first-bind', NAME, XUID_B, provisional),
        ('b-stable-old', 'trusted', NAME, XUID_B, STABLE_B),
    )
    evidence = {
        'platform': args.platform,
        'passed': False,
        'protocol': 767,
        'proxyJar': str(proxy.relative_to(ROOT)),
        'proxySha256': hashlib.sha256(proxy.read_bytes()).hexdigest(),
        'productionArtifactSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest(),
        'fixtureArtifactSha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
        'luckPermsSha256': LP_SHA256[args.platform],
        'luckPermsConsoleCommand': command_prefix,
        'syntheticProofs': True,
        'proofScope': 'Test-only admission/XUID Services; native 1.21.1 LoginSuccess, real LP console '
                      'command and read-only SQLite observations. No real Microsoft/Mojang account '
                      'authorization, backend admission, or GUI acceptance is claimed.',
        'runtime': str(run.relative_to(ROOT)),
        'database': str(database.relative_to(ROOT)),
        'luckPermsVersion': '5.5.91',
        'sentinelPermission': SENTINEL,
        'stages': [],
    }
    evidence_file = run / 'evidence.json'
    evidence_file.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print('RUNTIME', run.relative_to(ROOT), flush=True)
    used_ports = set()
    for label, scenario, name, xuid, expected in stages:
        port = free_port(used_ports)
        used_ports.add(port)
        backend = free_port(used_ports)
        used_ports.add(backend)
        configure_proxy(run, args.platform, port, backend)
        log = run / (label + '.log')
        command = [str(args.jdk / 'bin/java.exe'), '-Xms64M', '-Xmx256M', '-XX:ActiveProcessorCount=2',
                   '-Dterminal.jline=false', '-Dkyokou.e2e.scenario=' + scenario,
                   '-Dkyokou.e2e.xuid=' + str(xuid),
                   '-jar', str(proxy)]
        with log.open('w', encoding='utf-8') as output, backend_login_fixture(args.platform, backend) as native_backend:
            process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=output,
                                       stderr=subprocess.STDOUT, text=True, encoding='utf-8')
            try:
                wait_for_startup(process, log, start_marker, listening_marker)
                actual, native_name = login(port, name)
                if label == 'a-stable-old':
                    evidence['sentinelAssignment'] = {
                        'stage': label,
                        'via': f'owned isolated proxy console: {command_prefix} user <A UUID> permission set <sentinel> true',
                        'readOnlySqliteConfirmed': grant_sentinel(process, database, command_prefix=command_prefix),
                    }
            except Exception as failure:
                evidence['failedStage'] = label
                evidence['failedStagePorts'] = {'frontend': port, 'backend': backend}
                evidence['failure'] = f'{type(failure).__name__}: {failure}'
                if native_backend is not None:
                    evidence['failedStageBackendLogins'] = list(native_backend.events)
                evidence_file.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
                print('EVIDENCE', evidence_file.relative_to(ROOT), flush=True)
                raise
            finally:
                stop(process)
        if process.returncode != 0:
            raise RuntimeError(f'Owned fixture proxy did not stop cleanly: {process.returncode}')
        rows, sentinels = database_rows(database)
        content = log.read_text(encoding='utf-8', errors='replace')
        stage = {'stage': label, 'scenario': scenario, 'requestedName': name, 'syntheticXuid': str(xuid),
                 'ports': {'frontend': port, 'backend': backend},
                 'nativeUuid': actual, 'nativeName': native_name, 'expectedUuid': expected,
                 'players': rows, 'sentinelPermissions': sentinels,
                 'backendLogins': list(native_backend.events) if native_backend is not None else [],
                 'uuidMismatchWarning': 'but this data is stored under a different UUID' in content}
        evidence['stages'].append(stage)
        evidence_file.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
        print(json.dumps(stage), flush=True)
    initial, a_old, a_new, b_first, final = evidence['stages']
    real_names = {NAME.lower(), RENAMED_NAME.lower()}
    final_players = dict(final['players'])
    checks = {
        'freshFrontendPorts': len({stage['ports']['frontend'] for stage in evidence['stages']}) == len(stages),
        'nativeProfiles': all(stage['nativeUuid'] == stage['expectedUuid'] and
                              (stage['scenario'] != 'trusted' or stage['nativeName'] == stage['requestedName'])
                              for stage in evidence['stages']),
        'provisionalNamesIsolated': all(stage['nativeName'].lower() not in real_names and
                                       dict(stage['players']).get(stage['nativeUuid']) == stage['nativeName'].lower() and
                                       NAME.lower() not in dict(stage['players']).values()
                                       for stage in (initial, b_first)),
        'aOriginallyOwnedOldName': dict(a_old['players']).get(STABLE) == NAME.lower(),
        'aRenameReleasedOldName': dict(a_new['players']).get(STABLE) == RENAMED_NAME.lower() and
                                 NAME.lower() not in dict(a_new['players']).values(),
        'aStableUuidAndSentinelRetained': a_old['nativeUuid'] == a_new['nativeUuid'] == STABLE and
                                         not initial['sentinelPermissions'] and
                                         all(stage['sentinelPermissions'] == [(STABLE, 1)]
                                             for stage in evidence['stages'][1:]),
        'bOldNameHasIndependentUuid': STABLE_B != STABLE and final['nativeUuid'] == STABLE_B and
                                     final_players.get(STABLE_B) == NAME.lower() and
                                     final_players.get(STABLE) == RENAMED_NAME.lower(),
        'allStagesWithoutUuidMismatchWarning': not any(stage['uuidMismatchWarning']
                                                       for stage in evidence['stages']),
    }
    if args.platform == 'bungee':
        checks['trustedBackendEchoedActualProfiles'] = all(
            any(event.get('name') == stage['nativeName'] and
                event.get('loginUuid') == event.get('forwardedUuid') == stage['nativeUuid']
                for event in stage['backendLogins'])
            for stage in evidence['stages'] if stage['scenario'] == 'trusted')
    evidence['checks'] = checks
    evidence['passed'] = all(checks.values())
    evidence_file.write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print('EVIDENCE', evidence_file.relative_to(ROOT), flush=True)
    if not evidence['passed']:
        raise AssertionError('LP regression checks failed: ' + ', '.join(name for name, passed in checks.items() if not passed))
    print('PASS: five stages preserved A UUID and sentinel across rename; B reused the old name '
          'without inherited sentinel, provisional real-name pollution, or UUID mismatch warnings')


if __name__ == '__main__':
    main()
