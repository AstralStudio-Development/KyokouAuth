"""Isolated Java U legacy-profile UUID continuity using real Velocity modern forwarding and Paper.

mapping covers a real H2 claim/database reopen at the admission seam. claim/cancel/disconnect/expired
exercise native Limbo menus with synthetic old-account/Microsoft proofs; claim restarts the full proxy
and reauthenticates before reading the old Paper profile. Only AuthMe -> Java U is in scope; no user data is copied.
prepare starts no JVM/downloads. run compiles first, then owns Paper512M + proxy128M + SDK client64M.
"""
import argparse
from contextlib import ExitStack
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import sqlite3
import subprocess
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parent.parent
BASE = ROOT / ".narrafork/profile-claim-e2e"
OLD = str(uuid.UUID(bytes=hashlib.md5(b"OfflinePlayer:OldSteve").digest(), version=3))
MAIN = "moe.ryuu.kyokouauth.velocity.ProfileClaimE2ePlugin"
H2_SHA256 = '3ad9ac4b6aae9cd9d3ac1c447465e1ed06019b851b893dd6a8d76ddb6d85bca6'
H2_JAR = 'h2-1.4.200.jar'
SQLITE_SHA256 = 'cd55db695548e9b1ba38070109f809052e9ed377256f6218b9e4cd4ee603ab55'
SQLITE_JAR = 'sqlite-jdbc-3.45.3.0.jar'
AUTHME_FIXTURE_HASH = '$SHA$3c72a18a29b08d40$8e50a7a4f69a80f4893dc921eac84bd74b3f9ebfa22908302c9965eac3aa45e5'


def digest(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def wait(process, log, marker, timeout=90, offset=0):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if process.poll() is not None: raise RuntimeError(f'Owned process exited; inspect {log}')
        text = log.read_text(encoding='utf-8', errors='replace')[offset:] if log.exists() else ''
        if log.exists() and log.stat().st_size > 8 * 1024 * 1024: raise RuntimeError('Fixture log limit exceeded')
        if 'CLAIM_E2E_ASSERTION_FAILED' in text: raise RuntimeError('Native assertion failed')
        clean = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', text)
        if marker in clean: return clean
        time.sleep(0.1)
    raise TimeoutError(f'No {marker}; inspect {log}')


def stop(process, command):
    if process.poll() is not None: return
    try: process.communicate(command, timeout=30)
    except subprocess.TimeoutExpired:
        process.terminate()
        try: process.wait(timeout=10)
        except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)


def launch(stack, command, folder, log, shutdown):
    output = stack.enter_context(log.open('w', encoding='utf-8'))
    process = subprocess.Popen(command, cwd=folder, stdin=subprocess.PIPE, stdout=output,
        stderr=subprocess.STDOUT, text=True, encoding='utf-8')
    stack.callback(stop, process, shutdown)
    return process


def console(process, commands):
    process.stdin.write('\n'.join(commands) + '\n'); process.stdin.flush()


def test_jar(artifact, destination, interactive=False):
    classes = ROOT/'platform-velocity/build/classes/kotlin/test/moe/ryuu/kyokouauth/velocity'
    bootstrap = 'InteractiveProfileClaimE2ePlugin' if interactive else 'ProfileClaimE2ePlugin'
    entries = list(classes.glob(bootstrap+'*.class'))
    if not entries: raise RuntimeError('Compile :platform-velocity:compileTestKotlin first')
    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(destination,'w',zipfile.ZIP_DEFLATED) as target:
        if any('ProfileClaimE2ePlugin' in n for n in source.namelist()): raise RuntimeError('Test code in production Jar')
        metadata = json.loads(source.read('velocity-plugin.json'))
        metadata.update(id='kyokou-profile-claim-e2e',main=MAIN.rsplit('.',1)[0]+'.'+bootstrap,name='Isolated profile mapping proof')
        for info in source.infolist():
            if info.filename != 'velocity-plugin.json': target.writestr(info, source.read(info.filename))
        target.writestr('velocity-plugin.json', json.dumps(metadata))
        for entry in entries: target.write(entry,'moe/ryuu/kyokouauth/velocity/'+entry.name)


def assert_claim_markers(text):
    """Require one ordered run on two distinct native sessions; old/substituted bootstrap logs fail."""
    def session(marker):
        hits = re.findall(re.escape(marker) + r' session=([0-9a-f-]{36})\b', text)
        if len(hits) != 1: raise AssertionError('Expected exactly one ' + marker)
        return str(uuid.UUID(hits[0]))
    seed = session('CLAIM_E2E_DISCONNECTED stage=seed')
    new = session('CLAIM_E2E_PREFLIGHT_EMPTY')
    if seed == new: raise AssertionError('Seed and claimant sessions must differ')
    markers = (
        f'CLAIM_E2E_BACKEND_CONNECTED stage=seed session={seed} name=OldSteve uuid={OLD} server=backend',
        f'CLAIM_E2E_DISCONNECTED stage=seed session={seed} name=OldSteve uuid={OLD}',
        f'CLAIM_E2E_PREFLIGHT_EMPTY session={new}',
        f'CLAIM_E2E_REPOSITORY_COMMITTED session={new} uuid={OLD} oldProof=synthetic newProof=synthetic',
        f'CLAIM_E2E_SECOND_CLAIM_REFUSED session={new} reason=LEGACY_UNAVAILABLE',
        f'CLAIM_E2E_REOPENED session={new} uuid={OLD} defaultUuidRefused=true',
        f'CLAIM_E2E_FINAL_PROFILE session={new} name=NewSteve uuid={OLD} nonce={new}',
        f'CLAIM_E2E_BACKEND_CONNECTED stage=mapped session={new} name=NewSteve uuid={OLD} server=backend',
    )
    start = 0
    for marker in markers:
        pos = text.find(marker, start)
        if pos < 0: raise AssertionError('Missing or out-of-order native marker: ' + marker)
        start = pos + len(marker)


def assert_interactive_markers(text, scenario):
    """Validate native held-session ordering, uniqueness and no accidental backend/claim mutation."""
    if scenario not in ('claim','cancel','disconnect','expired') or 'CLAIM_E2E_ASSERTION_FAILED' in text:
        raise AssertionError('Invalid scenario or native assertion failure')
    uid = r'[0-9a-f-]{36}'
    def one(pattern):
        values = re.findall(pattern, text)
        if len(values) != 1: raise AssertionError('Expected exactly one native marker: '+pattern)
        return values[0]
    seed = one(r'CLAIM_NATIVE_DISCONNECTED stage=seed session=('+uid+r')')
    held = one(r'CLAIM_NATIVE_LOGIN stage=claim session=('+uid+r') uuid='+uid+r' authenticated=false')
    if seed == held: raise AssertionError('Seed and held claimant reused a session')
    connected = re.findall(r'CLAIM_NATIVE_BACKEND stage=(\S+) session=('+uid+r') uuid=('+uid+r')', text)
    if connected != [('seed',seed,OLD)]: raise AssertionError('Held claimant reached backend or seed UUID differs')
    menus = re.findall(r'CLAIM_NATIVE_MENU session=('+uid+r') index=([1-3]) generation=('+uid+r')',text)
    progressed = scenario in ('claim','disconnect')
    count = 3 if progressed else 2
    if len(menus) != count or [(session,index) for session,index,_ in menus] != [(held,str(i)) for i in range(1,count+1)]:
        raise AssertionError('Missing, duplicate or cross-session native confirmation')
    if len({generation for _,_,generation in menus}) != count:
        raise AssertionError('Native confirmations reused a generation')
    ordered = [f'CLAIM_NATIVE_BACKEND stage=seed session={seed} uuid={OLD}',
        f'CLAIM_NATIVE_DISCONNECTED stage=seed session={seed}',f'CLAIM_NATIVE_LOGIN stage=claim session={held}',
        f'CLAIM_NATIVE_MENU session={held} index=1',f'CLAIM_NATIVE_OLD_PROOF session={held} synthetic=true',
        f'CLAIM_NATIVE_MENU session={held} index=2']
    one(r'CLAIM_NATIVE_OLD_PROOF session=('+uid+r') synthetic=true')
    if progressed:
        for marker in ('CLAIM_NATIVE_MICROSOFT_BEGIN','CLAIM_NATIVE_MICROSOFT_PROVED'):
            if one(re.escape(marker)+r' session=('+uid+r') synthetic=true') != held:
                raise AssertionError('Microsoft proof was not bound to the claimant')
            ordered.append(f'{marker} session={held} synthetic=true')
        ordered.append(f'CLAIM_NATIVE_MENU session={held} index=3')
    elif 'CLAIM_NATIVE_MICROSOFT_' in text:
        raise AssertionError('Cancelled/expired source confirmation started Microsoft proof')
    commits = re.findall(r'CLAIM_NATIVE_COMMITTED session=('+uid+r') uuid=('+uid+r') sourceOffline=(\w+)',text)
    if scenario == 'claim':
        if commits != [(held,OLD,'true')]: raise AssertionError('Missing/duplicate claim commit or source was online')
        ordered.append(f'CLAIM_NATIVE_COMMITTED session={held} uuid={OLD} sourceOffline=true')
    else:
        if commits: raise AssertionError('Unconfirmed claimant committed ownership')
        value = f'CLAIM_NATIVE_NO_MUTATION case={scenario} menus={count} begins={int(progressed)} exchanges={int(progressed)} backend=false'
        if text.count(value) != 1: raise AssertionError('No final no-mutation assertion')
    ordered.append(f'CLAIM_NATIVE_DISCONNECTED stage=claim session={held}')
    start = 0
    for marker in ordered:
        pos = text.find(marker,start)
        if pos < 0: raise AssertionError('Missing or out-of-order native claim marker: '+marker)
        start = pos+len(marker)


def assert_restarted_claim_markers(initial, restarted):
    assert_interactive_markers(initial,'claim')
    if 'CLAIM_E2E_ASSERTION_FAILED' in restarted or 'CLAIM_NATIVE_MENU' in restarted:
        raise AssertionError('Restart failed or reopened migration with its switch disabled')
    logins = re.findall(r'CLAIM_NATIVE_LOGIN stage=reauth session=([0-9a-f-]{36}) uuid='+re.escape(OLD)+r' authenticated=false',restarted)
    if len(logins) != 1 or logins[0] in re.findall(r'CLAIM_NATIVE_LOGIN stage=claim session=([0-9a-f-]{36})',initial):
        raise AssertionError('Restart must hold a fresh physical login until a new proof')
    session = logins[0]
    markers = (f'CLAIM_NATIVE_RESTART_LOADED uuid={OLD} migrationFlag=false',
        f'CLAIM_NATIVE_LOGIN stage=reauth session={session} uuid={OLD} authenticated=false',
        f'CLAIM_NATIVE_MICROSOFT_BEGIN session={session} synthetic=true',
        f'CLAIM_NATIVE_MICROSOFT_PROVED session={session} synthetic=true',
        f'CLAIM_NATIVE_REAUTH_BOUND session={session} uuid={OLD}',
        f'CLAIM_NATIVE_REAUTH_GRANTED session={session} uuid={OLD}',
        f'CLAIM_NATIVE_BACKEND stage=mapped session={session} uuid={OLD}')
    start = 0
    for marker in markers:
        pos = restarted.find(marker,start)
        if restarted.count(marker) != 1 or pos < 0:
            raise AssertionError('Missing, duplicate or out-of-order restart proof: '+marker)
        start = pos+len(marker)


def assert_private_claim_markers(native, client, other_logs=()):
    assert_interactive_markers(native,'claim')
    held = re.search(r'CLAIM_NATIVE_LOGIN stage=claim session=([0-9a-f-]{36})',native).group(1)
    expected = f'CLAIM_NATIVE_PRIVATE_SOURCE session={held} attempts=2 commandEvents=0 completionEvents=0 audit=AUTHME_PASSWORD_CLAIM'
    if native.count(expected) != 1 or native.find(expected) < native.find(f'CLAIM_NATIVE_COMMITTED session={held}'):
        raise AssertionError('Read-only private source, no-event barrier and distinct audit proof are required')
    attempts = re.findall(r'CLAIM_CLIENT_PRIVATE_SENT attempt=(\d+) unsigned=true',client)
    if attempts != ['1','2']: raise AssertionError('Must exercise wrong then correct native private submissions')
    for text in (native,client,*other_logs):
        if any(secret in text for secret in ('PassWord1','PRIVATE_BAD_E2E_PASSWORD',AUTHME_FIXTURE_HASH)):
            raise AssertionError('A private fixture password or source hash leaked into captured output')


def run(args):
    gradle_cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))/'caches/modules-2/files-2.1'
    driver = gradle_cache/'com.h2database/h2/1.4.200/f7533fe7cb8e99c87a43d325a77b4b678ad9031a'/H2_JAR
    if not driver.is_file() or digest(driver) != H2_SHA256: raise RuntimeError('Missing or mismatched pinned offline H2 driver')
    for p in (args.jar, ROOT/'run-proxy/velocity.jar', ROOT/'run-live/paper.jar'):
        if not p.is_file(): raise RuntimeError(f'Missing public artifact: {p}')
    upstream = (ROOT/'limbo/protocols/777/build/generated/limbo-codec/tool-classpath.txt').read_text(encoding='utf-8').strip()
    if any(not Path(p).is_file() for p in upstream.split(os.pathsep)): raise RuntimeError('Missing full SDK classpath')
    BASE.mkdir(parents=True,exist_ok=True)
    run = BASE/uuid.uuid4().hex; run.mkdir()
    classes = run/'client-classes'; classes.mkdir()
    java = str(args.jdk/'bin/java.exe'); javac = str(args.jdk/'bin/javac.exe')
    subprocess.run([javac,'-J-Xmx128M','-encoding','UTF-8','-cp',upstream,'-d',str(classes),str(ROOT/'tools/ProfileMappingClient.java')],check=True,timeout=90)
    paper = run/'paper'; paper.mkdir()
    proxy = run/'proxy'; (proxy/'plugins').mkdir(parents=True)
    fixture_driver = proxy/'fixture-libraries/com/h2database/h2/1.4.200'/H2_JAR
    fixture_driver.parent.mkdir(parents=True); shutil.copyfile(driver, fixture_driver)
    source_before = None
    if args.case == 'authme':
        sqlite_driver = gradle_cache/'org.xerial/sqlite-jdbc/3.45.3.0/6ae68e5fbf0d184c2f8191818fbe66e4dd70e14'/SQLITE_JAR
        if not sqlite_driver.is_file() or digest(sqlite_driver) != SQLITE_SHA256:
            raise RuntimeError('Missing or mismatched pinned offline SQLite driver')
        target = proxy/'fixture-libraries/org/xerial/sqlite-jdbc/3.45.3.0'/SQLITE_JAR
        target.parent.mkdir(parents=True); shutil.copyfile(sqlite_driver,target)
        with sqlite3.connect(proxy/'authme-snapshot.db') as source_db:
            source_db.execute('CREATE TABLE authme(username TEXT, password TEXT, totp TEXT)')
            source_db.execute('INSERT INTO authme VALUES (?, ?, ?)', ('oldsteve',AUTHME_FIXTURE_HASH,''))
        source_db.close()
        source_before = digest(proxy/'authme-snapshot.db')
    (proxy/'tmp').mkdir(); (paper/'tmp').mkdir()
    shutil.copyfile(ROOT/'run-live/paper.jar',paper/'paper.jar')
    for directory in ('libraries','versions'): shutil.copytree(ROOT/'run-live'/directory,paper/directory)
    (paper/'cache').mkdir()
    shutil.copyfile(ROOT/'run-live/cache/mojang_26.3.jar', paper/'cache/mojang_26.3.jar')
    backend_port, proxy_port = free_port(), free_port()
    secret = secrets.token_hex(32)
    (proxy/'isolated-profile-claim-fixture.json').write_text(json.dumps({'isolatedFixture': True,'syntheticFixture': True}),encoding='utf-8')
    (proxy/'forwarding.secret').write_text(secret,encoding='utf-8')
    (proxy/'velocity.toml').write_text(f'''config-version = "2.9"
bind = "127.0.0.1:{proxy_port}"
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
accepts-transfers = false
login-ratelimit = 0
''',encoding='utf-8')
    (paper/'eula.txt').write_text('eula=true\n',encoding='utf-8')
    (paper/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={backend_port}\nonline-mode=false\nwhite-list=false\nenforce-whitelist=false\nview-distance=2\nsimulation-distance=2\nlevel-type=minecraft:normal\ngenerate-structures=false\nspawn-protection=0\n',encoding='utf-8')
    (paper/'config').mkdir()
    (paper/'config/paper-global.yml').write_text(f'proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: "{secret}"\n',encoding='utf-8')
    interactive = args.case != 'mapping'
    test_jar(args.jar,proxy/'plugins/claim-test.jar',interactive)
    if args.luckperms:
        if digest(args.luckperms) != '2a6834c51830b133ec8763703391b20240f7a3b4748746503251a45d70cf1b39':
            raise RuntimeError('Pinned official LuckPerms 5.5.91 Jar mismatch')
        (paper/'plugins').mkdir()
        shutil.copyfile(args.luckperms,paper/'plugins/LuckPerms.jar')
        if args.luckperms_libraries:
            shutil.copytree(args.luckperms_libraries,paper/'plugins/LuckPerms/libs')
            event = paper/'plugins/LuckPerms/libs/event-3.0.0.jar'
            if not event.is_file() or digest(event) != 'ca3bdd4dd03292d97788511014b1c2dea630c2deff0db09ded973c4384a521a8':
                raise RuntimeError('LuckPerms declared event-api 3.0.0 dependency checksum mismatch')
    evidence = {'status':'failed','syntheticProofs':True,'oldAccountProof':'synthetic','microsoftProof':'synthetic',
        'limboGui':'NOT_TESTED','fullProxyRestart':'NOT_TESTED','floodgateAndBungeeMapping':'NOT_TESTED_UNSUPPORTED',
        'thirdPartyPluginContinuity':'NOT_TESTED','clientRendering':'NOT_TESTED','sourceArtifactSha256':digest(args.jar),'legacyUuid':OLD,
        'realMicrosoftOrAuthMeCredentials':False,'productionDataReadOrChanged':False}
    try:
        with ExitStack() as stack:
            backend = launch(stack,[java,'-Xms192M','-Xmx512M','-XX:ActiveProcessorCount=2','-Dhttps.proxyHost=127.0.0.1','-Dhttps.proxyPort=7890','-Djava.io.tmpdir='+str(paper/'tmp'),'-jar','paper.jar','--nogui'],paper,paper/'console.log','stop\n')
            wait(backend,paper/'console.log','Done (',120)
            def start_gateway(resumed=False):
                native_log = proxy/('resume-console.log' if resumed else 'console.log')
                process = launch(stack,[java,'-Xms64M','-Xmx128M','-XX:ActiveProcessorCount=2',
                    '-Djava.io.tmpdir='+str(proxy/'tmp'),'-Dkyokou.claimE2e.root='+str(BASE),'-Dkyokou.claimE2e.run='+str(proxy),
                    '-Dkyokou.claimE2e.migrationEnabled=false','-Dkyokou.claimE2e.supportsMapped=true',
                    '-Dkyokou.claimE2e.case='+args.case,'-Dkyokou.claimE2e.resume='+str(resumed).lower(),
                    '-jar',str(ROOT/'run-proxy/velocity.jar')],proxy,native_log,'end\n')
                wait(process,native_log,'CLAIM_E2E_STARTED')
                return process,native_log
            gateway,native_log = start_gateway()
            initial_native = ''
            for name in ('OldSteve','NewSteve'):
                if name == 'NewSteve' and interactive:
                    claim_log = run/'claim-client.log'
                    provisional = str(uuid.UUID(bytes=hashlib.md5(b'OfflinePlayer:NewSteve').digest(), version=3))
                    with claim_log.open('w',encoding='utf-8') as output:
                        subprocess.run([java,'-Xmx64M','-XX:ActiveProcessorCount=2','-cp',str(classes)+os.pathsep+upstream,
                            'ProfileMappingClient',str(proxy_port),name,provisional,args.case],cwd=run,
                            stdin=subprocess.DEVNULL,stdout=output,stderr=subprocess.STDOUT,check=True,timeout=60)
                    client_text = claim_log.read_text(encoding='utf-8')
                    expected_menus = 3 if args.case in ('claim','authme','disconnect') else 2
                    if f'CLAIM_CLIENT_COMPLETED case={args.case} menus={expected_menus}' not in client_text:
                        raise AssertionError('Native menu client did not finish its actual scenario')
                    if args.case not in ('claim','authme'):
                        native = wait(gateway,native_log,f'CLAIM_NATIVE_NO_MUTATION case={args.case}',30)
                        assert_interactive_markers(native,args.case)
                        if 'NewSteve[/127.0.0.1:' in (paper/'console.log').read_text(encoding='utf-8',errors='replace'):
                            raise AssertionError('Uncommitted claimant entered Paper')
                        evidence['limboGui']={'scenario':args.case,'nativeMenuCount':expected_menus,'wireInteraction':'passed',
                            'claimAndAuditUnchanged':'passed','backendAdmissionDenied':'passed'}
                        evidence['status']='passed'
                        return
                    initial_native = wait(gateway,native_log,'CLAIM_NATIVE_DISCONNECTED stage=claim',30)
                    assert_interactive_markers(initial_native,'claim')
                    if args.case == 'authme': assert_private_claim_markers(initial_native,client_text)
                    stop(gateway,'end\n')
                    if gateway.returncode != 0: raise RuntimeError('Fixture proxy failed to shut down cleanly')
                    gateway,native_log = start_gateway(resumed=True)
                log = run/(name+'-client.log')
                with ExitStack() as client_stack:
                    client = launch(client_stack,[java,'-Xmx64M','-XX:ActiveProcessorCount=2','-cp',str(classes)+os.pathsep+upstream,
                        'ProfileMappingClient',str(proxy_port),name,OLD],run,log,'\n')
                    wait(client,log,'PROFILE_CLIENT_CONNECTED',60)
                    wait(backend,paper/'console.log',name+'[/127.0.0.1:',30)
                    if name == 'OldSteve':
                        if args.luckperms:
                            permission_offset = len((paper/'console.log').read_text(encoding='utf-8',errors='replace'))
                            console(backend,['lp user OldSteve permission set kyokou.claim.sentinel true'])
                            wait(backend,paper/'console.log','kyokou.claim.sentinel',30,permission_offset)
                        console(backend,['give OldSteve minecraft:diamond 64','item replace entity OldSteve enderchest.0 with minecraft:emerald 17','experience set OldSteve 23 levels','advancement grant OldSteve only minecraft:story/root','save-all flush'])
                        wait(backend,paper/'console.log','Saved the game',30)
                    else:
                        offset=len((paper/'console.log').read_text(encoding='utf-8',errors='replace'))
                        console(backend,['data get entity NewSteve UUID','data get entity NewSteve Inventory','data get entity NewSteve EnderItems',
                            'execute if entity @a[name=NewSteve,advancements={minecraft:story/root=true}] run data get entity NewSteve XpLevel'])
                        text=wait(backend,paper/'console.log','NewSteve has the following entity data: 23',20,offset)
                        for item, count in (('minecraft:diamond', 64), ('minecraft:emerald', 17)):
                            if not any('NewSteve has the following entity data:' in line and item in line and
                                    re.search(r'\bcount:\s*'+str(count)+r'\b',line) for line in text.splitlines()):
                                raise AssertionError('Old item/count sentinel missing: '+item)
                        matches=re.findall(r'\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]',text)
                        expected=[(uuid.UUID(OLD).int >> shift)&0xffffffff for shift in (96,64,32,0)]
                        if not any([int(part)&0xffffffff for part in match] == expected for match in matches): raise AssertionError('Paper native UUID differs')
                        evidence['paperUuidAndInventoryEnderChestExperience']='passed'
                        if args.luckperms:
                            permission_offset = len((paper/'console.log').read_text(encoding='utf-8',errors='replace'))
                            console(backend,['lp user NewSteve permission check kyokou.claim.sentinel'])
                            permission_text = wait(backend,paper/'console.log','kyokou.claim.sentinel',30,permission_offset)
                            if not any('kyokou.claim.sentinel' in line and 'true' in line.lower() for line in permission_text.splitlines()):
                                raise AssertionError('LuckPerms did not retain the old UUID permission for NewSteve')
                            evidence['thirdPartyPluginContinuity']={'plugin':'LuckPerms','version':'5.5.91','jarSha256':digest(args.luckperms),'uuidPermissionSentinel':'passed'}
                wait(backend,paper/'console.log',name+' left the game',30)
                stage = 'seed' if name == 'OldSteve' else 'mapped'
                marker = 'CLAIM_NATIVE_DISCONNECTED' if interactive else 'CLAIM_E2E_DISCONNECTED'
                native = wait(gateway,native_log,f'{marker} stage={stage}',30)
                if stage == 'mapped':
                    if interactive:
                        assert_restarted_claim_markers(initial_native,native)
                        evidence['limboGui']={'scenario':args.case,'nativeMenuCount':3,'wireInteraction':'passed',
                            'oldAccountProof':'fixture SQLite SHA verifier' if args.case == 'authme' else 'synthetic console approval',
                            'microsoftProof':'synthetic','singleCommitAfterDuplicateFinalClick':'passed',
                            'heldUntilOrdinaryReconnect':'passed'}
                        evidence['fullProxyRestart']='passed with migration disabled; real H2 reopened in a new JVM'
                        evidence['freshProofAfterRestart']='passed through real AuthCoordinator and native Limbo; only Xbox response is synthetic'
                    else:
                        assert_claim_markers(native)
                        evidence['profileClaimRepository'] = {'database':'file-backed H2','driverSha256':H2_SHA256,
                            'preflightNoClaimRows':'passed','importIssueCommitAfterSeedDisconnect':'passed',
                            'sameSourceCompetingGrantRefused':'passed','closedPoolReopenLoadedBoundIdentity':'passed',
                            'defaultXuidUuidClaimRefused':'passed'}
                    evidence['nativeVelocityModernForwardingToPaper']='passed'
            console(backend,['save-all flush'])
        advancement=paper/'world/players/advancements'/f'{OLD}.json'
        if not advancement.is_file(): raise AssertionError('No old UUID advancement file')
        if not json.loads(advancement.read_text(encoding='utf-8')).get('minecraft:story/root',{}).get('done'): raise AssertionError('Advancement sentinel absent')
        evidence['advancementContinuity']='passed'
        if args.case == 'authme':
            captured = [path.read_text(encoding='utf-8',errors='replace') for path in (
                paper/'console.log',proxy/'resume-console.log',run/'OldSteve-client.log',run/'NewSteve-client.log')]
            assert_private_claim_markers(initial_native,client_text,captured)
            if digest(proxy/'authme-snapshot.db') != source_before:
                raise AssertionError('Read-only source snapshot bytes changed')
            evidence['oldAccountProof']='real salted-SHA verifier against an isolated fixture account; not a real AuthMe player'
            evidence['privateCommandPrivacy']={'wrongThenCorrectSubmissions':2,'publicCommandEvents':0,'publicCompletionEvents':0,
                'capturedPasswordOrHashLeaks':0,'sourceSnapshotUnchanged':True,'audit':'AUTHME_PASSWORD_CLAIM'}
        evidence['status']='passed'
    finally:
        (run/'evidence.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
        print(json.dumps({'status':evidence['status'],'evidence':str((run/'evidence.json').relative_to(ROOT))}))
    if evidence['status'] != 'passed': raise RuntimeError('Isolated profile proof failed')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('prepare','run'))
    parser.add_argument('--case',choices=('mapping','claim','authme','cancel','disconnect','expired'),default='mapping',
        help='mapping: storage seam only; other cases exercise the native Limbo GUI with synthetic proofs')
    parser.add_argument('--jar',type=Path,default=ROOT/'platform-velocity/build/libs/KyokouAuth-Velocity-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--jdk',type=Path,default=Path('C:/Program Files/Zulu/zulu-25'))
    parser.add_argument('--luckperms',type=Path,help='Cached pinned official LuckPerms Bukkit 5.5.91 Jar; only installed in the new isolated Paper')
    parser.add_argument('--luckperms-libraries',type=Path,help='Optional cached public LuckPerms dependency Jars; never provide plugin data directories')
    args=parser.parse_args()
    if args.command=='prepare':
        print('Local unsigned Java U fixture; run requires compiled test bootstrap and pinned cached server/SDK dependencies; no downloads/JVM started')
    else: run(args)

if __name__=='__main__': main()
