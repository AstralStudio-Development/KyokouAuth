"""Specialize the independent vanilla oracle to fixed official API families (not MCPL codecs)."""
import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

def source(protocol):
    text = (ROOT/'tools/ProtocolOracle.java').read_text(encoding='utf-8')
    if protocol == 777:
        return text
    text = text.replace('Minecraft 26.3 oracle.',f'Minecraft protocol {protocol} official-mapped oracle.')
    if protocol <= 773:
        text = text.replace('Identifier','ResourceLocation').replace('.identifier()', '.location()')
    if protocol <= 772:
        text = text.replace('p.gameProfile().id()','p.gameProfile().getId()').replace('p.gameProfile().name()','p.gameProfile().getName()')
    if protocol <= 774:
        text = text.replace('section.hasOnlyAir() && !section.hasFluid()', 'section.hasOnlyAir()')
    text = text.replace('spawn.previousGameType().isEmpty()', 'spawn.previousGameType() == null')
    text = text.replace('Optional.empty(), false, false, Optional.empty(), 0, 63);', 'null, false, false, Optional.empty(), 0, 63);')
    if protocol <= 775:
        text = text.replace('false, true, false, spawn, false, false);','false, true, false, spawn, false);')
    text = text.replace('packet.chunkData().forEachBlockEntityTag(packet.x(), packet.z(), (pos, type, nbt) -> {',
        'packet.chunkData().getBlockEntitiesTagsConsumer(packet.x(), packet.z()).accept((pos, type, nbt) -> {')
    text = text.replace('payload.tags().values()', 'tagValues(payload)')
    marker = '    private static void validateTagIds('
    helper = '''    @SuppressWarnings("unchecked")
    private static Collection<it.unimi.dsi.fastutil.ints.IntList> tagValues(NetworkPayload payload) {
        try {
            var field = NetworkPayload.class.getDeclaredField("tags");
            field.setAccessible(true);
            return ((Map<?,it.unimi.dsi.fastutil.ints.IntList>)field.get(payload)).values();
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

'''
    text = text.replace(marker,helper+marker)
    if protocol <= 774:
        text = text.replace('RegistryDataLoader.SYNCHRONIZED_REGISTRIES, Runnable::run).join();','RegistryDataLoader.SYNCHRONIZED_REGISTRIES);')
    if protocol <= 772:
        text = text.replace('var factory = PalettedContainerFactory.create(access);','var factory = access.lookupOrThrow(Registries.BIOME);')
        text = text.replace('section.hasOnlyAir() && !section.hasFluid()', 'section.hasOnlyAir()')
    if protocol <= 769:
        text = text.replace('var maps = packet.chunkData().getHeightmaps();',
            'var maps = Map.of(Heightmap.Types.MOTION_BLOCKING, packet.chunkData().getHeightmaps().getLongArray("MOTION_BLOCKING"));')
        text = text.replace('require(packet.content().getStyle().getClickEvent() instanceof ClickEvent.OpenUrl,',
            'require(packet.content().getStyle().getClickEvent() != null && packet.content().getStyle().getClickEvent().getAction() == ClickEvent.Action.OPEN_URL,')
        text = text.replace('var click = (ClickEvent.OpenUrl) packet.content().getStyle().getClickEvent();','var click = packet.content().getStyle().getClickEvent();')
        text = text.replace('click.uri().equals(URI.create(TEST_URL))','click.getValue().equals(TEST_URL)')
        text = text.replace('new ClickEvent.OpenUrl(URI.create(TEST_URL))', 'new ClickEvent(ClickEvent.Action.OPEN_URL, TEST_URL)')
        start = text.index('        CompoundTag old = new CompoundTag();')
        end = text.index('        log("PASS positive-controls',start)
        # old clickEvent/value is the CORRECT official format before 1.21.5, not a valid negative control.
        text = text[:start]+text[end:]
    if protocol == 767:
        text = text.replace('ClientboundLoginFinishedPacket','ClientboundGameProfilePacket')
        text = text.replace('p.gameProfile().id()','p.gameProfile().getId()').replace('p.gameProfile().name()','p.gameProfile().getName()')
        text = text.replace('access.lookupOrThrow(', 'access.registryOrThrow(').replace('loaded.lookupOrThrow(', 'loaded.registryOrThrow(')
        text = text.replace('getValue(OVERWORLD)', 'get(OVERWORLD)')
        text = text.replace('Registries.DIMENSION_TYPE).get(OVERWORLD).orElseThrow()', 'Registries.DIMENSION_TYPE).getHolder(OVERWORLD).orElseThrow()')
        text = text.replace('null, false, false, Optional.empty(), 0, 63);','null, false, false, Optional.empty(), 0);')
        text = '\n'.join(line for line in text.splitlines() if 'spawn.seaLevel()' not in line)+'\n'
        text = text.replace('var pos = p.change().position();','var pos = new net.minecraft.world.phys.Vec3(p.getX(), p.getY(), p.getZ());')
        text = text.replace('p.id() > 0 && p.relatives().isEmpty()', 'p.getId() > 0 && p.getRelativeArguments().isEmpty()')
        start=text.index('        List<Registry.PendingTags<?>> pending = new ArrayList<>();')
        end=text.index('        int entryCount = 0;',start)
        text=text[:start]+'''        for (var entry : tags.entrySet()) {
            Optional<Registry<Object>> registry = statics.registry((ResourceKey)entry.getKey());
            if (registry.isPresent()) {
                validateTagIds(registry.get(), entry.getValue());
                entry.getValue().applyToRegistry(registry.get());
            }
        }
        var loaded = RegistryDataLoader.load(entries, ResourceProvider.EMPTY, statics, RegistryDataLoader.SYNCHRONIZED_REGISTRIES);
        for (var entry : tags.entrySet()) {
            Optional<Registry<Object>> registry = loaded.registry((ResourceKey)entry.getKey());
            if (registry.isPresent()) {
                validateTagIds(registry.get(), entry.getValue());
                entry.getValue().applyToRegistry(registry.get());
            }
        }
'''+text[end:]
        text=text.replace('validateTagIds(registry, tags.getOrDefault(entry.getKey(), NetworkPayload.EMPTY));',
            'if (tags.containsKey(entry.getKey())) validateTagIds(registry, tags.get(entry.getKey()));')
    for old, new in {'p.tags()':'p.getTags()', 'packet.x()':'packet.getX()', 'packet.z()':'packet.getZ()',
        'packet.chunkData()':'packet.getChunkData()', 'packet.lightData()':'packet.getLightData()',
        'light.skyYMask()':'light.getSkyYMask()', 'light.emptySkyYMask()':'light.getEmptySkyYMask()',
        'light.skyUpdates()':'light.getSkyUpdates()', 'light.blockYMask()':'light.getBlockYMask()',
        'light.emptyBlockYMask()':'light.getEmptyBlockYMask()', 'light.blockUpdates()':'light.getBlockUpdates()'}.items():
        text=text.replace(old,new)
    return text

if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('protocol',type=int,choices=range(767,778))
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    output=args.output.resolve()
    if not output.is_relative_to(ROOT/'.narrafork') and not output.is_relative_to(ROOT/'build'):
        parser.error('Generated oracle must stay under .narrafork or build')
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(source(args.protocol),encoding='utf-8')
