"""Run native preparation and servitor lifecycle regressions against a built GameServer.jar."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--core', type=Path, required=True)
    args = parser.parse_args()
    dependencies = sorted(p for p in (project / 'dist/libs').glob('*.jar') if '-sources' not in p.name)
    classpath = os.pathsep.join(map(str, [args.core.resolve(), *dependencies]))
    fixtures = sorted((project / 'tests/core/encounter-stubs/org/l2jmobius/gameserver/geoengine').rglob('*.java'))
    fixtures += sorted((project / 'tests/core/preparation-stubs').rglob('*.java'))
    test = project / 'tests/core/PhantomPreparationIntegrationTest.java'
    with tempfile.TemporaryDirectory(prefix='l2-preparation-') as classes:
        subprocess.run(['javac', '-encoding', 'UTF-8', '-cp', classpath, '-d', classes, *map(str, fixtures), str(test)], check=True)
        subprocess.run(['java', '-cp', os.pathsep.join([classes, classpath]), 'PhantomPreparationIntegrationTest'], check=True, timeout=60)

if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
