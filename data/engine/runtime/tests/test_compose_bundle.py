"""Offline assertions for the Compose bundle and runtime overlay."""
import importlib.util
import subprocess
from pathlib import Path
import sys
import tempfile
import unittest
import os
import tarfile

sys.dont_write_bytecode = True
RUNTIME = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('rtbuild', RUNTIME / 'lib/rtbuild.py')
rtbuild = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rtbuild)
HOST_PACKAGE = Path(os.environ['DSH_HOST_PACKAGE'])
HOST = tempfile.TemporaryDirectory(prefix='compose-host-package-')
with tarfile.open(HOST_PACKAGE) as package:
    package.extractall(HOST.name, filter='data')
BUNDLE = Path(HOST.name) / 'bundles/dsh-compose-app'


class RuntimeBundle(unittest.TestCase):
    def test_setup_registers_bundles_in_runtime_resolution(self):
        prefix = Path(os.environ.get('DSH_TEST_PREFIX', str(RUNTIME / '.work/engine-build/dependencies')))
        modules = prefix / 'node_modules'
        cli = modules / '@deepseek-ai/dsh/package.json'
        boot = modules / '@deepseek-ai/dsh-app-boot/lib/index.js'
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            # Android installs its bridge outside the engine's bundle directory.
            for bundle in BUNDLE.parent.iterdir():
                if bundle.name == 'dsh-compose-bridge':
                    continue
                target = root / 'node_modules' / bundle.name / 'package.json'
                target.parent.mkdir(parents=True)
                target.write_bytes((bundle / 'package.json').read_bytes())
            anchor = root / 'node_modules/@deepseek-ai/dsh/package.json'
            anchor.parent.mkdir(parents=True)
            anchor.write_bytes(cli.read_bytes())
            bundles = root / 'bundles'
            bundles.mkdir()
            (bundles / BUNDLE.name).mkdir()
            subprocess.run(['node', str(RUNTIME / 'setup/finalize.mjs'), str(root), str(bundles)], check=True)
            script = """
                import assert from 'node:assert/strict';
                const { createRuntimeResolution } = await import(process.argv[1]);
                const resolution = await createRuntimeResolution({ installAnchor: process.argv[2], home: process.argv[3] });
                const entry = resolution.entries.find(entry => entry.name === 'dsh-compose-app');
                assert.ok(entry, 'bundle must be visible to profile plugin imports');
                assert.equal(entry.scope, 'installation');
                assert.equal(entry.declarer, process.argv[2]);
            """
            subprocess.run(['node', '--input-type=module', '-e', script,
                            boot.as_uri(), str(anchor), str(root / 'home/.dsh')], check=True)

    def test_engine_payload_is_separate_from_base_environment(self):
        archive = RUNTIME.parent / 'build/generated/engineAssets/runtime/engine.tgz'
        with tarfile.open(archive) as payload:
            for source in BUNDLE.rglob('*'):
                if source.is_file():
                    name = './bundles/dsh-compose-app/' + source.relative_to(BUNDLE).as_posix()
                    self.assertEqual(payload.extractfile(name).read(), source.read_bytes())
            names = payload.getnames()
            self.assertIn('./install.sh', names)
            self.assertIn('./locks/package-lock.json', names)
            self.assertTrue(any(name.startswith('./overrides/node_modules/') and name.endswith('.js') for name in names))
        entries = rtbuild.overlay_entries({})
        self.assertFalse(any(path.startswith('opt/dsh/node_modules/') for path in entries))

if __name__ == '__main__':
    unittest.main()
