"""Compares two buildscan outputs component by component. Usage: compare.py DIR1 DIR2"""
import json, os, sys

def counts(d):
    out = {}
    for c in sorted(os.listdir(d)):
        for kind in ('objasm.json', 'compile_commands.json'):
            f = os.path.join(d, c, kind)
            if os.path.isfile(f):
                out[(c, kind)] = len(json.load(open(f)))
    return out

a, b = counts(sys.argv[1]), counts(sys.argv[2])
diff = {k: (a.get(k), b.get(k)) for k in set(a) | set(b) if a.get(k) != b.get(k)}
print(f'{len(a)} lists before, {len(b)} after; {sum(a.values())} entries before, {sum(b.values())} after')
for k, v in sorted(diff.items()):
    print('  differs:', k, v)
e1 = json.load(open(os.path.join(sys.argv[1], 'environment.json')))
e2 = json.load(open(os.path.join(sys.argv[2], 'environment.json')))
print('path variables the same:', e1['path_variables'] == e2['path_variables'])
bad = [k for k, v in e2['set_by_env_file'].items() if isinstance(v, str) and '\r' in v]
print('variables ending in CR:', bad)
sys.exit(1 if diff or bad else 0)
