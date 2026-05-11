import * as fs from 'fs';
import * as path from 'path';

const repoRoot = path.resolve(__dirname, '../..');
const visualSuiteScript = path.join(repoRoot, 'scripts/run-visual-suite.sh');

function visualSuiteSpecPaths(): string[] {
  const script = fs.readFileSync(visualSuiteScript, 'utf8');
  return [...script.matchAll(/\btests\/[^\s\\]+\.spec\.ts\b/g)].map((match) => match[0]);
}

describe('visual suite script', () => {
  it('references only existing Playwright spec files', () => {
    const missingSpecs = visualSuiteSpecPaths().filter((specPath) => {
      return !fs.existsSync(path.join(repoRoot, specPath));
    });

    expect(missingSpecs).toEqual([]);
  });
});
