"""Gives a tree the line ends a git checkout with core.autocrlf=true would:
CR LF in every file git takes for text, that is one with no NUL and no CR
that isn't followed by LF. Links are left alone. Usage: crlf.py DIR"""
import os, re, sys

converted = skipped = 0
for d, dirs, files in os.walk(sys.argv[1]):
    for f in files:
        p = os.path.join(d, f)
        if os.path.islink(p) or not os.path.isfile(p):
            continue
        data = open(p, 'rb').read()
        if b'\0' in data or re.search(rb'\r(?!\n)', data):
            skipped += 1
            continue
        new = re.sub(rb'(?<!\r)\n', b'\r\n', data)
        if new != data:
            open(p, 'wb').write(new)
            converted += 1
print(f'{converted} files given CR LF line ends; {skipped} left alone as binary')
