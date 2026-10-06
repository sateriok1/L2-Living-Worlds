"""Run spot-defense manager regressions against a built GameServer.jar or core directory."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core', type=Path, required=True)
    parser.add_argument('--overlay', type=Path)
    args = parser.parse_args()
    dependencies = sorted(p for p in (project / 'dist/libs').glob('*.jar') if '-sources' not in p.name)
    entries = ([args.overlay.resolve()] if args.overlay else []) + [args.core.resolve(), *dependencies]
    classpath = os.pathsep.join(map(str, entries))
    fixtures = sorted((project / 'tests/core/spot-stubs').rglob('*.java'))
    test = project / 'tests/core/PhantomSpotIntegrationTest.java'
    with tempfile.TemporaryDirectory(prefix='l2-spot-') as classes:
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, *map(str, fixtures), str(test)], check=True)
        subprocess.run(['java', '-cp', os.pathsep.join([classes, classpath]), 'PhantomSpotIntegrationTest'], check=True, timeout=60)

if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
