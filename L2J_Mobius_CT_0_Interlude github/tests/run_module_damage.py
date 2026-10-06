"""Run module combat accounting regressions against the actual built game core and native heal handlers."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, default=project.parent / "build" / "bin")
    core = parser.parse_args().core.resolve()
    if not core.exists():
        parser.error(f"Build the core first; missing {core}")
    dependencies = sorted(p for p in (project / "dist" / "libs").glob("*.jar") if "-sources" not in p.name)
    classpath = os.pathsep.join(str(p) for p in [core, *dependencies])
    sources = [project / "tests" / "core" / "ModuleDamageTest.java"]
    sources += [project / "dist" / "game" / "data" / "scripts" / "handlers" / "skill" / "effects" / f"{name}.java"
                for name in ("Heal", "HealPercent")]
    with tempfile.TemporaryDirectory(prefix="l2-module-damage-") as classes:
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", classes, *map(str, sources)], check=True)
        subprocess.run(["java", "-cp", os.pathsep.join([classes, classpath]), "ModuleDamageTest"], check=True, timeout=60)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
