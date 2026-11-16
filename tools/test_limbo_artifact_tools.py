"""Pure-Python layout/negative tests using synthetic ZIPs under workspace .narrafork only.

Synthetic .class headers exercise archive policy, not real Java bytecode/linkage. No JVM is launched.
Run: python -B tools/test_limbo_artifact_tools.py
"""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

from limbo_artifact_support import (ENGINE_SERVICE, PROTOCOLS, ROOT as WORKSPACE, artifact_protocols,
                                    resolve_netty_classpath, sha256)


def load_tool(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), WORKSPACE / 'tools' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


AUDIT = load_tool('audit-limbo-artifact')
PREFIX = AUDIT.ROOT


def class_header(major):
    return b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, major, 1)


def session_anchor(name, *, dependency=None, method=None, r8_marker=None, inner_classes=None):
    """A real minimal class-file structure, with optional forbidden dead CP reference or method."""
    pool = []
    def utf8(value):
        raw = value.encode()
        pool.append(b'\x01' + struct.pack('>H', len(raw)) + raw)
        return len(pool)
    def class_ref(value):
        index = utf8(value)
        pool.append(b'\x07' + struct.pack('>H', index))
        return len(pool)
    own = class_ref(name)
    parent = class_ref('java/lang/Object')
    if r8_marker is not None:
        marker_index = utf8(r8_marker)
        pool.append(b'\x08' + struct.pack('>H', marker_index))
    if dependency:
        class_ref(dependency)
    methods = b'\x00\x00'
    if method:
        method_name = utf8(method)
        descriptor = utf8('()V')
        methods = struct.pack('>HHHHH', 1, 0x0101, method_name, descriptor, 0)
    attributes = b'\x00\x00'
    if inner_classes:
        inner = class_ref(inner_classes[0])
        outer = class_ref(inner_classes[1])
        simple_name = utf8(inner_classes[2])
        attribute = utf8('InnerClasses')
        payload = struct.pack('>HHHHH', 1, inner, outer, simple_name, 0x0019)
        attributes = struct.pack('>HHI', 1, attribute, len(payload)) + payload
    return (b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, 61, len(pool) + 1) + b''.join(pool)
            + struct.pack('>HHHHH', 0x0021, own, parent, 0, 0) + methods + attributes)


def synthetic_entries(protocols=PROTOCOLS):
    entries = {PREFIX + 'LimboServer.class': class_header(65), 'yggdrasil_session_pubkey.der': b'\x30\x82\x00\x80' + bytes(128)}
    def add_class(name, major=61):
        entries[name + '.class'] = class_header(major)
    for name in ('LimboPlayer', 'LimboListener', 'LimboLogger', 'LimboSettings', 'LimboEngineFactory', 'EngineContext', 'ForwardingMode', 'protocol/TextComponent'):
        add_class(PREFIX + name)
    entries[ENGINE_SERVICE] = '\n'.join(f'moe.ryuu.kyokouauth.limbo.engine.p{p}.session.EngineFactory' for p in protocols).encode()
    for protocol in protocols:
        base = PREFIX + f'engine/p{protocol}/'
        for name in ('EngineFactory', 'GeneratedCodec', 'SparseMinecraftProtocol'):
            add_class(base + 'session/' + name, 65)
        fixture = f'synthetic-registry-{protocol}'.encode()
        entries[f'limbo/configuration-{protocol}.bin'] = fixture
        meta = {'protocol': str(protocol), 'version': f'synthetic-{protocol}', 'source': 'official-offline-server-empty-known-packs',
                'known-packs': 'empty-response-full-registry-nbt', 'server-sha256': '1' * 64, 'server-bundler-sha256': '2' * 64,
                'sha256': hashlib.sha256(fixture).hexdigest(), 'registries': '1', 'entries': '1'}
        entries[f'limbo/configuration-{protocol}.properties'] = ''.join(f'{k}={v}\n' for k, v in meta.items()).encode()
        artifacts = [{'filename': 'synthetic.pom', 'sha256': '3' * 64, 'url': 'synthetic-fixture:pom'},
                     {'filename': 'synthetic.jar', 'sha256': '4' * 64, 'url': 'synthetic-fixture:binary'},
                     {'filename': 'synthetic-sources.jar', 'sha256': '5' * 64, 'url': 'synthetic-fixture:sources'}]
        entries[f'limbo/mcpl-{protocol}.json'] = json.dumps({'protocol': protocol, 'licenses': ['The MIT License'], 'artifacts': artifacts}).encode()
        packet = {'state': 'GAME', 'direction': 'SERVERBOUND', 'id': 0, 'class': 'synthetic.UnusedPacket',
                  'disposition': 'ignored', 'terminal': False}
        codec = {'schema': 1, 'protocol': protocol, 'minecraftVersion': f'synthetic-{protocol}', 'binarySha256': '4' * 64,
                 'sourcesSha256': '5' * 64, 'codecSourceSha256': '6' * 64, 'generatorSha256': '7' * 64, 'packets': [packet]}
        entries[f'limbo/codec-{protocol}.json'] = json.dumps(codec).encode()
        entries[f'limbo/ignored-{protocol}.json'] = json.dumps([packet]).encode()
    for name in ('org/cloudburstmc/nbt/NbtMap', 'com/google/gson/Gson', 'org/slf4j/LoggerFactory', 'it/unimi/dsi/fastutil/ints/Int2ObjectMap'):
        add_class(AUDIT.SHARED + name)
    services = [(AUDIT.SHARED + suffix).replace('/', '.') for suffix in ('org/cloudburstmc/math/imaginary/ImaginaryProvider',
                'org/cloudburstmc/math/vector/VectorProvider', 'org/slf4j/spi/SLF4JServiceProvider')]
    for family in {AUDIT.family(protocol) for protocol in protocols}:
        base = AUDIT.TEXT + family + '/net/kyori/adventure/'
        add_class(base + 'key/Key')
        add_class(base + 'text/Component')
        services.append((base + 'text/serializer/json/JSONComponentSerializer$Provider').replace('/', '.'))
        if family != 'legacy':
            services.append((base + 'text/event/DataComponentValueConverterRegistry$Provider').replace('/', '.'))
    for interface in services:
        add_class(interface.replace('.', '/'))
        add_class(interface.replace('.', '/') + 'Impl')
        entries['META-INF/services/' + interface] = (interface + 'Impl\n').encode()
    return entries


class ArtifactPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='limbo-artifact-tools-', dir=WORKSPACE / '.narrafork')
        cls.folder = Path(cls.temp.name)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def write_zip(self, entries, name='bad.zip'):
        path = self.folder / name
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as archive:
            for key, value in entries.items():
                archive.writestr(key, value)
        return path

    def check_bad(self, entries, message):
        report = AUDIT.audit(self.write_zip(entries))
        self.assertTrue(any(message in error for error in report['errors']), report['errors'])

    def test_complete_synthetic_layout_is_read_only(self):
        artifact = self.write_zip(synthetic_entries())
        before = sha256(artifact)
        report = AUDIT.audit(artifact)
        self.assertEqual([], report['errors'])
        self.assertEqual(before, sha256(artifact))
        self.assertEqual(11, len(report['protocols']))
        self.assertEqual(20 * 1024 * 1024, report['hard-limit-bytes'])
        self.assertEqual(0, report['target-gap-bytes'])
        self.assertIn('shared', report['groups'])
        self.assertEqual(set(PROTOCOLS), artifact_protocols(artifact))

    def test_boundary_prototype_requires_explicit_protocols(self):
        artifact = self.write_zip(synthetic_entries((767, 770, 777)))
        self.assertTrue(AUDIT.audit(artifact)['errors'])
        report = AUDIT.audit(artifact, (767, 770, 777))
        self.assertEqual([], report['errors'])
        self.assertEqual('explicit-protocol-subset', report['scope'])

    def test_default_wrappers_reject_partial_before_any_jvm(self):
        artifact = self.write_zip(synthetic_entries((767, 770, 777)))
        for script in ('verify-limbo-matrix', 'limbo-assembly-smoke'):
            module = load_tool(script)
            with self.subTest(script=script), mock.patch.object(sys, 'argv', [script, '--jar', str(artifact)]), \
                    mock.patch.object(module.subprocess, 'run', side_effect=AssertionError('JVM must not launch')), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
                module.main()
            self.assertEqual(2, raised.exception.code)

    def test_missing_generated_codec_and_removed_blockhound(self):
        entries = synthetic_entries()
        del entries[PREFIX + 'engine/p777/session/GeneratedCodec.class']
        artifact = self.write_zip(entries)
        with self.assertRaisesRegex(ValueError, 'GeneratedCodec'):
            artifact_protocols(artifact)
        self.assertTrue(any('GeneratedCodec' in error for error in AUDIT.audit(artifact)['errors']))
        entries = synthetic_entries()
        entries['META-INF/services/reactor.blockhound.integration.BlockHoundIntegration'] = (
            PREFIX.replace('/', '.') + 'engine.p777.libs.io.netty.util.internal.Hidden$NettyBlockHoundIntegration\n').encode()
        self.check_bad(entries, 'SPI provider class missing')

    def test_same_protocol_vanilla_patch_alias_groups(self):
        groups = {767: ('1.21', '1.21.1'), 772: ('1.21.7', '1.21.8'), 773: ('1.21.9', '1.21.10'),
                  775: ('26.1', '26.1.1', '26.1.2')}
        def entries_for(protocol, library_version, fixture_version):
            entries = synthetic_entries()
            properties = entries[f'limbo/configuration-{protocol}.properties'].decode().replace(f'version=synthetic-{protocol}', 'version=' + fixture_version)
            entries[f'limbo/configuration-{protocol}.properties'] = properties.encode()
            codec = json.loads(entries[f'limbo/codec-{protocol}.json'])
            codec['minecraftVersion'] = library_version
            entries[f'limbo/codec-{protocol}.json'] = json.dumps(codec).encode()
            return entries
        for protocol, versions in groups.items():
            for library in versions:
                for fixture in versions:
                    with self.subTest(protocol=protocol, library=library, fixture=fixture):
                        self.assertEqual([], AUDIT.audit(self.write_zip(entries_for(protocol, library, fixture)))['errors'])
        for protocol, library, fixture in ((772, '1.21.7', '1.21.9'), (773, '1.21.9', '1.21.8'),
                                           (775, '26.1', '26.1.3'), (770, '1.21.7', '1.21.8')):
            with self.subTest(protocol=protocol, library=library, fixture=fixture):
                self.check_bad(entries_for(protocol, library, fixture), 'generated codec/fixture version differs')

    def test_empty_session_service_der_anchors_are_allowed(self):
        entries = synthetic_entries()
        for protocol in (767, 770, 777):
            name = PREFIX + f'engine/p{protocol}/libs/org/geysermc/mcprotocollib/auth/SessionService'
            entries[name + '.class'] = session_anchor(name, r8_marker='~~R8{"backend":"cf","version":"9.5.32"}')
        self.assertEqual([], AUDIT.audit(self.write_zip(entries))['errors'])
        entries[name + '.class'] = session_anchor(name)
        self.assertEqual([], AUDIT.audit(self.write_zip(entries))['errors'])

    def test_session_anchor_allows_only_lookup_innerclasses_metadata(self):
        name = PREFIX + 'engine/p777/libs/org/geysermc/mcprotocollib/auth/SessionService'
        metadata = ('java/lang/invoke/MethodHandles$Lookup', 'java/lang/invoke/MethodHandles', 'Lookup')
        entries = synthetic_entries()
        entries[name + '.class'] = session_anchor(name, inner_classes=metadata)
        self.assertEqual([], AUDIT.audit(self.write_zip(entries))['errors'])
        invalid = (session_anchor(name, dependency='java/lang/invoke/MethodHandles$Lookup'),
                   session_anchor(name, inner_classes=('java/net/http/HttpClient$Builder', 'java/net/http/HttpClient', 'Builder')),
                   session_anchor(name, inner_classes=(metadata[0], metadata[1], 'Other')),
                   session_anchor(name, inner_classes=metadata, method='hasJoinedServer'),
                   session_anchor(name, inner_classes=metadata, dependency='java/net/URL'))
        for data in invalid:
            with self.subTest(data=data[-35:]):
                entries[name + '.class'] = data
                self.check_bad(entries, 'SessionService')

    def test_root_core_slf4j_is_allowed_without_limbo_copies(self):
        entries = synthetic_entries()
        entries['org/slf4j/Logger.class'] = class_header(61)
        entries['org/slf4j/LoggerFactory.class'] = class_header(61)
        self.assertEqual([], AUDIT.audit(self.write_zip(entries))['errors'])
        entries[PREFIX + 'engine/p777/libs/org/slf4j/Logger.class'] = class_header(61)
        self.check_bad(entries, 'per-protocol shared/text dependency copy')
        entries = synthetic_entries()
        entries['com/google/gson/Gson.class'] = class_header(61)
        self.check_bad(entries, 'unrelocated dependency')

    def test_session_service_anchor_rejects_auth_methods_dependencies_and_spoofing(self):
        name = PREFIX + 'engine/p777/libs/org/geysermc/mcprotocollib/auth/SessionService'
        for data in (session_anchor(name, method='joinServer'), session_anchor(name, method='getProfile'),
                     session_anchor(name, dependency='java/net/http/HttpClient'),
                     session_anchor(name, dependency=PREFIX + 'engine/p777/libs/org/apache/hc/HttpClient'),
                     session_anchor(name, r8_marker='https://sessionserver.example.invalid/hasJoined'),
                     session_anchor(name, r8_marker='~~R8{"backend":"dex","version":"fake"}'),
                     session_anchor('synthetic/SpoofedSessionService'), class_header(61)):
            with self.subTest(data=data[-35:]):
                entries = synthetic_entries()
                entries[name + '.class'] = data
                self.check_bad(entries, 'SessionService')

    def test_internal_guard_is_exactly_java21_without_weakening_public_api(self):
        entries = synthetic_entries()
        entries[PREFIX + 'NettyRuntimeGuard.class'] = class_header(65)
        self.assertEqual([], AUDIT.audit(self.write_zip(entries))['errors'])
        entries[PREFIX + 'NettyRuntimeGuard.class'] = class_header(61)
        self.check_bad(entries, 'internal runtime guard must target JVM 21')
        for name in ('NettyRuntimeGuardExtra', 'NettyRuntimeGuard$Unexpected', 'LimboPlayer'):
            with self.subTest(name=name):
                entries = synthetic_entries()
                entries[PREFIX + name + '.class'] = class_header(65)
                self.check_bad(entries, 'public API must target JVM 17')

    def test_forbidden_bytecode_and_payloads(self):
        forbidden = {'io/netty/buffer/ByteBuf.class': 'Netty bytecode',
                     PREFIX + 'engine/p777/libs/io/netty/util/Netty.class': 'Netty bytecode',
                     PREFIX + 'engine/p777/libs/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.class': 'full SDK',
                     PREFIX + 'engine/p777/libs/org/geysermc/mcprotocollib/protocol/ClientListener$1.class': 'full SDK',
                     PREFIX + 'engine/p777/libs/org/geysermc/mcprotocollib/protocol/ServerListener.class': 'full SDK',
                     PREFIX + 'engine/p777/libs/net/raphimc/minecraftauth/MinecraftAuth.class': 'auth/HTTP/JWT',
                     PREFIX + 'engine/p777/libs/org/jose4j/Jwt.class': 'auth/HTTP/JWT',
                     PREFIX + 'engine/p777/libs/org/apache/hc/HttpClient.class': 'auth/HTTP/JWT',
                     'META-INF/native/libnetty_quic.so': 'native/QUIC',
                     'LimboTestProxy.class': 'test/bootstrap',
                     'net/minecraft/server/Main.class': 'game artifact',
                     'META-INF/libraries/server.jar': 'nested SDK/game'}
        for name, message in forbidden.items():
            with self.subTest(name=name):
                entries = synthetic_entries()
                entries[name] = class_header(61)
                self.check_bad(entries, message)

    def test_no_per_protocol_shared_or_text_copies(self):
        for package in ('it/unimi/dsi/fastutil/ints/Map', 'com/google/gson/Gson', 'org/cloudburstmc/nbt/NbtMap',
                        'org/cloudburstmc/math/Vector', 'net/kyori/adventure/text/Component', 'org/slf4j/Logger'):
            with self.subTest(package=package):
                entries = synthetic_entries()
                entries[PREFIX + 'engine/p777/libs/' + package + '.class'] = class_header(61)
                self.check_bad(entries, 'per-protocol shared/text')

    def test_required_der_and_providers(self):
        for missing in ('yggdrasil_session_pubkey.der', 'META-INF/services/' + AUDIT.SHARED.replace('/', '.') + 'org.cloudburstmc.math.vector.VectorProvider',
                        'META-INF/services/' + AUDIT.TEXT.replace('/', '.') + 'json.net.kyori.adventure.text.serializer.json.JSONComponentSerializer$Provider'):
            with self.subTest(missing=missing):
                entries = synthetic_entries()
                del entries[missing]
                self.check_bad(entries, 'missing')
        entries = synthetic_entries()
        entries['yggdrasil_session_pubkey.der'] = b'not-a-DER'
        self.check_bad(entries, 'DER resource')

    def test_duplicate_and_dangling_spi(self):
        entries = synthetic_entries()
        entries[ENGINE_SERVICE] += b'\n' + entries[ENGINE_SERVICE].splitlines()[0]
        self.check_bad(entries, 'duplicate engine')
        entries = synthetic_entries()
        entries['META-INF/services/synthetic.MissingInterface'] = b'synthetic.MissingProvider\n'
        self.check_bad(entries, 'SPI provider class missing')

    def test_fixture_provenance_and_generated_manifest(self):
        entries = synthetic_entries()
        entries['limbo/configuration-777.bin'] = b'changed'
        self.check_bad(entries, 'fixture hash differs')
        entries = synthetic_entries()
        codec = json.loads(entries['limbo/codec-777.json'])
        codec['binarySha256'] = '8' * 64
        codec['packets'][0]['terminal'] = True
        entries['limbo/codec-777.json'] = json.dumps(codec).encode()
        self.check_bad(entries, 'not tied to pinned binarySha256')
        self.check_bad(entries, 'ignored terminal')
        self.check_bad(entries, 'ignored-packet manifest differs')

    def test_api_engine_versions_and_adventure_family(self):
        entries = synthetic_entries()
        entries[PREFIX + 'LimboPlayer.class'] = class_header(65)
        self.check_bad(entries, 'class target must be 61')
        entries = synthetic_entries()
        entries[PREFIX + 'engine/p777/session/EngineFactory.class'] = class_header(61)
        self.check_bad(entries, 'engine own bytecode')
        entries = synthetic_entries()
        entries[PREFIX + 'engine/p767/session/EngineFactory.class'] += (AUDIT.TEXT + 'nbt/net/kyori/').encode()
        self.check_bad(entries, 'wrong Adventure family')

    def test_size_cap_and_ten_mib_target_gap(self):
        entries = synthetic_entries()
        entries['synthetic-size-padding'] = bytes(AUDIT.TARGET)
        artifact = self.write_zip(entries)
        report = AUDIT.audit(artifact)
        self.assertEqual([], report['errors'])
        self.assertGreater(report['target-gap-bytes'], 0)
        report = AUDIT.audit(artifact, max_bytes=artifact.stat().st_size - 1)
        self.assertTrue(any('hard-size-limit' in error for error in report['errors']))
        entries['synthetic-size-padding'] = bytes(AUDIT.HARD_LIMIT)
        self.assertTrue(any('hard-size-limit' in error for error in AUDIT.audit(self.write_zip(entries))['errors']))

    def test_duplicate_zip_entry(self):
        artifact = self.write_zip(synthetic_entries())
        with warnings.catch_warnings(), zipfile.ZipFile(artifact, 'a') as archive:
            warnings.simplefilter('ignore', UserWarning)
            archive.writestr('yggdrasil_session_pubkey.der', b'duplicate')
        self.assertTrue(any('duplicate ZIP entry' in error for error in AUDIT.audit(artifact)['errors']))

    def test_provided_netty_only_explicit_proxy_jar_set(self):
        artifact = self.write_zip(synthetic_entries(), 'artifact.jar')
        provided = self.write_zip({'io/netty/buffer/ByteBuf.class': class_header(61),
                                   'io/netty/buffer/Unpooled.class': class_header(61)}, 'proxy.jar')
        parser = argparse.ArgumentParser()
        self.assertEqual(str(provided), resolve_netty_classpath(parser, str(provided), artifact))
        with mock.patch.dict(os.environ, {'LIMBO_NETTY_CLASSPATH': str(provided)}):
            self.assertEqual(str(provided), resolve_netty_classpath(parser, None, artifact))
        for raw in (str(artifact), str(self.folder), str(self.folder / '*.jar'), '', str(provided) + os.pathsep):
            with self.subTest(raw=raw), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                resolve_netty_classpath(parser, raw, artifact)
        for name in ('org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.class', 'net/minecraft/server/Main.class', PREFIX + 'LimboServer.class'):
            sdk = self.write_zip({name: class_header(61)}, 'forbidden-sdk.jar')
            with self.subTest(name=name), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                resolve_netty_classpath(parser, str(sdk), artifact)

    def test_assembly_async_linkage_errors_override_zero_exit_and_summary(self):
        module = load_tool('limbo-assembly-smoke')
        output = ('ASSEMBLY PASS protocol=767 private-loopback=true\n'
                  'ASSEMBLY SUMMARY engines=1 scope=explicit-protocol-subset all-closed=true renderer=NOT_TESTED\n')
        markers = ('Failed to initialize a channel. Closing: channel', 'java.lang.IncompatibleClassChangeError: sealed Component',
                   'java.lang.NoClassDefFoundError: MissingClass', 'Exception in thread "netty-worker" java.lang.RuntimeException',
                   'java.lang.NoSuchMethodError: missing()V', 'java.lang.VerifyError: Bad type',
                   'java.lang.ExceptionInInitializerError')
        self.assertEqual((0, []), module.assembly_verdict(0, output, '', [767]))
        for marker in markers:
            for stream in ('stdout', 'stderr'):
                with self.subTest(marker=marker, stream=stream):
                    stdout = output + marker if stream == 'stdout' else output
                    stderr = marker if stream == 'stderr' else ''
                    code, failures = module.assembly_verdict(0, stdout, stderr, [767])
                    self.assertNotEqual(0, code)
                    self.assertTrue(failures)
        self.assertEqual(7, module.assembly_verdict(7, output, '', [767])[0])
        self.assertNotEqual(0, module.assembly_verdict(0, '', '', [767])[0])
        self.assertNotEqual(0, module.assembly_verdict(0, output, '', [767, 770])[0])

    def test_assembly_cli_captures_both_streams_and_suppresses_false_pass(self):
        module = load_tool('limbo-assembly-smoke')
        artifact = self.write_zip(synthetic_entries(), 'assembly-artifact.jar')
        provided = self.write_zip({'io/netty/buffer/ByteBuf.class': class_header(61),
                                   'io/netty/buffer/Unpooled.class': class_header(61)}, 'assembly-proxy.jar')
        output = ''.join(f'ASSEMBLY PASS protocol={protocol} private-loopback=true\n' for protocol in PROTOCOLS)
        output += 'ASSEMBLY SUMMARY engines=11 scope=production-eleven all-closed=true renderer=NOT_TESTED\n'
        error = 'Failed to initialize a channel\njava.lang.IncompatibleClassChangeError: sealed Component\n'
        processes = [subprocess.CompletedProcess([], 0), subprocess.CompletedProcess([], 0, output, error)]
        visible = io.StringIO()
        with mock.patch.object(sys, 'argv', ['assembly', '--jar', str(artifact), '--netty-classpath', str(provided)]), \
                mock.patch.object(module, 'ROOT', self.folder), mock.patch.object(module.Path, 'is_file', return_value=True), \
                mock.patch.object(module.subprocess, 'run', side_effect=processes) as execute, contextlib.redirect_stdout(visible):
            code = module.main()
        self.assertNotEqual(0, code)
        self.assertTrue(execute.call_args.kwargs['capture_output'])
        self.assertNotIn('ASSEMBLY SUMMARY', visible.getvalue())
        self.assertNotIn('ASSEMBLY PASS', visible.getvalue())
        self.assertIn('ASSEMBLY FAIL', visible.getvalue())
        log = self.folder / '.narrafork/multiversion/assembly' / artifact.stem / 'assembly.log'
        self.assertIn(output, log.read_text(encoding='utf-8'))
        self.assertIn(error, log.read_text(encoding='utf-8'))

    def test_python_cli_help_and_no_relaxed_hard_cap(self):
        for name in ('verify-limbo.py', 'verify-limbo-matrix.py', 'limbo-assembly-smoke.py', 'audit-limbo-artifact.py'):
            with self.subTest(name=name):
                result = subprocess.run([sys.executable, '-B', str(WORKSPACE / 'tools' / name), '--help'], capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
        artifact = self.write_zip(synthetic_entries())
        result = subprocess.run([sys.executable, '-B', str(WORKSPACE / 'tools/audit-limbo-artifact.py'), str(artifact),
                                 '--max-bytes', str(AUDIT.HARD_LIMIT + 1)], capture_output=True, text=True)
        self.assertEqual(2, result.returncode)


if __name__ == '__main__':
    unittest.main(verbosity=2)
