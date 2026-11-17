"""Read-only parsing regressions for the official selector registry exporter. No JVM or server."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('selector_fixtures', Path(__file__).with_name('extract-role-selector-fixtures.py'))
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)


class SelectorFixtureTests(unittest.TestCase):
    lines = ('ROLE_FIXTURE minecraft:player_head=1105', 'ROLE_FIXTURE minecraft:generic_9x3=2',
             'ROLE_FIXTURE item-key=minecraft:player_head', 'ROLE_FIXTURE menu-key=minecraft:generic_9x3')

    def test_plain_and_vanilla_wrapped_stdout_are_equivalent(self):
        plain = '\n'.join(self.lines)
        wrapped = '\n'.join('[13:12:45] [main/INFO]: [STDOUT]: ' + line for line in self.lines)
        self.assertEqual(fixtures.parse_fixture_output(plain), fixtures.parse_fixture_output(wrapped))
        self.assertEqual('1105', fixtures.parse_fixture_output(wrapped)['minecraft:player_head'])

    def test_missing_duplicate_unknown_and_nonnumeric_are_rejected(self):
        for text in ('\n'.join(self.lines[:-1]), '\n'.join((*self.lines, self.lines[0])),
                     '\n'.join((*self.lines, 'ROLE_FIXTURE unknown=1')),
                     '\n'.join(self.lines).replace('1105', '-1'),
                     '\n'.join(self.lines).replace('1105', '１２３'),
                     '\n'.join(self.lines).replace('1105', '1 ROLE_FIXTURE x=2')):
            with self.subTest(text=text), self.assertRaises(ValueError):
                fixtures.parse_fixture_output(text)


if __name__ == '__main__':
    unittest.main()
