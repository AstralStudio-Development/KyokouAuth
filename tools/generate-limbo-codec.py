#!/usr/bin/env python3
"""Generate a sparse codec from Gradle-resolved, pinned upstream binary/source jars.

No downloads, reference checkout, or cache layout is consulted. The Java compiler's
AST preserves factory expressions; the loaded complete registry supplies packet IDs.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--protocol', type=int, required=True, choices=range(767, 778))
    parser.add_argument('--mcpl-jar', type=Path, required=True)
    parser.add_argument('--sources-jar', type=Path, required=True)
    parser.add_argument('--classpath', required=True, help='Complete resolved tool configuration, OS path separator')
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--java', default='java')
    parser.add_argument('--javac', default='javac')
    parser.add_argument('--verify', action='store_true', help='Compile generated Java and run isolated registry contract checks')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    tool_classes = output / 'tool-classes'
    tool_classes.mkdir(exist_ok=True)
    sources = [root / 'limbo/codec-generator/CodecGenerator.java', root / 'limbo/codec-generator/ItemCodecGenerator.java']
    subprocess.run([args.javac, '-J-Xmx192m', '-J-XX:ActiveProcessorCount=2', '--release', '21',
                    '-encoding', 'UTF-8', '-d', str(tool_classes), *map(str, sources)], check=True)
    classpath = os.pathsep.join([str(tool_classes), str(args.mcpl_jar.resolve()), args.classpath])
    command = [args.java, '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp', classpath, 'CodecGenerator',
               str(args.protocol), str(args.mcpl_jar.resolve()), str(args.sources_jar.resolve()), str(output)]
    # This subtree is wholly generator-owned. Rebuild its source snapshot so retired
    # classes cannot survive a normal incremental run; leave resources/caches alone.
    java_sources = output / 'java'
    if java_sources.resolve() != java_sources:
        raise ValueError('Generated Java output must not alias another directory')
    if java_sources.exists():
        shutil.rmtree(java_sources)
    subprocess.run(command, check=True)
    if args.verify:
        generated_classes = output / 'verified-classes'
        generated_classes.mkdir(exist_ok=True)
        generated_sources = sorted(java_sources.rglob('*.java'))
        subprocess.run([args.javac, '-J-Xmx192m', '-J-XX:ActiveProcessorCount=2', '--release', '21',
                        '-encoding', 'UTF-8', '-cp', classpath, '-d', str(generated_classes),
                        *map(str, generated_sources)], check=True)
        subprocess.run([*command[:3], '-cp', os.pathsep.join([str(generated_classes), classpath]),
                        'CodecGenerator', *command[-4:], '--verify'], check=True)


if __name__ == '__main__':
    main()
