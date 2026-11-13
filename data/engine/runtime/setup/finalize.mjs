import { chmodSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createHash } from 'node:crypto';

const stage = process.argv[2];
const bundles = process.argv[3];
const manifestPath = join(stage, 'node_modules/@deepseek-ai/dsh/package.json');
const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
for (const name of readdirSync(bundles)) {
  const bundle = JSON.parse(readFileSync(join(stage, 'node_modules', name, 'package.json'), 'utf8'));
  (manifest.dependencies ??= {})[bundle.name] = bundle.version;
}
writeFileSync(manifestPath, JSON.stringify(manifest));
// Refresh the direct launcher's existing native-addon inventory after npm installation.
const files = {};
function visit(directory, relative = '') {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    const rel = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) {
      if (relative.endsWith('/prebuilds') &&
          (!entry.name.startsWith(`linux-${process.arch}`) || entry.name.includes('musl'))) {
        rmSync(path, { recursive: true });
      } else visit(path, rel);
    } else if (entry.isFile()) {
      if (entry.name === 'spawn-helper') chmodSync(path, 0o755);
      if (entry.name.endsWith('.node')) files[`/opt/dsh/${rel}`] = createHash('sha256').update(readFileSync(path)).digest('hex');
    }
  }
}
visit(join(stage, 'node_modules'), 'node_modules');
writeFileSync(join(stage, 'native-addons.json'), JSON.stringify({ schema: 1, files }));
