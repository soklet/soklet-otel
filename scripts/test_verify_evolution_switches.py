import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("evolution_switches", Path(__file__).with_name("verify-evolution-switches.py"))
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class EvolutionSwitchTests(unittest.TestCase):
    def test_comments_and_literals_cannot_supply_the_default(self):
        for fake in ('/* default -> true; */', '// default -> true;\n', 'case A -> "default";'):
            count, missing = CHECK.switches_without_default('switch (value) { ' + fake + ' case B -> false; }')
            self.assertEqual((1, [1]), (count, missing))

    def test_nested_default_does_not_protect_outer_switch(self):
        self.assertEqual((2, [1]), CHECK.switches_without_default(
            'switch (outer) { case A -> switch (inner) { default -> true; }; }'))

    def test_top_level_defaults_cover_statement_and_expression_switches(self):
        self.assertEqual((2, []), CHECK.switches_without_default(
            'switch ((outer)) { default: break; }\nreturn switch (value()) { case A -> true; default -> false; };'))


if __name__ == "__main__":
    unittest.main()
