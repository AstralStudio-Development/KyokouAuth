"""Read-only argument/classpath helpers for final-artifact Limbo probes."""
import hashlib
import os
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
PROTOCOLS = tuple(range(767, 778))
ENGINE_SERVICE = "META-INF/services/moe.ryuu.kyokouauth.limbo.LimboEngineFactory"


def sha256(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def selected_protocols(parser, requested):
    protocols = list(PROTOCOLS if requested is None else requested)
    if not protocols or len(set(protocols)) != len(protocols) or any(p not in PROTOCOLS for p in protocols):
        parser.error("--protocol requires unique protocol IDs in 767..777")
    return sorted(protocols)


def add_netty_argument(parser):
    parser.add_argument("--netty-classpath", help="Provided Netty JARs separated by the OS classpath separator; "
                        "otherwise LIMBO_NETTY_CLASSPATH, then read-only run-proxy/velocity.jar. No SDK fallback.")


def resolve_netty_classpath(parser, value, artifact):
    supplied = value if value is not None else os.environ.get("LIMBO_NETTY_CLASSPATH")
    raw = str(ROOT / "run-proxy/velocity.jar") if supplied is None else supplied
    if not raw or any(not item for item in raw.split(os.pathsep)):
        parser.error("provided Netty classpath must contain only explicit JAR paths, not empty entries")
    paths = [Path(item).resolve() for item in raw.split(os.pathsep)]
    if len(set(paths)) != len(paths):
        parser.error("provided Netty classpath contains duplicate JARs")
    entries = set()
    for path in paths:
        if not path.is_file() or path.suffix.lower() != ".jar" or "*" in str(path):
            parser.error(f"provided Netty JAR does not exist or is not an explicit .jar: {path}")
        if path == Path(artifact).resolve():
            parser.error("artifact cannot supply its own provided Netty")
        try:
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                if any(name.endswith(".class") and ("moe/ryuu/kyokouauth/limbo/" in name
                        or "org/geysermc/" in name or name.startswith("net/minecraft/")) for name in names):
                    parser.error(f"provided Netty cannot contain Limbo, MCPL SDK or game classes: {path}")
                entries.update(names)
        except zipfile.BadZipFile as failure:
            parser.error(f"invalid provided Netty JAR: {path}: {failure}")
    for required in ("io/netty/buffer/ByteBuf.class", "io/netty/buffer/Unpooled.class"):
        if required not in entries:
            parser.error(f"provided Netty classpath is missing {required}; supply the actual proxy dependency JAR set")
    return os.pathsep.join(map(str, paths))


def artifact_protocols(path):
    """No oracle/bootstrap/full SDK may substitute for a production GeneratedCodec."""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name in names:
            if name.endswith(".class") and (re.search(r"(^|/)io/netty/", name)
                    or name.endswith("/protocol/codec/MinecraftCodec.class")):
                raise ValueError(f"artifact is not trimmed/provided-Netty: {name}")
        providers = [line.split("#", 1)[0].strip() for line in archive.read(ENGINE_SERVICE).decode("utf-8").splitlines()]
        providers = [provider for provider in providers if provider]
        protocols = []
        for provider in providers:
            match = re.fullmatch(r"moe\.ryuu\.kyokouauth\.limbo\.engine\.p(\d+)\.session\.EngineFactory", provider)
            if not match:
                raise ValueError(f"unexpected engine provider: {provider}")
            protocol = int(match[1])
            if protocol not in PROTOCOLS:
                raise ValueError(f"unsupported engine provider: {provider}")
            prefix = f"moe/ryuu/kyokouauth/limbo/engine/p{protocol}/session/"
            for item in ("EngineFactory.class", "GeneratedCodec.class"):
                if prefix + item not in names:
                    raise ValueError(f"artifact is missing {prefix + item}")
            if f"limbo/codec-{protocol}.json" not in names:
                raise ValueError(f"artifact is missing generated codec manifest for {protocol}")
            protocols.append(protocol)
        if not protocols or len(set(protocols)) != len(protocols):
            raise ValueError("artifact requires unique, nonempty engine providers")
        return set(protocols)
