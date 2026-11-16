"""Regression tests for physical Limbo project layout and manifest-driven shrink rules."""
import ast
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("limbo_shrink_layout", ROOT / "tools/shrink-limbo.py")
SHRINK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHRINK)


class LimboLayoutTest(unittest.TestCase):
    def setUp(self):
        base = ROOT / ".narrafork/layout-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def generated(self, protocol=767, directory="unrelated-project/build/generated/limbo-codec"):
        folder = self.root / directory
        resource = folder / "resources/limbo"
        resource.mkdir(parents=True, exist_ok=True)
        (resource / f"codec-{protocol}.json").write_text(json.dumps({"schema": 1, "protocol": protocol}), encoding="utf-8")
        rules = folder / "keep-rules.pro"
        rules.write_text("\n".join((
            "moe.ryuu.kyokouauth.limbo.session.GeneratedCodec",
            "org.geysermc.mcprotocollib.protocol.codec.PacketCodec",
            "net.kyori.adventure.text.Component",
            "com.google.gson.Gson",
            "io.netty.buffer.ByteBuf",
        )), encoding="utf-8")
        return rules

    def test_generated_protocol_is_read_from_manifest_not_directory_name(self):
        for protocol in range(767, 778):
            with self.subTest(protocol=protocol):
                rules = self.generated(protocol, f"arbitrary/{protocol}/build/generated/limbo-codec")
                result = SHRINK.relocated_rules(rules)
                family = "legacy" if protocol <= 769 else "json" if protocol <= 775 else "nbt"
                self.assertIn(f"engine.p{protocol}.session.GeneratedCodec", result)
                self.assertIn(f"engine.p{protocol}.libs.org.geysermc", result)
                self.assertIn(f"text.{family}.net.kyori", result)
                self.assertIn("shared.com.google.gson.Gson", result)
                self.assertIn("io.netty.buffer.ByteBuf", result)

    def test_global_rules_are_unchanged_without_generated_metadata(self):
        rules = self.root / "shrink-rules.pro"
        rules.write_text("-keep class root.Facade { *; }\n", encoding="utf-8")
        self.assertEqual(rules.read_text(), SHRINK.relocated_rules(rules))

    def test_missing_or_ambiguous_manifest_fails_instead_of_silently_skipping(self):
        rules = self.generated()
        metadata = rules.parent / "resources/limbo/codec-767.json"
        metadata.unlink()
        with self.assertRaises(ValueError):
            SHRINK.relocated_rules(rules)
        metadata.write_text('{"protocol":767}', encoding="utf-8")
        metadata.with_name("codec-768.json").write_text('{"protocol":768}', encoding="utf-8")
        with self.assertRaises(ValueError):
            SHRINK.relocated_rules(rules)

    def test_invalid_protocol_and_filename_mismatch_fail_closed(self):
        rules = self.generated()
        metadata = rules.parent / "resources/limbo/codec-767.json"
        for value in (True, "767", 766, 778, None, 768):
            with self.subTest(value=value):
                metadata.write_text(json.dumps({"protocol": value}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    SHRINK.relocated_rules(rules)

    def test_default_resource_pruning_rejects_any_surviving_reader(self):
        SHRINK.verify_resource_pruning("Safe.class", b"safe class bytes")
        for name in SHRINK.REMOVED_DEFAULT_RESOURCES:
            with self.subTest(name=name), self.assertRaises(RuntimeError):
                SHRINK.verify_resource_pruning("Reader.class", b"prefix/" + name.encode() + b"/suffix")
        for protected in ("yggdrasil_session_pubkey.der", "MCProtocolLib-LICENSE.txt", "limbo/configuration-777.bin", "limbo/codec-777.json"):
            self.assertNotIn(protected, SHRINK.REMOVED_DEFAULT_RESOURCES)

    def test_all_projects_are_nested_without_root_compatibility_shells(self):
        self.assertTrue((ROOT / "limbo/api/build.gradle.kts").is_file())
        self.assertTrue((ROOT / "limbo/runtime/build.gradle.kts").is_file())
        self.assertFalse((ROOT / "limbo-api").exists())
        self.assertFalse((ROOT / "limbo-runtime").exists())
        for family in ("legacy", "json", "nbt"):
            self.assertTrue((ROOT / f"limbo/text/{family}/build.gradle.kts").is_file())
            self.assertFalse((ROOT / f"limbo-text-{family}").exists())
        for protocol in range(767, 778):
            self.assertTrue((ROOT / f"limbo/protocols/{protocol}/build.gradle.kts").is_file())
            self.assertFalse((ROOT / f"limbo-protocol-{protocol}").exists())
            self.assertTrue((ROOT / f"limbo/protocols/{protocol}/src/main/resources/limbo/configuration-{protocol}.bin").is_file())

    def test_preparation_and_extraction_target_nested_delivery_resources(self):
        for filename, variable in (("prepare-limbo.py", "delivery"), ("extract-limbo-fixtures.py", "dest")):
            source = ast.parse((ROOT / "tools" / filename).read_text(encoding="utf-8"))
            matches = [node.value for node in ast.walk(source) if isinstance(node, ast.Assign)
                       and any(isinstance(target, ast.Name) and target.id == variable for target in node.targets)
                       and "src/main/resources/limbo" in ast.unparse(node.value)]
            self.assertEqual(1, len(matches), filename)
            expression = compile(ast.Expression(matches[0]), filename, "eval")
            for protocol in range(767, 778):
                actual = eval(expression, {"__builtins__": {}}, {"ROOT": self.root, "protocol": protocol, "str": str})
                self.assertEqual(self.root / f"limbo/protocols/{protocol}/src/main/resources/limbo", actual)


if __name__ == "__main__":
    unittest.main()
