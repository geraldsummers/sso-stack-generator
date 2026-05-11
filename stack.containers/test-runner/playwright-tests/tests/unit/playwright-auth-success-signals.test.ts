import * as fs from 'fs';
import * as path from 'path';

function walk(dir: string): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .flatMap((entry) => {
      const fullPath = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        return walk(fullPath);
      }
      return entry.isFile() && fullPath.endsWith('.ts') ? [fullPath] : [];
    });
}

describe('Playwright authenticated success signals', () => {
  const projectRoot = path.resolve(__dirname, '../..');
  const auditedPaths = [
    path.join(projectRoot, 'tests/deep'),
    path.join(projectRoot, 'tests/fast'),
    path.join(projectRoot, 'utils/drivers'),
  ];

  it('does not use redirects or final URLs as authenticated success assertions', () => {
    const offenders: string[] = [];

    for (const file of auditedPaths.flatMap(walk)) {
      const relative = path.relative(projectRoot, file);
      const source = fs.readFileSync(file, 'utf-8');
      const lines = source.split('\n');

      lines.forEach((line, index) => {
        const normalized = line.trim();
        const isPositiveUrlAssertion =
          /\.toHaveURL\(/.test(normalized) && !/\.not\.toHaveURL\(/.test(normalized);
        const isForwardAuthUrlPattern = /\burlPattern\s*:/.test(normalized);

        if (isPositiveUrlAssertion || isForwardAuthUrlPattern) {
          offenders.push(`${relative}:${index + 1}: ${normalized}`);
        }
      });
    }

    expect(offenders).toEqual([]);
  });
});
