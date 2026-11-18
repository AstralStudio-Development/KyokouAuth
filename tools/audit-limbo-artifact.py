"""Read-only audit of final trimmed Limbo/plugin JARs; default is all eleven engines and a hard 20 MiB cap.

10 MiB is a reported target, not a silently relaxed acceptance limit. Boundary prototypes must
explicitly select --protocol 767 770 777; partial artifacts never pass the default production audit.
This is a ZIP/bytecode-layout audit, not Java linkage, endpoint startup or client rendering proof.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile

from limbo_artifact_support import ENGINE_SERVICE, PROTOCOLS, selected_protocols, sha256

ROOT = 'moe/ryuu/kyokouauth/limbo/'
SHARED = ROOT + 'shared/'
TEXT = ROOT + 'text/'
HARD_LIMIT = 20 * 1024 * 1024
TARGET = 10 * 1024 * 1024
SHA256 = re.compile(r'[0-9a-f]{64}')
PER_PROTOCOL_SHARED = re.compile(r'engine/p\d+/libs/(?:org/cloudburstmc/|it/unimi/dsi/fastutil/|com/nukkitx/|com/google/gson/|org/slf4j/|net/kyori/)')
# Root SLF4J belongs to the unprocessed core/Hikari graph; Limbo's own copies must remain shared-only.
UNRELOCATED = ('org/geysermc/', 'org/cloudburstmc/', 'net/kyori/', 'it/unimi/dsi/fastutil/', 'com/nukkitx/', 'com/google/gson/')
VERSION_ALIASES = {767: frozenset(('1.21', '1.21.1')), 772: frozenset(('1.21.7', '1.21.8')),
                   773: frozenset(('1.21.9', '1.21.10')), 775: frozenset(('26.1', '26.1.1', '26.1.2'))}
BANNED_PACKAGES = ('net/raphimc/', 'net/lenni0451/', 'org/jose4j/', 'org/apache/http/', 'org/apache/hc/',
                   'okhttp3/', 'okio/', 'com/nimbusds/', 'com/auth0/jwt/', 'org/junit/', 'org/mockito/', 'kotlin/test/')
BANNED_TOOLS = {'NewExporter', 'LimboAssemblySmoke', 'LimboTestProxy', 'MultiLimboTestProxy', 'ProtocolOracle',
                'VelocityE2ePlugin', 'BungeeE2ePlugin', 'LimboClientObserver'}


def family(protocol):
    return 'legacy' if protocol <= 769 else 'json' if protocol <= 775 else 'nbt'


def breakdown(infos, top):
    groups = collections.defaultdict(lambda: {'entries': 0, 'compressed': 0, 'uncompressed': 0})
    for info in infos:
        if info.is_dir():
            continue
        match = re.match(re.escape(ROOT) + r'(engine/p\d+|shared|text/[^/]+)/', info.filename)
        group = match[1] if match else 'fixtures-and-provenance' if info.filename.startswith('limbo/') else 'other-plugin-runtime'
        group = groups[group]
        group['entries'] += 1
        group['compressed'] += info.compress_size
        group['uncompressed'] += info.file_size
    largest = sorted((info for info in infos if not info.is_dir()), key=lambda info: (-info.compress_size, info.filename))[:top]
    return dict(sorted(groups.items())), [{'name': info.filename, 'compressed': info.compress_size, 'uncompressed': info.file_size} for info in largest]


def empty_session_anchor_error(data, expected_name):
    """Accept only an inert Object shell, not a renamed/partially retained auth implementation.

    Parse the class file rather than searching text: zero interfaces/fields/methods, no executable
    or member-reference constants, and no runtime dependencies beyond this class and Object.
    SourceFile/R8 provenance and the exact JDK MethodHandles.Lookup InnerClasses row are allowed;
    unreferenced constant-pool baggage and other dependency/metadata classes are not accepted.
    """
    offset = 0
    def take(size):
        nonlocal offset
        if size < 0 or offset + size > len(data):
            raise ValueError('truncated class file')
        value = data[offset:offset + size]
        offset += size
        return value
    def number(size):
        return int.from_bytes(take(size), 'big')
    try:
        if take(4) != b'\xca\xfe\xba\xbe':
            raise ValueError('invalid class magic')
        take(4)  # minor/major are audited separately where relevant.
        pool = [None]
        for _ in range(number(2) - 1):
            tag = number(1)
            if tag == 1:
                pool.append((tag, take(number(2)).decode('utf-8')))
            elif tag in (7, 8):
                pool.append((tag, number(2)))
            else:
                raise ValueError(f'non-anchor constant-pool tag {tag}')
        used_utf8 = set()
        def utf8(index):
            if index <= 0 or index >= len(pool) or pool[index][0] != 1:
                raise ValueError('invalid UTF8 constant-pool reference')
            used_utf8.add(index)
            return pool[index][1]
        def class_name(index):
            if index <= 0 or index >= len(pool) or pool[index][0] != 7:
                raise ValueError('invalid class constant-pool reference')
            return utf8(pool[index][1])
        flags, own, parent = number(2), number(2), number(2)
        if flags & (0x0200 | 0x2000 | 0x4000):
            raise ValueError('anchor must be an ordinary class')
        if class_name(own) != expected_name or class_name(parent) != 'java/lang/Object':
            raise ValueError('anchor class identity/superclass differs')
        references = {class_name(index) for index, item in enumerate(pool) if item and item[0] == 7}
        for kind in ('interfaces', 'fields', 'methods'):
            if number(2) != 0:
                raise ValueError(f'anchor must have zero {kind}')
        metadata_references = set()
        seen_attributes = set()
        for _ in range(number(2)):
            attribute = utf8(number(2))
            length = number(4)
            if attribute in seen_attributes:
                raise ValueError(f'duplicate anchor class attribute {attribute}')
            seen_attributes.add(attribute)
            if attribute == 'SourceFile' and length == 2:
                utf8(number(2))
            elif attribute == 'InnerClasses' and length == 10 and number(2) == 1:
                inner, outer = class_name(number(2)), class_name(number(2))
                simple, inner_flags = utf8(number(2)), number(2)
                if (inner, outer, simple, inner_flags) != ('java/lang/invoke/MethodHandles$Lookup', 'java/lang/invoke/MethodHandles', 'Lookup', 0x0019):
                    raise ValueError('non-anchor InnerClasses metadata')
                metadata_references.update((inner, outer))
            else:
                raise ValueError(f'non-anchor class attribute {attribute}')
        allowed_references = {expected_name, 'java/lang/Object'} | metadata_references
        if references != allowed_references:
            raise ValueError(f'anchor has dependency classes: {sorted(references - allowed_references)}')
        if offset != len(data):
            raise ValueError('trailing class-file data')
        # R8 embeds an otherwise unreferenced CONSTANT_String compilation marker even in empty shells.
        for item in pool:
            if item and item[0] == 8:
                marker = utf8(item[1])
                if not marker.startswith('~~R8'):
                    raise ValueError('non-R8 string constant in anchor')
                metadata = json.loads(marker[4:])
                if not isinstance(metadata, dict) or metadata.get('backend') != 'cf' or not metadata.get('version'):
                    raise ValueError('invalid R8 class-file marker')
        if used_utf8 != {index for index, item in enumerate(pool) if item and item[0] == 1}:
            raise ValueError('unused constant-pool data in anchor')
        return None
    except (ValueError, IndexError, TypeError, UnicodeDecodeError) as failure:
        return str(failure)


def audit(path, protocols=PROTOCOLS, max_bytes=HARD_LIMIT, top=12):
    path = Path(path)
    if not 0 < max_bytes <= HARD_LIMIT or top < 0:
        raise ValueError('audit cap must be in 1..20971520 bytes and top must be nonnegative')
    errors = []
    checks = []
    def require(condition, message):
        if not condition:
            errors.append(message)
        return bool(condition)

    size = path.stat().st_size
    require(size <= max_bytes, f'hard-size-limit: {size} > {max_bytes} bytes')
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        counts = collections.Counter(info.filename for info in infos)
        names = set(counts)
        for name, count in counts.items():
            require(count == 1, f'duplicate ZIP entry: {name} ({count})')
            require('\\' not in name and not name.startswith('/') and '..' not in name.split('/'), f'unsafe ZIP path: {name}')
        groups, largest = breakdown(infos, top)
        def read(name):
            if not require(name in names, f'missing resource: {name}'):
                return b''
            return archive.read(name)
        def document(name):
            raw = read(name)
            try:
                return json.loads(raw) if raw else {}
            except (ValueError, UnicodeDecodeError) as failure:
                errors.append(f'invalid JSON {name}: {failure}')
                return {}
        def service(name):
            try:
                return [item for line in read(name).decode('utf-8').splitlines() if (item := line.split('#', 1)[0].strip())]
            except UnicodeDecodeError:
                errors.append(f'invalid UTF-8 service: {name}')
                return []
        def expect_class(name, major=None):
            data = read(name)
            if not require(len(data) >= 10 and data[:4] == b'\xca\xfe\xba\xbe', f'invalid class header: {name}'):
                return
            if major is not None:
                require(struct.unpack('>H', data[6:8])[0] == major, f'class target must be {major}: {name}')

        expect_class(ROOT + 'LimboServer.class', 65)
        for name in ('LimboPlayer', 'LimboListener', 'LimboLogger', 'LimboSettings', 'LimboEngineFactory', 'EngineContext', 'ForwardingMode', 'RoleSelectorItem', 'RoleSelectorMenu', 'RoleSelectorIcon', 'protocol/TextComponent'):
            expect_class(ROOT + name + '.class', 61)
        require(ROOT + 'RoleAvatarIcon.class' not in names, 'obsolete selector avatar API retained')
        expected_providers = {f'moe.ryuu.kyokouauth.limbo.engine.p{protocol}.session.EngineFactory' for protocol in protocols}
        providers = service(ENGINE_SERVICE)
        require(len(providers) == len(set(providers)), 'duplicate engine provider')
        require(set(providers) == expected_providers, f'engine providers differ: expected={sorted(expected_providers)} actual={providers}')
        actual_protocols = {int(match[1]) for name in names if (match := re.match(re.escape(ROOT) + r'engine/p(\d+)/', name))}
        require(actual_protocols == set(protocols), f'engine namespaces differ: expected={list(protocols)} actual={sorted(actual_protocols)}')
        for prefix in ('configuration-', 'mcpl-', 'codec-', 'ignored-', 'role-selector-', 'items-'):
            actual = {int(match[1]) for name in names if (match := re.fullmatch(r'limbo/' + prefix + r'(\d+)\.(?:json|bin|properties)', name))}
            require(actual == set(protocols), f'{prefix} resource protocols differ: {sorted(actual)}')

        for protocol in protocols:
            base = ROOT + f'engine/p{protocol}/'
            for name in ('EngineFactory', 'GeneratedCodec', 'RoleSelectorPackets', 'ItemDecodeBudget',
                         'BoundedItemCodecHelper' if protocol <= 768 else 'BoundedItemTypes'):
                expect_class(base + 'session/' + name + '.class', 65)
            require(base + 'session/BoundedCustomModelData.class' not in names,
                    f'obsolete selector CMD reader retained: {protocol}')
            fixture = f'limbo/configuration-{protocol}'
            try:
                lines = read(fixture + '.properties').decode('utf-8').splitlines()
                meta = dict(line.split('=', 1) for line in lines if line.strip() and not line.lstrip().startswith('#'))
                require(meta.get('protocol') == str(protocol), f'fixture protocol differs: {protocol}')
                for key in ('server-sha256', 'server-bundler-sha256', 'sha256'):
                    require(bool(SHA256.fullmatch(meta.get(key, ''))), f'fixture missing/invalid {key}: {protocol}')
                require(meta.get('source') == 'official-offline-server-empty-known-packs', f'fixture source differs: {protocol}')
                require(meta.get('known-packs') == 'empty-response-full-registry-nbt', f'fixture is not full empty-known-packs registry: {protocol}')
                require(hashlib.sha256(read(fixture + '.bin')).hexdigest() == meta.get('sha256'), f'fixture hash differs: {protocol}')
                require(int(meta.get('registries', 0)) > 0 and int(meta.get('entries', 0)) > 0, f'empty registry fixture: {protocol}')
            except (ValueError, UnicodeDecodeError) as failure:
                errors.append(f'invalid fixture metadata {protocol}: {failure}')
                meta = {}
            try:
                selector = dict(line.split('=', 1) for line in read(f'limbo/role-selector-{protocol}.properties').decode('utf-8').splitlines()
                                if line.strip() and not line.lstrip().startswith('#'))
                require(selector.get('protocol') == str(protocol), f'selector fixture protocol differs: {protocol}')
                require(selector.get('source') == 'official-server-bootstrap-built-in-registries', f'selector fixture source differs: {protocol}')
                require(selector.get('server-sha256') == meta.get('server-sha256'), f'selector fixture official server differs: {protocol}')
                for key in ('server-sha256', 'registry-server-sha256', 'exporter-sha256'):
                    require(bool(SHA256.fullmatch(selector.get(key, ''))), f'selector fixture invalid {key}: {protocol}')
                item_ids = [int(selector.get(key, 0)) for key in ('player-head-id', 'name-tag-id', 'diamond-id')]
                require(all(value > 0 for value in item_ids) and len(set(item_ids)) == 3 and int(selector.get('generic-9x3-id', -1)) >= 0,
                        f'selector fixture numeric IDs invalid: {protocol}')
            except (ValueError, UnicodeDecodeError) as failure:
                errors.append(f'invalid selector fixture metadata {protocol}: {failure}')
            items = document(f'limbo/items-{protocol}.json')
            require(items.get('schema') == 1 and items.get('protocol') == protocol and
                    items.get('source') == 'pinned-official-mcpl-ast-sparse-item-closure', f'selector item source differs: {protocol}')
            require(items.get('components') == ['CUSTOM_NAME', 'LORE', 'PROFILE'], f'selector component closure differs: {protocol}')
            component_ids = items.get('componentIds', {})
            require(set(component_ids) == {'CUSTOM_NAME', 'LORE', 'PROFILE'} and
                    all(type(value) is int and value >= 0 for value in component_ids.values()) and len(set(component_ids.values())) == 3,
                    f'selector official component IDs invalid: {protocol}')
            for key, maximum in {'frameBytes': 65536, 'changedSlots': 64, 'componentCount': 3, 'listItems': 8,
                                 'stringChars': 8192, 'nbtBytes': 16384, 'nbtDepth': 16}.items():
                require(type(items.get(key)) is int and 0 < items[key] <= maximum, f'selector decode budget differs: {protocol} {key}')
            require(items.get('componentCount') == 3, f'selector component capacity must be three: {protocol}')
            require(not ({'cmdListMaxima', 'cmdFiniteFloats'} & items.keys()), f'obsolete selector CMD budget metadata: {protocol}')
            require(bool(items.get('sources')) and all(bool(SHA256.fullmatch(value)) for value in items.get('sources', {}).values()),
                    f'selector item source fingerprints invalid: {protocol}')
            dependency = document(f'limbo/mcpl-{protocol}.json')
            require(dependency.get('protocol') == protocol and dependency.get('licenses') == ['The MIT License'], f'MCPL provenance/license differs: {protocol}')
            artifacts = dependency.get('artifacts', [])
            require(len(artifacts) == 3 and len({item.get('filename') for item in artifacts}) == 3, f'MCPL must pin POM/binary/sources: {protocol}')
            for item in artifacts:
                require(bool(SHA256.fullmatch(item.get('sha256', ''))) and bool(item.get('url')), f'invalid MCPL artifact provenance: {protocol}')
            codec = document(f'limbo/codec-{protocol}.json')
            require(codec.get('schema') == 1 and codec.get('protocol') == protocol, f'generated codec schema/protocol differs: {protocol}')
            aliases = VERSION_ALIASES.get(protocol, frozenset())
            version_matches = codec.get('minecraftVersion') == meta.get('version') or (
                codec.get('minecraftVersion') in aliases and meta.get('version') in aliases)
            require(bool(meta.get('version')) and version_matches, f'generated codec/fixture version differs: {protocol}')
            for key in ('binarySha256', 'sourcesSha256', 'codecSourceSha256', 'generatorSha256'):
                require(bool(SHA256.fullmatch(codec.get(key, ''))), f'generated codec invalid {key}: {protocol}')
            for suffix, key in (('-sources.jar', 'sourcesSha256'), ('.jar', 'binarySha256')):
                matches = [item for item in artifacts if item.get('filename', '').endswith(suffix)
                           and (suffix != '.jar' or not item.get('filename', '').endswith('-sources.jar'))]
                require(len(matches) == 1 and matches[0].get('sha256') == codec.get(key), f'generated codec not tied to pinned {key}: {protocol}')
            packets = codec.get('packets', [])
            require(bool(packets), f'empty generated codec manifest: {protocol}')
            keys = [(item.get('state'), item.get('direction'), item.get('id')) for item in packets]
            require(len(keys) == len(set(keys)), f'duplicate generated packet ID: {protocol}')
            for item in packets:
                require(item.get('disposition') in ('typed', 'ignored', 'rejected'), f'invalid packet disposition: {protocol}')
                if item.get('disposition') == 'ignored':
                    require(item.get('state') == 'GAME' and item.get('direction') == 'SERVERBOUND' and item.get('terminal') is False,
                            f'ignored terminal/non-GAME/non-serverbound packet: {protocol}')
                if item.get('disposition') == 'typed':
                    require(base + 'libs/' + item.get('class', '').replace('.', '/') + '.class' in names,
                            f'typed packet class missing: {protocol} {item.get("class")}')
            required_inventory = {'ClientboundOpenScreenPacket', 'ClientboundContainerSetContentPacket', 'ClientboundContainerClosePacket',
                                  'ServerboundContainerClickPacket', 'ServerboundContainerClosePacket'}
            actual_inventory = {item.get('class', '').rsplit('.', 1)[-1] for item in packets
                                if item.get('state') == 'GAME' and item.get('disposition') == 'typed'}
            require(required_inventory <= actual_inventory, f'selector typed inventory codecs missing: {protocol}')
            click_name = base + 'libs/org/geysermc/mcprotocollib/protocol/packet/ingame/serverbound/inventory/ServerboundContainerClickPacket.class'
            require(b'ItemDecodeBudget' in read(click_name), f'selector click preallocation guard missing: {protocol}')
            if protocol <= 768:
                require(b'BoundedItemCodecHelper' in read(base + 'session/GeneratedCodec.class'),
                        f'selector bounded legacy factory missing: {protocol}')
            else:
                require(b'BoundedItemTypes' in read(click_name), f'selector bounded item reader missing: {protocol}')
            require(document(f'limbo/ignored-{protocol}.json') == [item for item in packets if item.get('disposition') == 'ignored'],
                    f'ignored-packet manifest differs: {protocol}')
            checks.append({'protocol': protocol, 'family': family(protocol), 'fixture': 'hash-verified', 'registries': meta.get('registries'),
                           'entries': meta.get('entries'), 'codec-manifest-sha256': hashlib.sha256(read(f'limbo/codec-{protocol}.json')).hexdigest()})

        for name in ('org/cloudburstmc/nbt/NbtMap', 'com/google/gson/Gson', 'org/slf4j/LoggerFactory', 'it/unimi/dsi/fastutil/ints/Int2ObjectMap'):
            expect_class(SHARED + name + '.class')
        required_services = [SHARED.replace('/', '.') + item for item in ('org.cloudburstmc.math.imaginary.ImaginaryProvider',
                             'org.cloudburstmc.math.vector.VectorProvider', 'org.slf4j.spi.SLF4JServiceProvider')]
        expected_families = {family(protocol) for protocol in protocols}
        actual_families = {match[1] for name in names if (match := re.match(re.escape(TEXT) + r'([^/]+)/', name))}
        require(actual_families == expected_families, f'Adventure families differ: expected={sorted(expected_families)} actual={sorted(actual_families)}')
        for text_family in sorted(expected_families):
            text_base = TEXT + text_family + '/net/kyori/adventure/'
            expect_class(text_base + 'key/Key.class')
            expect_class(text_base + 'text/Component.class')
            required_services.append((text_base + 'text/serializer/json/JSONComponentSerializer$Provider').replace('/', '.'))
            if text_family != 'legacy':
                required_services.append((text_base + 'text/event/DataComponentValueConverterRegistry$Provider').replace('/', '.'))
        for interface in required_services:
            require(bool(service('META-INF/services/' + interface)), f'missing required math/text/logging provider: {interface}')
        for name in sorted(names):
            if name.startswith('META-INF/services/') and not name.endswith('/'):
                values = service(name)
                require(len(values) == len(set(values)), f'duplicate SPI provider: {name}')
                for provider in values:
                    require(provider.replace('.', '/') + '.class' in names, f'SPI provider class missing: {name} -> {provider}')
                interface = name.removeprefix('META-INF/services/')
                if not interface.startswith(('java.', 'javax.')):
                    require(interface.replace('.', '/') + '.class' in names, f'SPI interface class missing: {interface}')
            require(not name.endswith('module-info.class'), f'module descriptor leaked: {name}')
            require(not (name.startswith('META-INF/') and name.upper().endswith(('.SF', '.DSA', '.RSA'))), f'stale signature: {name}')
            require(not name.startswith('META-INF/native/') and not name.lower().endswith(('.so', '.dll', '.dylib', '.jnilib')),
                    f'native/QUIC payload leaked: {name}')
            require(not name.lower().endswith('.jar'), f'nested SDK/game JAR leaked: {name}')
            require(not name.startswith(('net/minecraft/', 'assets/minecraft/', 'data/minecraft/', 'com/mojang/blaze3d/')), f'game artifact leaked: {name}')
            if not name.endswith('.class'):
                continue
            require(not re.search(r'(^|/)io/netty/', name), f'Netty bytecode must be provided, not embedded: {name}')
            require(not name.startswith(UNRELOCATED), f'unrelocated dependency: {name}')
            require(not PER_PROTOCOL_SHARED.search(name), f'per-protocol shared/text dependency copy: {name}')
            require(not any(re.search(r'(^|/)' + re.escape(package), name) for package in BANNED_PACKAGES), f'auth/HTTP/JWT/test dependency leaked: {name}')
            require(not name.endswith(('/protocol/codec/MinecraftCodec.class', '/protocol/ClientListener.class', '/protocol/ServerListener.class')),
                    f'full SDK/client/auth root leaked: {name}')
            simple = name.rsplit('/', 1)[-1].split('$', 1)[0].removesuffix('.class')
            require(simple not in ('MinecraftCodec', 'ClientListener', 'ServerListener', 'MinecraftAuth'), f'full SDK/client/auth root leaked: {name}')
            if simple == 'SessionService':
                canonical = re.fullmatch(re.escape(ROOT) + r'engine/p\d+/libs/org/geysermc/mcprotocollib/auth/SessionService\.class', name)
                failure = empty_session_anchor_error(archive.read(name), name.removesuffix('.class')) if canonical else 'unexpected anchor namespace or nested implementation'
                require(failure is None, f'invalid SessionService DER/flag anchor: {name}: {failure}')
            require(simple not in BANNED_TOOLS and not simple.endswith(('Test', 'Probe')), f'test/bootstrap class leaked: {name}')
        der = read('yggdrasil_session_pubkey.der')
        require(len(der) > 100 and der[:2] == b'\x30\x82' and int.from_bytes(der[2:4], 'big') + 4 == len(der), 'invalid/missing public-session DER resource')
        majors = collections.Counter()
        for name in names:
            if not name.endswith('.class'):
                continue
            data = archive.read(name)
            if require(len(data) >= 10 and data[:4] == b'\xca\xfe\xba\xbe', f'invalid class header: {name}'):
                major = struct.unpack('>H', data[6:8])[0]
                majors[major] += 1
                if re.match(re.escape(ROOT) + r'engine/p\d+/session/', name):
                    require(major == 65, f'engine own bytecode must target JVM 21: {name}')
                if name == ROOT + 'NettyRuntimeGuard.class':
                    require(major == 65, f'internal runtime guard must target JVM 21: {name}')
                elif name.startswith(ROOT) and not name.startswith((ROOT + 'engine/', SHARED, TEXT, ROOT + 'LimboServer')):
                    require(major == 61, f'public API must target JVM 17: {name}')
                if (match := re.match(re.escape(ROOT) + r'engine/p(\d+)/', name)):
                    for found in re.findall(rb'moe/ryuu/kyokouauth/limbo/text/(legacy|json|nbt)/net/kyori/', data):
                        require(found.decode() == family(int(match[1])), f'engine references wrong Adventure family: {name}')
    return {'artifact': str(path.resolve()), 'artifact-sha256': sha256(path), 'scope': 'production-eleven' if tuple(protocols) == PROTOCOLS else 'explicit-protocol-subset',
            'entries': len(infos), 'bytes': size, 'hard-limit-bytes': max_bytes, 'target-bytes': TARGET, 'target-gap-bytes': max(0, size - TARGET),
            'highest-class-major': max(majors, default=0), 'class-majors': dict(sorted(majors.items())), 'groups': groups, 'largest': largest,
            'protocols': checks, 'errors': errors, 'linkage': 'NOT_TESTED', 'renderer': 'NOT_TESTED'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('jar', type=Path, nargs='+', help='Final aggregate and/or Velocity/Bungee plugin JARs (each is capped)')
    parser.add_argument('--protocol', type=int, nargs='+', choices=PROTOCOLS, help='Explicit prototype scope, e.g. 767 770 777; default strictly eleven')
    parser.add_argument('--max-bytes', type=int, default=HARD_LIMIT, help='Optional stricter cap, never greater than 20*1024*1024')
    parser.add_argument('--top', type=int, default=12, help='Largest compressed ZIP members to report')
    parser.add_argument('--json', action='store_true', help='Emit machine-readable audit/breakdown to stdout; the JAR is never modified')
    args = parser.parse_args()
    protocols = selected_protocols(parser, args.protocol)
    if not 0 < args.max_bytes <= HARD_LIMIT or args.top < 0:
        parser.error('--max-bytes must be in 1..20971520 and --top must be nonnegative')
    reports = []
    for path in args.jar:
        try:
            report = audit(path, protocols, args.max_bytes, args.top)
        except (OSError, zipfile.BadZipFile, ValueError, TypeError, KeyError, AttributeError) as failure:
            report = {'artifact': str(path), 'errors': [f'invalid artifact: {failure}']}
        reports.append(report)
        if args.json:
            continue
        for group, sizes in report.get('groups', {}).items():
            print(f'AUDIT ZIP group={group} compressed={sizes["compressed"]} uncompressed={sizes["uncompressed"]} entries={sizes["entries"]}')
        for item in report.get('largest', []):
            print(f'AUDIT TOP compressed={item["compressed"]} uncompressed={item["uncompressed"]} name={item["name"]}')
        for error in report['errors']:
            print(f'AUDIT FAIL {error}')
        for check in report.get('protocols', []) if not report['errors'] else []:
            print(f'AUDIT PASS protocol={check["protocol"]} family={check["family"]} fixture=hash-verified codec-manifest-sha256={check["codec-manifest-sha256"]}')
        print(f'AUDIT SUMMARY artifact={path} status={"FAIL" if report["errors"] else "PASS"} bytes={report.get("bytes", "?")} '
              f'hard-limit={args.max_bytes} target={TARGET} target-gap={report.get("target-gap-bytes", "?")} '
              f'sha256={report.get("artifact-sha256", "?")} highest-class-major={report.get("highest-class-major", "?")} linkage=NOT_TESTED renderer=NOT_TESTED')
    if args.json:
        print(json.dumps(reports, indent=2, ensure_ascii=False))
    return 1 if any(report['errors'] for report in reports) else 0


if __name__ == '__main__':
    raise SystemExit(main())
